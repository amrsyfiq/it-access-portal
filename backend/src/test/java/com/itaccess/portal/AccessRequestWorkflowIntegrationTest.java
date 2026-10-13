package com.itaccess.portal;

import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.dto.request.CreateAccessRequest;
import com.itaccess.portal.dto.request.ReviewDecision;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.mapper.AccessRequestMapper;
import com.itaccess.portal.mapper.RequestHistoryMapper;
import com.itaccess.portal.security.CurrentUser;
import com.itaccess.portal.security.Role;
import com.itaccess.portal.service.AccessRequestService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full stack against Oracle: HTTP -> controller -> service -> MyBatis -> Hikari -> Oracle. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AccessRequestWorkflowIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AccessRequestService service;
    @Autowired
    private AccessRequestMapper requestMapper;
    @Autowired
    private RequestHistoryMapper historyMapper;

    @Test
    void submitThenApproveThenSecondReviewConflicts() throws Exception {
        String created = mvc.perform(post("/api/requests")
                        .header("X-Demo-User", "ted.l")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requesterName":"Ted L","systemName":"GitLab","accessLevel":"WRITE",
                                 "reason":"Need to push feature branches"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(get("/api/requests/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].oldStatus").doesNotExist())
                .andExpect(jsonPath("$[0].newStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].changedBy").value("ted.l"));

        mvc.perform(patch("/api/requests/{id}/status", id)
                        .header("X-Demo-User", "alice.admin").header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPROVED","comment":"Approved for Q4"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[1].comment").value("Approved for Q4"));

        mvc.perform(patch("/api/requests/{id}/status", id)
                        .header("X-Demo-User", "alice.admin").header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REJECTED"}"""))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/requests").param("status", "APPROVED").param("q", "gitlab"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(id)).exists());
    }

    /**
     * Proves the @Transactional boundary: the status UPDATE succeeds, then the history INSERT fails
     * (comment longer than the VARCHAR2(1000) column; the service is called directly so DTO validation
     * is bypassed). The whole transaction must roll back, leaving the request PENDING.
     */
    @Test
    void failedHistoryInsertRollsBackStatusChange() {
        var created = service.create(new CreateAccessRequest(
                "Ted L", "Datadog", AccessLevel.READ, "Need dashboards access"),
                new CurrentUser("ted.l", Role.EMPLOYEE));
        CurrentUser admin = new CurrentUser("alice.admin", Role.ADMIN);

        assertThatThrownBy(() -> service.review(created.id(),
                new ReviewRequest(ReviewDecision.APPROVED, "x".repeat(1001)), admin))
                .isInstanceOf(RuntimeException.class);

        assertThat(requestMapper.findById(created.id()).orElseThrow().getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(historyMapper.findByRequestId(created.id())).hasSize(1);
    }

    @Test
    void healthEndpointReportsDatabaseUp() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP"));
    }
}
