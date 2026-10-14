package com.itaccess.portal.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.itaccess.portal.security.TestGatewayTokens.ADMIN_SCOPES;
import static com.itaccess.portal.security.TestGatewayTokens.claims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayJwtVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final String USER_ID = "9c3335d6-f079-4c46-a6cb-c6f7a44787ec";

    private final GatewayJwtVerifier verifier = new GatewayJwtVerifier(
            TestGatewayTokens.TEST_PUBLIC_KEY, TestGatewayTokens.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void employeeScopesGiveEmployeeRole() {
        CurrentUser user = verifier.verify(TestGatewayTokens.employee(USER_ID, NOW.plusSeconds(600)));

        assertThat(user).isEqualTo(new CurrentUser(USER_ID, Role.EMPLOYEE));
    }

    @Test
    void reviewScopeGivesAdminRole() {
        CurrentUser user = verifier.verify(TestGatewayTokens.admin(USER_ID, NOW.plusSeconds(600)));

        assertThat(user.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = TestGatewayTokens.admin(USER_ID, NOW.minusSeconds(60));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("expired");
    }

    @Test
    void tokenWithinClockSkewIsAccepted() {
        assertThat(verifier.verify(TestGatewayTokens.employee(USER_ID, NOW.minusSeconds(10)))).isNotNull();
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        String token = TestGatewayTokens.signedByOtherKey(claims("evil", ADMIN_SCOPES, NOW.plusSeconds(600)).build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("signature");
    }

    @Test
    void unsignedTokenIsRejected() {
        String token = TestGatewayTokens.unsigned(claims("evil", ADMIN_SCOPES, NOW.plusSeconds(600)).build());

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidGatewayTokenException.class);
    }

    @Test
    void hmacSignedWithThePublicKeyIsRejected() throws Exception {
        String token = TestGatewayTokens.hmacWithPublicKey(claims("evil", ADMIN_SCOPES, NOW.plusSeconds(600)).build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("algorithm");
    }

    @Test
    void wrongIssuerIsRejected() {
        String token = TestGatewayTokens.signed(claims(USER_ID, ADMIN_SCOPES, NOW.plusSeconds(600))
                .issuer("https://evil.example").build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("issuer");
    }

    @Test
    void missingSubjectIsRejected() {
        String token = TestGatewayTokens.signed(claims(null, ADMIN_SCOPES, NOW.plusSeconds(600)).build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("subject");
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("Malformed");
    }
}
