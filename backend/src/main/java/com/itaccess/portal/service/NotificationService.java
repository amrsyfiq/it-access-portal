package com.itaccess.portal.service;

import com.itaccess.portal.domain.NotificationLog;
import com.itaccess.portal.domain.ProcessingStatus;
import com.itaccess.portal.dto.response.NotificationLogResponse;
import com.itaccess.portal.exception.NotFoundException;
import com.itaccess.portal.domain.RequestHistory;
import com.itaccess.portal.mapper.AccessRequestMapper;
import com.itaccess.portal.mapper.NotificationLogMapper;
import com.itaccess.portal.mapper.RequestHistoryMapper;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.messaging.event.NotificationRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Handles events delivered by Kafka: records the notification in NOTIFICATION_LOG and sends an
 * email (NotificationMailer; locally caught by Mailpit). After commit it announces the result, which
 * the live-update stream pushes to open browsers.
 *
 * Order matters: the row is written first and the email sent last, inside one transaction. If the
 * email fails, the row is rolled back and Kafka retries the event. The remaining at-least-once
 * window: the email is sent but the commit then fails, so the retry sends it again.
 *
 * Kafka delivers at least once, so the same event can arrive twice (e.g. re-read after a consumer
 * restart or rebalance, before its offset was committed).
 * The EVENT_ID unique constraint makes processing idempotent: an event already PROCESSED is skipped.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final NotificationLogMapper notificationMapper;
    private final AccessRequestMapper requestMapper;
    private final RequestHistoryMapper historyMapper;
    private final NotificationMailer mailer;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String failForSystem;

    public NotificationService(NotificationLogMapper notificationMapper, AccessRequestMapper requestMapper,
                               RequestHistoryMapper historyMapper, NotificationMailer mailer,
                               ApplicationEventPublisher events, Clock clock,
                               @Value("${portal.notifications.fail-for-system:}") String failForSystem) {
        this.notificationMapper = notificationMapper;
        this.requestMapper = requestMapper;
        this.historyMapper = historyMapper;
        this.mailer = mailer;
        this.events = events;
        this.clock = clock;
        this.failForSystem = failForSystem;
    }

    @Transactional
    public void handle(AccessRequestEvent event) {
        String eventId = event.eventId().toString();
        Optional<NotificationLog> existing = notificationMapper.findByEventId(eventId);
        if (existing.isPresent() && existing.get().getProcessingStatus() == ProcessingStatus.PROCESSED) {
            log.info("Duplicate event {} ignored (already processed)", eventId);
            return;
        }

        OffsetDateTime now = now();
        if (existing.isPresent()) {
            notificationMapper.markProcessed(eventId, now); // was FAILED before, succeeded now
        } else {
            try {
                notificationMapper.insertProcessed(toLog(event, now));
            } catch (DuplicateKeyException ex) {
                // Another consumer recorded the same event a moment ago.
                log.info("Duplicate event {} ignored (recorded concurrently)", eventId);
                return;
            }
        }

        sendEmail(event); // may throw -> transaction rolls back -> retry -> dead-letter topic

        log.info("Notification for {} {} on request {} recorded as PROCESSED",
                event.eventType(), eventId, event.requestId());
        events.publishEvent(new NotificationRecordedEvent(event.requestId(), eventId, ProcessingStatus.PROCESSED));
    }

    @Transactional
    public void recordFailure(AccessRequestEvent event, String reason) {
        NotificationLog row = toLog(event, now());
        row.setProcessedAt(null);
        row.setErrorMessage(truncate(reason));
        notificationMapper.recordFailure(row);
        events.publishEvent(new NotificationRecordedEvent(event.requestId(), row.getEventId(), ProcessingStatus.FAILED));
    }

    @Transactional(readOnly = true)
    public List<NotificationLogResponse> forRequest(long requestId) {
        if (!requestMapper.existsById(requestId)) {
            throw NotFoundException.accessRequest(requestId);
        }
        return notificationMapper.findByRequestId(requestId).stream().map(NotificationLogResponse::from).toList();
    }

    /**
     * portal.notifications.fail-for-system (off by default) makes sending fail for one system name, to
     * demonstrate retries and the dead-letter topic without stopping the mail server.
     */
    private void sendEmail(AccessRequestEvent event) {
        if (!failForSystem.isBlank() && failForSystem.equalsIgnoreCase(event.systemName())) {
            throw new IllegalStateException("Simulated notification failure for system '" + event.systemName() + "'");
        }
        String submitter = AccessRequestEvent.SUBMITTED.equals(event.eventType()) ? null : submitterOf(event.requestId());
        mailer.send(event, submitter);
    }

    /** The first history entry (null -> PENDING) records who submitted the request. */
    private String submitterOf(long requestId) {
        return historyMapper.findByRequestId(requestId).stream()
                .filter(h -> h.getOldStatus() == null)
                .map(RequestHistory::getChangedBy)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Request " + requestId + " has no submission history"));
    }

    private static NotificationLog toLog(AccessRequestEvent event, OffsetDateTime now) {
        NotificationLog row = new NotificationLog();
        row.setEventId(event.eventId().toString());
        row.setRequestId(event.requestId());
        row.setEventType(event.eventType());
        row.setCreatedAt(now);
        row.setProcessedAt(now);
        return row;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
