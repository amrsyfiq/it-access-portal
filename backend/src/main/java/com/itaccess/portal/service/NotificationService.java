package com.itaccess.portal.service;

import com.itaccess.portal.domain.NotificationLog;
import com.itaccess.portal.domain.ProcessingStatus;
import com.itaccess.portal.dto.response.NotificationLogResponse;
import com.itaccess.portal.exception.NotFoundException;
import com.itaccess.portal.mapper.AccessRequestMapper;
import com.itaccess.portal.mapper.NotificationLogMapper;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Handles events delivered by Kafka: "sends" a notification (simulated with a log line) and
 * records the result in NOTIFICATION_LOG.
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
    private final Clock clock;
    private final String failForSystem;

    public NotificationService(NotificationLogMapper notificationMapper, AccessRequestMapper requestMapper, Clock clock,
                               @Value("${portal.notifications.fail-for-system:}") String failForSystem) {
        this.notificationMapper = notificationMapper;
        this.requestMapper = requestMapper;
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

        sendSimulatedNotification(event); // may throw -> retry -> dead-letter topic

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
        log.info("Notification for {} {} on request {} recorded as PROCESSED",
                event.eventType(), eventId, event.requestId());
    }

    @Transactional
    public void recordFailure(AccessRequestEvent event, String reason) {
        NotificationLog row = toLog(event, now());
        row.setProcessedAt(null);
        row.setErrorMessage(truncate(reason));
        notificationMapper.recordFailure(row);
    }

    @Transactional(readOnly = true)
    public List<NotificationLogResponse> forRequest(long requestId) {
        if (!requestMapper.existsById(requestId)) {
            throw NotFoundException.accessRequest(requestId);
        }
        return notificationMapper.findByRequestId(requestId).stream().map(NotificationLogResponse::from).toList();
    }

    /**
     * Stand-in for sending an email. portal.notifications.fail-for-system (off by default) makes it
     * fail for one system name, to demonstrate retries and the dead-letter topic.
     */
    private void sendSimulatedNotification(AccessRequestEvent event) {
        if (!failForSystem.isBlank() && failForSystem.equalsIgnoreCase(event.systemName())) {
            throw new IllegalStateException("Simulated notification failure for system '" + event.systemName() + "'");
        }
        String text = AccessRequestEvent.SUBMITTED.equals(event.eventType())
                ? "New access request #%d for %s submitted by %s".formatted(event.requestId(), event.systemName(),
                        event.actor())
                : "Access request #%d for %s was %s by %s".formatted(event.requestId(), event.systemName(),
                        event.status(), event.actor());
        log.info("[SIMULATED EMAIL] {}", text);
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
