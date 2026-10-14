package com.itaccess.portal.messaging;

import com.itaccess.portal.messaging.event.AccessRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Producer. The service raises an AccessRequestEvent inside its database transaction; Spring holds
 * it and calls this method only AFTER that transaction commits. So a rolled-back change never
 * produces a message, and consumers never hear about data they cannot read yet.
 *
 * The record key is the request id: Kafka sends equal keys to the same partition, which keeps the
 * events of one request in order.
 *
 * Known gap (documented): if the app crashes, or Kafka is unreachable, between the commit and the
 * send, the event is lost. A transactional outbox would close that gap.
 */
@Component
public class AccessRequestEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestEventPublisher.class);

    private final KafkaTemplate<String, AccessRequestEvent> kafkaTemplate;

    public AccessRequestEventPublisher(KafkaTemplate<String, AccessRequestEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AccessRequestEvent event) {
        try {
            // send() is asynchronous: it returns at once and the callback runs when the broker answers,
            // so the HTTP request does not wait for Kafka.
            kafkaTemplate.send(KafkaConfig.EVENTS_TOPIC, event.key(), event).whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Published {} {} for request {} to {}-{}@{}", event.eventType(), event.eventId(),
                            event.requestId(), KafkaConfig.EVENTS_TOPIC,
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                } else {
                    logLost(event, ex);
                }
            });
        } catch (RuntimeException ex) {
            // e.g. broker unreachable: send() gives up after max.block.ms (see application.yml)
            logLost(event, ex);
        }
    }

    private static void logLost(AccessRequestEvent event, Throwable ex) {
        // The database change is already committed, so the HTTP request still succeeds.
        log.error("Could not publish {} {} for request {}; event lost (no outbox yet): {}",
                event.eventType(), event.eventId(), event.requestId(), ex.getMessage());
    }
}
