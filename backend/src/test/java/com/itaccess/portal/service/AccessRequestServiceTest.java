package com.itaccess.portal.service;

import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.domain.AccessRequest;
import com.itaccess.portal.domain.RequestHistory;
import com.itaccess.portal.domain.RequestSearchCriteria;
import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.dto.request.CreateAccessRequest;
import com.itaccess.portal.dto.request.ReviewDecision;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.dto.response.AccessRequestResponse;
import com.itaccess.portal.exception.ForbiddenOperationException;
import com.itaccess.portal.exception.InvalidStatusTransitionException;
import com.itaccess.portal.exception.NotFoundException;
import com.itaccess.portal.mapper.AccessRequestMapper;
import com.itaccess.portal.mapper.RequestHistoryMapper;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.security.CurrentUser;
import com.itaccess.portal.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure unit tests: mappers are mocked, so these check business rules only (no database). */
@ExtendWith(MockitoExtension.class)
class AccessRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");
    private static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);
    private static final CurrentUser EMPLOYEE = new CurrentUser("ted.l", Role.EMPLOYEE);
    private static final CurrentUser ADMIN = new CurrentUser("alice.admin", Role.ADMIN);

    @Mock
    private AccessRequestMapper requestMapper;
    @Mock
    private RequestHistoryMapper historyMapper;
    @Mock
    private ApplicationEventPublisher events;

    private AccessRequestService service;

    @BeforeEach
    void setUp() {
        service = new AccessRequestService(requestMapper, historyMapper, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createInsertsPendingRequestAndInitialHistory() {
        doAnswer(inv -> {
            inv.<AccessRequest>getArgument(0).setId(42L); // what MyBatis does with the generated key
            return 1;
        }).when(requestMapper).insert(any());

        AccessRequestResponse result = service.create(
                new CreateAccessRequest("  Ted L ", "GitLab", AccessLevel.WRITE, "Need to push to the repo"), EMPLOYEE);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(result.requesterName()).isEqualTo("Ted L");
        assertThat(result.createdAt()).isEqualTo(NOW_UTC);

        ArgumentCaptor<RequestHistory> history = ArgumentCaptor.forClass(RequestHistory.class);
        verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getRequestId()).isEqualTo(42L);
        assertThat(history.getValue().getOldStatus()).isNull();
        assertThat(history.getValue().getNewStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(history.getValue().getChangedBy()).isEqualTo("ted.l");

        ArgumentCaptor<AccessRequestEvent> event = ArgumentCaptor.forClass(AccessRequestEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(AccessRequestEvent.SUBMITTED);
        assertThat(event.getValue().requestId()).isEqualTo(42L);
        assertThat(event.getValue().key()).isEqualTo("42");
        assertThat(event.getValue().eventId()).isNotNull();
    }

    @Test
    void timestampsAreTruncatedToOracleMicrosecondPrecision() {
        Clock nanoClock = Clock.fixed(Instant.parse("2026-10-13T08:00:00.123456789Z"), ZoneOffset.UTC);
        service = new AccessRequestService(requestMapper, historyMapper, events, nanoClock);
        doAnswer(inv -> {
            inv.<AccessRequest>getArgument(0).setId(1L);
            return 1;
        }).when(requestMapper).insert(any());

        AccessRequestResponse result = service.create(
                new CreateAccessRequest("Ted L", "GitLab", AccessLevel.READ, "Need to read the code"), EMPLOYEE);

        assertThat(result.createdAt()).isEqualTo(OffsetDateTime.parse("2026-10-13T08:00:00.123456Z"));
    }

    @Test
    void approveUpdatesStatusAndRecordsHistoryWithComment() {
        when(requestMapper.updateStatusIfPending(7L, RequestStatus.APPROVED, NOW_UTC)).thenReturn(1);
        when(requestMapper.findByIdWithHistory(7L)).thenReturn(Optional.of(request(7L, RequestStatus.APPROVED)));

        var result = service.review(7L, new ReviewRequest(ReviewDecision.APPROVED, " Looks fine "), ADMIN);

        assertThat(result.status()).isEqualTo(RequestStatus.APPROVED);
        ArgumentCaptor<RequestHistory> history = ArgumentCaptor.forClass(RequestHistory.class);
        verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getOldStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(history.getValue().getNewStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(history.getValue().getReviewComment()).isEqualTo("Looks fine");
        assertThat(history.getValue().getChangedBy()).isEqualTo("alice.admin");

        ArgumentCaptor<AccessRequestEvent> event = ArgumentCaptor.forClass(AccessRequestEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(AccessRequestEvent.REVIEWED);
        assertThat(event.getValue().status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(event.getValue().actor()).isEqualTo("alice.admin");
        assertThat(event.getValue().systemName()).isEqualTo("GitLab");
    }

    @Test
    void rejectStoresNullCommentWhenBlank() {
        when(requestMapper.updateStatusIfPending(7L, RequestStatus.REJECTED, NOW_UTC)).thenReturn(1);
        when(requestMapper.findByIdWithHistory(7L)).thenReturn(Optional.of(request(7L, RequestStatus.REJECTED)));

        service.review(7L, new ReviewRequest(ReviewDecision.REJECTED, "   "), ADMIN);

        ArgumentCaptor<RequestHistory> history = ArgumentCaptor.forClass(RequestHistory.class);
        verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getNewStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(history.getValue().getReviewComment()).isNull();
    }

    @Test
    void reviewingAnAlreadyReviewedRequestIsAConflictAndWritesNoHistory() {
        when(requestMapper.updateStatusIfPending(eq(7L), any(), any())).thenReturn(0);
        when(requestMapper.findById(7L)).thenReturn(Optional.of(request(7L, RequestStatus.APPROVED)));

        assertThatThrownBy(() -> service.review(7L, new ReviewRequest(ReviewDecision.REJECTED, null), ADMIN))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("APPROVED");
        verify(historyMapper, never()).insert(any());
        verifyNoInteractions(events);
    }

    @Test
    void reviewingAMissingRequestIsNotFound() {
        when(requestMapper.updateStatusIfPending(eq(99L), any(), any())).thenReturn(0);
        when(requestMapper.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(99L, new ReviewRequest(ReviewDecision.APPROVED, null), ADMIN))
                .isInstanceOf(NotFoundException.class);
        verify(historyMapper, never()).insert(any());
        verifyNoInteractions(events);
    }

    @Test
    void employeesCannotReview() {
        assertThatThrownBy(() -> service.review(7L, new ReviewRequest(ReviewDecision.APPROVED, null), EMPLOYEE))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(requestMapper, historyMapper);
    }

    @Test
    void historyOfMissingRequestIsNotFound() {
        when(requestMapper.existsById(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.history(5L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listPassesFilterAndPagingToMapper() {
        when(requestMapper.search(any())).thenReturn(List.of(request(1L, RequestStatus.PENDING)));
        when(requestMapper.count(any())).thenReturn(21L);

        var page = service.list(RequestStatus.PENDING, "git", 1, 20);

        ArgumentCaptor<RequestSearchCriteria> criteria = ArgumentCaptor.forClass(RequestSearchCriteria.class);
        verify(requestMapper).search(criteria.capture());
        assertThat(criteria.getValue()).isEqualTo(new RequestSearchCriteria(RequestStatus.PENDING, "%GIT%", 20, 20));
        assertThat(page.totalItems()).isEqualTo(21L);
        assertThat(page.items()).hasSize(1);
    }

    @Test
    void likePatternEscapesWildcardsAndIgnoresBlank() {
        assertThat(AccessRequestService.toLikePattern("50%_off")).isEqualTo("%50\\%\\_OFF%");
        assertThat(AccessRequestService.toLikePattern("  ")).isNull();
        assertThat(AccessRequestService.toLikePattern(null)).isNull();
    }

    private static AccessRequest request(long id, RequestStatus status) {
        AccessRequest r = new AccessRequest();
        r.setId(id);
        r.setRequesterName("Ted L");
        r.setSystemName("GitLab");
        r.setAccessLevel(AccessLevel.READ);
        r.setReason("Need to read the code");
        r.setStatus(status);
        r.setCreatedAt(NOW_UTC);
        r.setUpdatedAt(NOW_UTC);
        return r;
    }
}
