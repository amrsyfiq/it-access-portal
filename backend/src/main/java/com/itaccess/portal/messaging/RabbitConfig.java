package com.itaccess.portal.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ topology. Spring's RabbitAdmin declares these exchanges, queues and bindings on the
 * broker at startup (declaring something that already exists with the same settings is a no-op).
 *
 * <pre>
 * topic exchange access-request.events --(access-request.*)--> queue notification.queue
 *                                                                  | rejected after retries
 *                                                                  v
 * direct exchange access-request.dlx --(notification.dlq)----> queue notification.dlq
 * </pre>
 */
@Configuration
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "access-request.events";
    public static final String NOTIFICATION_QUEUE = "notification.queue";
    public static final String DEAD_LETTER_EXCHANGE = "access-request.dlx";
    public static final String DEAD_LETTER_QUEUE = "notification.dlq";

    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }

    /** Messages this queue rejects (after the retries are used up) go to the dead-letter exchange. */
    @Bean
    Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    /** Topic pattern: * matches exactly one word, so both access-request.submitted and .reviewed match. */
    @Bean
    Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue()).to(eventsExchange()).with("access-request.*");
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /** Parking lot for failed messages: inspect them in the management UI, fix the cause, re-publish. */
    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_LETTER_QUEUE);
    }

    /**
     * JSON instead of Java serialization, used by both RabbitTemplate (producer) and the listener
     * (consumer). The listener deserializes into its parameter type; type headers are only trusted
     * for our own event package.
     */
    @Bean
    MessageConverter messageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper, "com.itaccess.portal.messaging.event");
    }
}
