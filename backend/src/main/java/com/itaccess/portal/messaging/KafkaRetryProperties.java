package com.itaccess.portal.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * portal.kafka.retry.* from application.yml, bound into a typed object (instead of many @Value fields).
 *
 * @param maxRetries      retries after the first attempt
 * @param initialInterval wait before the first retry
 * @param multiplier      each later wait is multiplied by this
 * @param maxInterval     upper bound for a single wait
 */
@ConfigurationProperties("portal.kafka.retry")
public record KafkaRetryProperties(
        @DefaultValue("2") int maxRetries,
        @DefaultValue("1s") Duration initialInterval,
        @DefaultValue("2") double multiplier,
        @DefaultValue("10s") Duration maxInterval) {
}
