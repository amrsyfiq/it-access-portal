package com.itaccess.portal.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks the backend JWT that the WSO2 gateway adds to every request it forwards (X-JWT-Assertion)
 * and turns it into a CurrentUser.
 *
 * The gateway has already validated the caller's OAuth2 token and its scopes; this JWT is WSO2's
 * signed statement "this call is from user X with scopes Y". Verifying the signature with WSO2's
 * public certificate proves the request really came through the gateway, so the backend cannot be
 * called around it with forged identity headers.
 */
public class GatewayJwtVerifier {

    public static final String REVIEW_SCOPE = "access_request:review";

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final RSASSAVerifier signatureVerifier;
    private final String expectedIssuer;
    private final Clock clock;

    public GatewayJwtVerifier(RSAPublicKey wso2PublicKey, String expectedIssuer, Clock clock) {
        this.signatureVerifier = new RSASSAVerifier(wso2PublicKey);
        this.expectedIssuer = expectedIssuer;
        this.clock = clock;
    }

    public CurrentUser verify(String token) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException ex) {
            throw new InvalidGatewayTokenException("Malformed gateway JWT");
        }

        // Only accept the algorithm WSO2 is configured to use. Never trust "alg" blindly
        // (classic attacks: "none", or HS256 signed with the public key as the HMAC secret).
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidGatewayTokenException("Unexpected JWT algorithm " + jwt.getHeader().getAlgorithm());
        }
        try {
            if (!jwt.verify(signatureVerifier)) {
                throw new InvalidGatewayTokenException("Gateway JWT signature is invalid");
            }
        } catch (JOSEException ex) {
            throw new InvalidGatewayTokenException("Gateway JWT signature could not be verified");
        }

        Instant now = clock.instant();
        Date expiry = claims.getExpirationTime();
        if (expiry == null || expiry.toInstant().plus(CLOCK_SKEW).isBefore(now)) {
            throw new InvalidGatewayTokenException("Gateway JWT has expired");
        }
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new InvalidGatewayTokenException("Unexpected JWT issuer " + claims.getIssuer());
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidGatewayTokenException("Gateway JWT has no subject");
        }

        // WSO2 4.x identifies users by a stable user id (UUID), not by username.
        return new CurrentUser(subject, scopes(claims).contains(REVIEW_SCOPE) ? Role.ADMIN : Role.EMPLOYEE);
    }

    private static Set<String> scopes(JWTClaimsSet claims) {
        Object scope = claims.getClaim("scope");
        if (!(scope instanceof String value) || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.trim().split("\\s+")).collect(Collectors.toSet());
    }
}
