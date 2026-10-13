package com.itaccess.portal.messaging;

import com.itaccess.portal.messaging.event.AccessRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Producer. The service raises an AccessRequestEvent inside its database transaction; Spring holds
 * it and calls this method only AFTER that transaction commits. So a rolled-back change never
 * produces a message, and consumers never hear about data they cannot read yet.
 *
 * Known gap (documented): if the app crashes, or RabbitMQ is down, between the commit and the send,
 * the event is lost. A transactional outbox would close that gap.
 */
@Component
public class AccessRequestEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public AccessRequestEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AccessRequestEvent event) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, event.routingKey(), event, message -> {
                message.getMessageProperties().setMessageId(event.eventId().toString());
                return message;
            });
            log.info("Published {} {} for request {} (routing key {})",
                    event.eventType(), event.eventId(), event.requestId(), event.routingKey());
        } catch (AmqpException ex) {
            // The database change is already committed, so the HTTP request still succeeds.
            log.error("Could not publish {} {} for request {}; event lost (no outbox yet): {}",
                    event.eventType(), event.eventId(), event.requestId(), ex.getMessage());
        }
    }
}
