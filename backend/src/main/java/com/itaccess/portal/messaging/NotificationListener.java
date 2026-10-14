package com.itaccess.portal.messaging;

import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumer, in consumer group "notification-service". Kafka splits the topic's partitions among the
 * group's members; each record is handled by one member. Runs on a listener thread, not the HTTP
 * thread.
 *
 * If onEvent() returns normally, the record's offset is committed (the group's bookmark moves past
 * it). If it throws, KafkaConfig's error handler retries it, then dead-letters it.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService notificationService;

    public NotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = KafkaConfig.EVENTS_TOPIC, groupId = KafkaConfig.CONSUMER_GROUP)
    public void onEvent(AccessRequestEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Received {} {} for request {} from partition {} offset {}",
                event.eventType(), event.eventId(), event.requestId(), partition, offset);
        notificationService.handle(event);
    }
}
