package com.itaccess.portal.security;

import com.itaccess.portal.config.TimeConfig;
import com.itaccess.portal.controller.AccessRequestController;
import com.itaccess.portal.dto.request.ReviewDecision;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.dto.response.PageResponse;
import com.itaccess.portal.service.AccessRequestService;
import com.itaccess.portal.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller in gateway mode: the filter, the certificate loading and the argument resolver are
 * real; the service is mocked. Tokens are signed with the TEST-ONLY key whose certificate is
 * configured below instead of WSO2's.
 */
@WebMvcTest(value = AccessRequestController.class, properties = {
        "portal.auth.mode=gateway-jwt",
        "portal.gateway.jwt.certificate=classpath:gateway-test/test-gateway-cert.pem",
        "portal.gateway.jwt.issuer=wso2.org/products/am"})
@Import(TimeConfig.class)
class GatewayModeControllerTest {

    private static final String TED = "9c3335d6-f079-4c46-a6cb-c6f7a44787ec";
    private static final String ALICE = "90a0f7b5-b760-4299-9fc6-d64c071ee9fc";
    private static final Instant LATER = Instant.now().plusSeconds(600);

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AccessRequestService service;
    @MockitoBean
    private NotificationService notificationService;

    @Test
    void requestWithoutGatewayJwtIsRejected() throws Exception {
        mvc.perform(get("/api/requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("X-JWT-Assertion")));
        verifyNoInteractions(service);
    }

    @Test
    void demoHeadersAreIgnoredInGatewayMode() throws Exception {
        mvc.perform(patch("/api/requests/7/status")
                        .header("X-Demo-User", "mallory").header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void validEmployeeTokenReachesTheService() throws Exception {
        when(service.list(any(), any(), anyInt(), anyInt())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0));

        mvc.perform(get("/api/requests").header("X-JWT-Assertion", TestGatewayTokens.employee(TED, LATER)))
                .andExpect(status().isOk());
        verify(service).list(null, null, 0, 20);
    }

    @Test
    void identityAndRoleComeFromTheVerifiedJwt() throws Exception {
        mvc.perform(patch("/api/requests/7/status")
                        .header("X-JWT-Assertion", TestGatewayTokens.admin(ALICE, LATER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        verify(service).review(eq(7L), eq(new ReviewRequest(ReviewDecision.APPROVED, null)),
                eq(new CurrentUser(ALICE, Role.ADMIN)));
    }

    @Test
    void forgedJwtIsRejected() throws Exception {
        String forged = TestGatewayTokens.signedByOtherKey(
                TestGatewayTokens.claims("mallory", TestGatewayTokens.ADMIN_SCOPES, LATER).build());

        mvc.perform(get("/api/requests").header("X-JWT-Assertion", forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Gateway JWT signature is invalid"));
        verifyNoInteractions(service);
    }
}
