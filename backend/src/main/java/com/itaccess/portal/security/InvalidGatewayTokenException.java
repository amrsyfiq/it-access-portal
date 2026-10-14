package com.itaccess.portal.security;

/** The gateway JWT is missing, malformed, unsigned, expired or not from WSO2. Results in 401. */
public class InvalidGatewayTokenException extends RuntimeException {

    public InvalidGatewayTokenException(String message) {
        super(message);
    }
}
