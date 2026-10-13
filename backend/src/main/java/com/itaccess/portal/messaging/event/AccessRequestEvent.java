package com.itaccess.portal.messaging.event;

import com.itaccess.portal.domain.RequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * JSON message published to RabbitMQ when a request is submitted or reviewed.
 * One shape for both event types keeps the consumer simple; {@code eventType} says which it is.
 *
 * @param eventId    unique per event; the consumer uses it to ignore duplicates
 * @param eventType  {@link #SUBMITTED} or {@link #REVIEWED}
 * @param requestId  the access request this event is about
 * @param occurredAt when the change happened (UTC)
 * @param actor      who made the change
 * @param systemName requested system (metadata for the notification text)
 * @param status     request status after the change
 */
public record AccessRequestEvent(
        UUID eventId,
        String eventType,
        long requestId,
        Instant occurredAt,
        String actor,
        String systemName,
        RequestStatus status) {

    public static final String SUBMITTED = "AccessRequestSubmitted";
    public static final String REVIEWED = "AccessRequestReviewed";

    public static AccessRequestEvent submitted(long requestId, String systemName, String actor, Instant at) {
        return new AccessRequestEvent(UUID.randomUUID(), SUBMITTED, requestId, at, actor, systemName,
                RequestStatus.PENDING);
    }

    public static AccessRequestEvent reviewed(long requestId, String systemName, RequestStatus newStatus,
                                              String actor, Instant at) {
        return new AccessRequestEvent(UUID.randomUUID(), REVIEWED, requestId, at, actor, systemName, newStatus);
    }

    /** access-request.submitted / access-request.reviewed */
    public String routingKey() {
        return SUBMITTED.equals(eventType) ? "access-request.submitted" : "access-request.reviewed";
    }
}
