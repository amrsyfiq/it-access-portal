package com.itaccess.portal.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

/**
 * Builds fake "gateway" JWTs for tests, signed with the TEST-ONLY key in
 * src/test/resources/gateway-test (see the README there), plus a few deliberately bad ones.
 */
final class TestGatewayTokens {

    static final String ISSUER = "wso2.org/products/am";
    static final String EMPLOYEE_SCOPES = "access_request:read access_request:submit";
    static final String ADMIN_SCOPES = "access_request:read access_request:review access_request:submit";

    private static final PrivateKey TEST_KEY;
    static final RSAPublicKey TEST_PUBLIC_KEY;

    static {
        try (InputStream in = TestGatewayTokens.class.getResourceAsStream("/gateway-test/test-gateway.p12")) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, "test-only".toCharArray());
            TEST_KEY = (PrivateKey) store.getKey("test-gateway", "test-only".toCharArray());
            TEST_PUBLIC_KEY = (RSAPublicKey) store.getCertificate("test-gateway").getPublicKey();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private TestGatewayTokens() {
    }

    static JWTClaimsSet.Builder claims(String subject, String scopes, Instant expiry) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .claim("scope", scopes)
                .claim("http://wso2.org/claims/apicontext", "/accessportal/1.0.0")
                .issueTime(Date.from(expiry.minusSeconds(900)))
                .expirationTime(Date.from(expiry));
    }

    static String signed(JWTClaimsSet claims) {
        return sign(claims, TEST_KEY);
    }

    static String employee(String subject, Instant expiry) {
        return signed(claims(subject, EMPLOYEE_SCOPES, expiry).build());
    }

    static String admin(String subject, Instant expiry) {
        return signed(claims(subject, ADMIN_SCOPES, expiry).build());
    }

    /** Correct format, but signed by a key the backend does not trust (an attacker's own key). */
    static String signedByOtherKey(JWTClaimsSet claims) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return sign(claims, generator.generateKeyPair().getPrivate());
    }

    /** "alg": "none", no signature at all. */
    static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /** HS256, using the PUBLIC key bytes as the HMAC secret (algorithm-confusion attack). */
    static String hmacWithPublicKey(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(TEST_PUBLIC_KEY.getEncoded()));
        return jwt.serialize();
    }

    private static String sign(JWTClaimsSet claims, PrivateKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
