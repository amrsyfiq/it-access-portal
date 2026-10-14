package com.itaccess.portal.controller;

import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.dto.request.ReviewDecision;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.dto.response.AccessRequestResponse;
import com.itaccess.portal.exception.ForbiddenOperationException;
import com.itaccess.portal.exception.InvalidStatusTransitionException;
import com.itaccess.portal.exception.NotFoundException;
import com.itaccess.portal.security.CurrentUser;
import com.itaccess.portal.security.Role;
import com.itaccess.portal.domain.ProcessingStatus;
import com.itaccess.portal.dto.response.NotificationLogResponse;
import com.itaccess.portal.service.AccessRequestService;
import com.itaccess.portal.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice: real controller, validation, JSON and exception handler; the service is mocked.
 * Checks the HTTP contract (status codes and the problem-detail error format).
 */
@WebMvcTest(AccessRequestController.class)
class AccessRequestControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccessRequestService service;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void createReturns201WithLocation() throws Exception {
        OffsetDateTime now = OffsetDateTime.of(2026, 10, 13, 8, 0, 0, 0, ZoneOffset.UTC);
        when(service.create(any(), any())).thenReturn(new AccessRequestResponse(
                5L, "Ted L", "Jira", AccessLevel.READ, "Need to view tickets", RequestStatus.PENDING, now, now));

        mvc.perform(post("/api/requests")
                        .header("X-Demo-User", "ted.l")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requesterName":"Ted L","systemName":"Jira","accessLevel":"READ",
                                 "reason":"Need to view tickets"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "requests/5"))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-13T08:00:00Z"));

        verify(service).create(any(), eq(new CurrentUser("ted.l", Role.EMPLOYEE)));
    }

    @Test
    void createWithMissingFieldsReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requesterName":"","systemName":"Jira","reason":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.requesterName").exists())
                .andExpect(jsonPath("$.errors.accessLevel").exists())
                .andExpect(jsonPath("$.errors.reason").exists());
        verifyNoInteractions(service);
    }

    @Test
    void createWithUnknownEnumReturns400() throws Exception {
        mvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requesterName":"Ted","systemName":"Jira","accessLevel":"ROOT",
                                 "reason":"Need to view tickets"}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void listWithInvalidStatusReturns400() throws Exception {
        mvc.perform(get("/api/requests").param("status", "DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").exists());
    }

    @Test
    void listWithTooLargePageSizeReturns400() throws Exception {
        mvc.perform(get("/api/requests").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());
    }

    @Test
    void missingRequestReturns404Problem() throws Exception {
        when(service.get(99L)).thenThrow(NotFoundException.accessRequest(99L));

        mvc.perform(get("/api/requests/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Access request 99 was not found"));
    }

    @Test
    void reviewOfNonPendingRequestReturns409() throws Exception {
        when(service.review(eq(7L), any(), any()))
                .thenThrow(new InvalidStatusTransitionException(7L, RequestStatus.APPROVED, RequestStatus.REJECTED));

        mvc.perform(patch("/api/requests/7/status")
                        .header("X-Demo-User", "alice.admin").header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REJECTED","comment":"too late"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid status transition"));

        verify(service).review(7L, new ReviewRequest(ReviewDecision.REJECTED, "too late"),
                new CurrentUser("alice.admin", Role.ADMIN));
    }

    @Test
    void reviewByEmployeeReturns403() throws Exception {
        when(service.review(eq(7L), any(), any())).thenThrow(new ForbiddenOperationException("no"));

        mvc.perform(patch("/api/requests/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPROVED"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewCannotSetPending() throws Exception {
        mvc.perform(patch("/api/requests/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PENDING"}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void notificationsListsWhatTheConsumerRecorded() throws Exception {
        when(notificationService.forRequest(7L)).thenReturn(List.of(new NotificationLogResponse(
                "b9c4e7e2-0000-4000-8000-000000000001", "AccessRequestSubmitted", ProcessingStatus.PROCESSED,
                OffsetDateTime.parse("2026-10-13T08:00:00Z"), OffsetDateTime.parse("2026-10-13T08:00:01Z"), null)));

        mvc.perform(get("/api/requests/7/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("AccessRequestSubmitted"))
                .andExpect(jsonPath("$[0].processingStatus").value("PROCESSED"));
    }
}
