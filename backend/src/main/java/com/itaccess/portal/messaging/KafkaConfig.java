package com.itaccess.portal.messaging;

import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.service.NotificationService;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka topics and consumer error handling.
 *
 * <pre>
 * topic access-request.events (3 partitions, key = request id)
 *     --> consumer group "notification-service" (@KafkaListener)
 *            | still failing after the retries
 *            v
 * topic access-request.events.DLT (same partition number as the failed record)
 * </pre>
 *
 * Spring's KafkaAdmin creates the NewTopic beans on the broker at startup if they do not exist.
 */
@Configuration
@EnableConfigurationProperties(KafkaRetryProperties.class)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    public static final String EVENTS_TOPIC = "access-request.events";
    public static final String DEAD_LETTER_TOPIC = "access-request.events.DLT";
    public static final String CONSUMER_GROUP = "notification-service";

    /**
     * Partitions are Kafka's unit of parallelism and ordering: records with the same key always go to
     * the same partition and are read in order. Keying by request id keeps "submitted" before
     * "reviewed" for any one request, while different requests can be processed in parallel.
     */
    private static final int PARTITIONS = 3;

    @Bean
    NewTopic eventsTopic() {
        return TopicBuilder.name(EVENTS_TOPIC).partitions(PARTITIONS).replicas(1).build();
    }

    /** Same partition count, because a failed record is copied to the same partition number here. */
    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * What happens when the listener throws. Spring Boot plugs this bean into every @KafkaListener.
     * 1. Retry the same record in place: 1 attempt + maxRetries retries with exponential back-off.
     *    (While it retries, later records on that partition wait: Kafka preserves order per partition.)
     * 2. Still failing: record FAILED in NOTIFICATION_LOG, copy the record to the dead-letter topic,
     *    and move on. The original offset is committed, so the partition is not blocked forever.
     * Unreadable records (bad JSON) are not retried at all; they go straight to the dead-letter topic.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate,
                                          NotificationService notificationService,
                                          KafkaRetryProperties retry) {
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(DEAD_LETTER_TOPIC, record.partition()));

        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            String reason = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
            if (record.value() instanceof AccessRequestEvent event) {
                log.error("Giving up on {} {} for request {} after retries: {}",
                        event.eventType(), event.eventId(), event.requestId(), reason);
                try {
                    notificationService.recordFailure(event, reason);
                } catch (RuntimeException storeFailure) {
                    log.error("Could not record failure for event {}: {}", event.eventId(), storeFailure.getMessage());
                }
            } else {
                log.error("Unreadable record at {}-{}@{} sent to {}: {}",
                        record.topic(), record.partition(), record.offset(), DEAD_LETTER_TOPIC, reason);
            }
            toDeadLetterTopic.accept(record, ex);
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
