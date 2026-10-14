package com.itaccess.portal.live;

import com.itaccess.portal.messaging.KafkaConfig;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.messaging.event.NotificationRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Bridges events to the browsers' live-update streams.
 *
 * 1. A second Kafka consumer on access-request.events, next to NotificationListener. It uses its OWN
 *    consumer group, so it receives every event independently of the notification consumer (fan-out).
 *    The group name is unique per running instance, so each instance pushes every event to the
 *    browsers connected to it; "latest" means a new instance does not replay old events.
 * 2. NotificationRecordedEvent (in-process, after commit) tells the browsers a notification was stored.
 *
 * Neither method may throw: an exception here would be handled by the Kafka error handler, which
 * belongs to the notification consumer. broadcast() never throws.
 */
@Component
public class LiveUpdateListener {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateListener.class);

    /** What the browser receives for a "request" event. */
    public record RequestChange(long requestId, String eventType, String status, String actor, Instant occurredAt) {
    }

    /** What the browser receives for a "notification" event. */
    public record NotificationChange(long requestId, String eventId, String processingStatus) {
    }

    private final LiveUpdateService liveUpdates;

    public LiveUpdateListener(LiveUpdateService liveUpdates) {
        this.liveUpdates = liveUpdates;
    }

    @KafkaListener(topics = KafkaConfig.EVENTS_TOPIC,
            groupId = "#{'live-updates-' + T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    public void onEvent(AccessRequestEvent event) {
        log.debug("Pushing {} for request {} to live-update streams", event.eventType(), event.requestId());
        liveUpdates.broadcast("request", new RequestChange(event.requestId(), event.eventType(),
                event.status().name(), event.actor(), event.occurredAt()));
    }

    @TransactionalEventListener
    public void onNotificationRecorded(NotificationRecordedEvent event) {
        liveUpdates.broadcast("notification", new NotificationChange(event.requestId(), event.eventId(),
                event.processingStatus().name()));
    }
}
