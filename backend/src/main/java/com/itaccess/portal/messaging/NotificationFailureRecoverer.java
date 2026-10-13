package com.itaccess.portal.messaging;

import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Called by Spring AMQP when a message has failed on every retry. Records a FAILED row in
 * NOTIFICATION_LOG (so the failure is visible in the app), then rejects the message without
 * requeueing; the queue's dead-letter settings move it to notification.dlq.
 * Spring Boot plugs any MessageRecoverer bean into the listener's retry automatically.
 */
@Component
public class NotificationFailureRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(NotificationFailureRecoverer.class);

    private final NotificationService notificationService;
    private final JsonMapper jsonMapper;
    private final MessageRecoverer rejectToDeadLetter = new RejectAndDontRequeueRecoverer();

    public NotificationFailureRecoverer(NotificationService notificationService, JsonMapper jsonMapper) {
        this.notificationService = notificationService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        String reason = NestedExceptionUtils.getMostSpecificCause(cause).getMessage();
        try {
            AccessRequestEvent event = jsonMapper.readValue(message.getBody(), AccessRequestEvent.class);
            log.error("Giving up on {} {} for request {} after retries: {}",
                    event.eventType(), event.eventId(), event.requestId(), reason);
            notificationService.recordFailure(event, reason);
        } catch (RuntimeException ex) {
            // Unreadable message, or the failure could not be stored: the DLQ still keeps the message.
            log.error("Could not record failure for message {}: {}",
                    message.getMessageProperties().getMessageId(), ex.getMessage());
        }
        rejectToDeadLetter.recover(message, cause); // throws -> container rejects -> dead-letter queue
    }
}
