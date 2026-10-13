package com.itaccess.portal.messaging;

import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer. Runs on a listener thread, not the HTTP thread. If handle() returns normally the
 * message is acknowledged and removed from the queue. If it throws, Spring retries it
 * (spring.rabbitmq.listener.simple.retry.*); once the retries are used up, NotificationFailureRecoverer
 * records the failure and the message is rejected into the dead-letter queue.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService notificationService;

    public NotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = RabbitConfig.NOTIFICATION_QUEUE)
    public void onEvent(AccessRequestEvent event) {
        log.info("Received {} {} for request {}", event.eventType(), event.eventId(), event.requestId());
        notificationService.handle(event);
    }
}
