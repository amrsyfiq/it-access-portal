package com.itaccess.portal.service;

import com.itaccess.portal.domain.AccessRequest;
import com.itaccess.portal.domain.RequestHistory;
import com.itaccess.portal.domain.RequestSearchCriteria;
import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.dto.request.CreateAccessRequest;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.dto.response.AccessRequestDetailResponse;
import com.itaccess.portal.dto.response.AccessRequestResponse;
import com.itaccess.portal.dto.response.HistoryEntryResponse;
import com.itaccess.portal.dto.response.PageResponse;
import com.itaccess.portal.exception.ForbiddenOperationException;
import com.itaccess.portal.exception.InvalidStatusTransitionException;
import com.itaccess.portal.exception.NotFoundException;
import com.itaccess.portal.mapper.AccessRequestMapper;
import com.itaccess.portal.mapper.RequestHistoryMapper;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Business rules for access requests. Every public method is one database transaction:
 * Spring opens it before the method runs, commits when it returns, and rolls back if it throws
 * a RuntimeException.
 */
@Service
public class AccessRequestService {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestService.class);

    private final AccessRequestMapper requestMapper;
    private final RequestHistoryMapper historyMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AccessRequestService(AccessRequestMapper requestMapper, RequestHistoryMapper historyMapper,
                                ApplicationEventPublisher events, Clock clock) {
        this.requestMapper = requestMapper;
        this.historyMapper = historyMapper;
        this.events = events;
        this.clock = clock;
    }

    /** Inserts the request and its initial PENDING history row atomically. */
    @Transactional
    public AccessRequestResponse create(CreateAccessRequest command, CurrentUser user) {
        OffsetDateTime now = now();

        AccessRequest request = new AccessRequest();
        request.setRequesterName(command.requesterName().trim());
        request.setSystemName(command.systemName().trim());
        request.setAccessLevel(command.accessLevel());
        request.setReason(command.reason().trim());
        request.setStatus(RequestStatus.PENDING);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        requestMapper.insert(request); // fills request.id from the Oracle IDENTITY column

        historyMapper.insert(new RequestHistory(request.getId(), null, RequestStatus.PENDING, null, now,
                user.username()));

        log.info("Access request {} created by {} for system '{}'", request.getId(), user.username(),
                request.getSystemName());
        // Held by Spring until this transaction commits; then AccessRequestEventPublisher sends it.
        events.publishEvent(AccessRequestEvent.submitted(request.getId(), request.getSystemName(), user.username(),
                now.toInstant()));
        return AccessRequestResponse.from(request);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessRequestResponse> list(RequestStatus status, String query, int page, int size) {
        RequestSearchCriteria criteria = new RequestSearchCriteria(status, toLikePattern(query), page * size, size);
        List<AccessRequestResponse> items = requestMapper.search(criteria).stream()
                .map(AccessRequestResponse::from)
                .toList();
        return new PageResponse<>(items, page, size, requestMapper.count(criteria));
    }

    @Transactional(readOnly = true)
    public AccessRequestDetailResponse get(long id) {
        return requestMapper.findByIdWithHistory(id)
                .map(AccessRequestDetailResponse::from)
                .orElseThrow(() -> NotFoundException.accessRequest(id));
    }

    @Transactional(readOnly = true)
    public List<HistoryEntryResponse> history(long id) {
        if (!requestMapper.existsById(id)) {
            throw NotFoundException.accessRequest(id);
        }
        return historyMapper.findByRequestId(id).stream().map(HistoryEntryResponse::from).toList();
    }

    /**
     * Approves or rejects a PENDING request. The status update and the history insert are in one
     * transaction: if the history insert fails, the status change is rolled back too.
     */
    @Transactional
    public AccessRequestDetailResponse review(long id, ReviewRequest command, CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ForbiddenOperationException("Only administrators can review access requests");
        }
        RequestStatus newStatus = command.status().toStatus();
        OffsetDateTime now = now();

        int updated = requestMapper.updateStatusIfPending(id, newStatus, now);
        if (updated == 0) {
            // Either the request does not exist or it is no longer PENDING.
            AccessRequest existing = requestMapper.findById(id)
                    .orElseThrow(() -> NotFoundException.accessRequest(id));
            throw new InvalidStatusTransitionException(id, existing.getStatus(), newStatus);
        }

        String comment = StringUtils.hasText(command.comment()) ? command.comment().trim() : null;
        historyMapper.insert(new RequestHistory(id, RequestStatus.PENDING, newStatus, comment, now, user.username()));

        log.info("Access request {} {} by {}", id, newStatus, user.username());
        AccessRequestDetailResponse result = get(id);
        events.publishEvent(AccessRequestEvent.reviewed(id, result.systemName(), newStatus, user.username(),
                now.toInstant()));
        return result;
    }

    /**
     * Current UTC time at the precision Oracle stores (TIMESTAMP(6) = microseconds), so the value
     * returned right after an insert is identical to what a later read returns.
     */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Builds a case-insensitive "contains" pattern for LIKE. The value is still bound as a parameter
     * (no SQL injection); escaping only stops user-typed % and _ from acting as wildcards.
     */
    static String toLikePattern(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String escaped = query.trim().toUpperCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
