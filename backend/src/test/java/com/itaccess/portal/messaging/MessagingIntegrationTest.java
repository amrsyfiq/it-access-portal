package com.itaccess.portal.messaging;

import com.itaccess.portal.TestcontainersConfiguration;
import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.domain.ProcessingStatus;
import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.dto.request.CreateAccessRequest;
import com.itaccess.portal.dto.request.ReviewDecision;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.dto.response.NotificationLogResponse;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.security.CurrentUser;
import com.itaccess.portal.security.Role;
import com.itaccess.portal.service.AccessRequestService;
import com.itaccess.portal.service.NotificationService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End to end through a real Kafka broker and Oracle:
 * service -> (after commit) KafkaTemplate -> topic -> @KafkaListener -> NOTIFICATION_LOG.
 * Consumption is asynchronous, so assertions wait (Awaitility) instead of checking immediately.
 * The timeout is generous because a consumer group needs a few seconds to join on first start.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MessagingIntegrationTest {

    private static final CurrentUser EMPLOYEE = new CurrentUser("ted.l", Role.EMPLOYEE);
    private static final CurrentUser ADMIN = new CurrentUser("alice.admin", Role.ADMIN);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private AccessRequestService requestService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private KafkaTemplate<String, AccessRequestEvent> kafkaTemplate;
    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    private long submit(String system) {
        return requestService.create(
                new CreateAccessRequest("Ted L", system, AccessLevel.READ, "Integration test request"), EMPLOYEE).id();
    }

    private List<NotificationLogResponse> notifications(long requestId) {
        return notificationService.forRequest(requestId);
    }

    @Test
    void submittedRequestIsPublishedConsumedAndLogged() {
        long id = submit("GitLab");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notifications(id))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.eventType()).isEqualTo(AccessRequestEvent.SUBMITTED);
                    assertThat(n.processingStatus()).isEqualTo(ProcessingStatus.PROCESSED);
                    assertThat(n.processedAt()).isNotNull();
                    assertThat(n.errorMessage()).isNull();
                }));
    }

    @Test
    void reviewPublishesAReviewedEvent() {
        long id = submit("Jira");
        requestService.review(id, new ReviewRequest(ReviewDecision.APPROVED, "ok"), ADMIN);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notifications(id))
                .extracting(NotificationLogResponse::eventType)
                .containsExactlyInAnyOrder(AccessRequestEvent.SUBMITTED, AccessRequestEvent.REVIEWED));
    }

    @Test
    void sameEventDeliveredTwiceIsRecordedOnce() {
        long id = submit("Confluence");
        await().atMost(TIMEOUT).until(() -> notifications(id).size() == 1);

        // Simulate a redelivery: publish the very same event (same eventId) twice.
        AccessRequestEvent event = AccessRequestEvent.reviewed(id, "Confluence", RequestStatus.APPROVED, "alice.admin",
                Instant.now());
        kafkaTemplate.send(KafkaConfig.EVENTS_TOPIC, event.key(), event);
        kafkaTemplate.send(KafkaConfig.EVENTS_TOPIC, event.key(), event);

        await().atMost(TIMEOUT).until(() -> notifications(id).size() == 2);
        // ...and it stays at 2: the duplicate was consumed and skipped, not stored again.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> notifications(id).size() == 2);
        assertThat(notifications(id)).filteredOn(n -> n.eventId().equals(event.eventId().toString())).hasSize(1);
    }

    @Test
    void failingNotificationIsRetriedThenLoggedAsFailedAndDeadLettered() {
        // "Always Failing System" is configured in src/test/resources/application.properties
        long id = submit("Always Failing System");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notifications(id))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.processingStatus()).isEqualTo(ProcessingStatus.FAILED);
                    assertThat(n.errorMessage()).contains("Simulated notification failure");
                    assertThat(n.processedAt()).isNull();
                }));
        String failedEventId = notifications(id).getFirst().eventId();

        ConsumerRecord<?, ?> deadLettered = readDeadLetterRecordContaining(failedEventId);
        assertThat(header(deadLettered, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(KafkaConfig.EVENTS_TOPIC);
        assertThat(header(deadLettered, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("Simulated notification failure");
        assertThat(String.valueOf(deadLettered.key())).isEqualTo(String.valueOf(id));
    }

    @Test
    void rolledBackReviewPublishesNothing() {
        long id = submit("Snowflake");
        await().atMost(TIMEOUT).until(() -> notifications(id).size() == 1);

        // Comment too long for the column -> history insert fails -> whole review transaction rolls back.
        assertThatThrownBy(() -> requestService.review(id,
                new ReviewRequest(ReviewDecision.APPROVED, "x".repeat(1001)), ADMIN))
                .isInstanceOf(RuntimeException.class);

        // The "reviewed" event was raised inside the transaction but never sent, because it never committed.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> notifications(id).size() == 1);
        assertThat(notifications(id).getFirst().eventType()).isEqualTo(AccessRequestEvent.SUBMITTED);
    }

    /** Reads the dead-letter topic from the start with a throwaway consumer group (as a person would). */
    private ConsumerRecord<?, ?> readDeadLetterRecordContaining(String text) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        AtomicReference<ConsumerRecord<?, ?>> found = new AtomicReference<>();
        try (Consumer<?, ?> consumer = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null, null,
                overrides)) {
            consumer.subscribe(List.of(KafkaConfig.DEAD_LETTER_TOPIC));
            await().atMost(TIMEOUT).until(() -> {
                for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(500))) {
                    if (String.valueOf(record.value()).contains(text)) {
                        found.set(record);
                        return true;
                    }
                }
                return false;
            });
        }
        return found.get();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
