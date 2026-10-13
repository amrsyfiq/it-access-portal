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
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End to end through a real RabbitMQ broker and Oracle:
 * service -> (after commit) RabbitTemplate -> exchange -> queue -> @RabbitListener -> NOTIFICATION_LOG.
 * Consumption is asynchronous, so assertions wait (Awaitility) instead of checking immediately.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MessagingIntegrationTest {

    private static final CurrentUser EMPLOYEE = new CurrentUser("ted.l", Role.EMPLOYEE);
    private static final CurrentUser ADMIN = new CurrentUser("alice.admin", Role.ADMIN);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private AccessRequestService requestService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private RabbitTemplate rabbitTemplate;

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
        rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, event.routingKey(), event);
        rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, event.routingKey(), event);

        await().atMost(TIMEOUT).until(() -> notifications(id).size() == 2);
        // ...and it stays at 2: the duplicate was acknowledged and skipped, not stored again.
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
        Message deadLettered = rabbitTemplate.receive(RabbitConfig.DEAD_LETTER_QUEUE, TIMEOUT.toMillis());
        assertThat(deadLettered).as("message in %s", RabbitConfig.DEAD_LETTER_QUEUE).isNotNull();
        assertThat(deadLettered.getMessageProperties().getMessageId()).isEqualTo(failedEventId);
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
}
