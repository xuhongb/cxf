/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.sts.token.validator;

import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.callback.CallbackHandler;

import org.w3c.dom.Document;
import org.w3c.dom.Text;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.sts.STSPropertiesMBean;
import org.apache.cxf.sts.request.ReceivedToken;
import org.apache.cxf.sts.request.TokenRequirements;

import org.apache.cxf.ws.security.sts.provider.model.secext.BinarySecurityTokenType;

import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSSConfig;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.handler.RequestData;
import org.apache.ws.security.message.token.BinarySecurity;
import org.apache.ws.security.message.token.X509Security;
import org.apache.ws.security.validate.Credential;
import org.apache.ws.security.validate.SignatureTrustValidator;
import org.apache.ws.security.validate.Validator;

/**
 * This class validates an X.509 V.3 certificate (received as a BinarySecurityToken). The cert must
 * be known (or trusted) by the STS crypto object.
 */
public class X509TokenValidator implements TokenValidator {
    
    public static final String X509_V3_TYPE = WSConstants.X509TOKEN_NS + "#X509v3";
    
    public static final String BASE64_ENCODING = WSConstants.SOAPMESSAGE_NS + "#Base64Binary";
    
    private static final Logger LOG = LogUtils.getL7dLogger(X509TokenValidator.class);
    
    private Validator validator = new SignatureTrustValidator();

    /**
     * Set the WSS4J Validator instance to use to validate the token.
     * @param validator the WSS4J Validator instance to use to validate the token
     */
    public void setValidator(Validator validator) {
        this.validator = validator;
    }
    
    /**
     * Return true if this TokenValidator implementation is capable of validating the
     * ReceivedToken argument.
     */
    public boolean canHandleToken(ReceivedToken validateTarget) {
        return canHandleToken(validateTarget, null);
    }
    
    /**
     * Return true if this TokenValidator implementation is capable of validating the
     * ReceivedToken argument. The realm is ignored in this token Validator.
     */
    public boolean canHandleToken(ReceivedToken validateTarget, String realm) {
        Object token = validateTarget.getToken();
        if ((token instanceof BinarySecurityTokenType)
            && X509_V3_TYPE.equals(((BinarySecurityTokenType)token).getValueType())) {
            return true;
        }
        return false;
    }
    
    /**
     * Validate a Token using the given TokenValidatorParameters.
     */
    public TokenValidatorResponse validateToken(TokenValidatorParameters tokenParameters) {
        LOG.fine("Validating X.509 Token");
        TokenRequirements tokenRequirements = tokenParameters.getTokenRequirements();
        ReceivedToken validateTarget = tokenRequirements.getValidateTarget();

        STSPropertiesMBean stsProperties = tokenParameters.getStsProperties();
        Crypto sigCrypto = stsProperties.getSignatureCrypto();
        CallbackHandler callbackHandler = stsProperties.getCallbackHandler();

        RequestData requestData = new RequestData();
        requestData.setSigCrypto(sigCrypto);
        requestData.setWssConfig(WSSConfig.getNewInstance());
        requestData.setCallbackHandler(callbackHandler);

        TokenValidatorResponse response = new TokenValidatorResponse();
        response.setValid(false);
        
        if (validateTarget == null || !validateTarget.isBinarySecurityToken()) {
            return response;
        }

        BinarySecurityTokenType binarySecurityType = (BinarySecurityTokenType)validateTarget.getToken();

        // Test the encoding type
        String encodingType = binarySecurityType.getEncodingType();
        if (!BASE64_ENCODING.equals(encodingType)) {
            LOG.fine("Bad encoding type attribute specified: " + encodingType);
            return response;
        }

        //
        // Turn the received JAXB object into a DOM element
        //
        Document doc = DOMUtils.createDocument();
        BinarySecurity binarySecurity = new X509Security(doc);
        binarySecurity.setEncodingType(encodingType);
        binarySecurity.setValueType(binarySecurityType.getValueType());
        String data = binarySecurityType.getValue();
        ((Text)binarySecurity.getElement().getFirstChild()).setData(data);

        //
        // Validate the token
        //
        try {
            Credential credential = new Credential();
            credential.setBinarySecurityToken(binarySecurity);
            if (sigCrypto != null) {
                X509Certificate cert = ((X509Security)binarySecurity).getX509Certificate(sigCrypto);
                credential.setCertificates(new X509Certificate[]{cert});
            }

            Credential returnedCredential = validator.validate(credential, requestData);
            response.setPrincipal(returnedCredential.getCertificates()[0].getSubjectX500Principal());
            response.setValid(true);
        } catch (WSSecurityException ex) {
            LOG.log(Level.WARNING, "", ex);
        }
        return response;
    }
    
}
