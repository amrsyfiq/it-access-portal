package com.itaccess.portal.live;

import com.itaccess.portal.TestcontainersConfiguration;
import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.dto.request.CreateAccessRequest;
import com.itaccess.portal.dto.request.ReviewDecision;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.security.CurrentUser;
import com.itaccess.portal.security.Role;
import com.itaccess.portal.service.AccessRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The two consumers of a request change, end to end with real Kafka, Oracle and Mailpit:
 *  - the notification consumer emails the right people (read back from Mailpit's API)
 *  - the live-update consumer pushes the change to an open SSE stream (read with a real HTTP client)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class EmailAndLiveUpdatesIntegrationTest {

    private static final CurrentUser EMPLOYEE = new CurrentUser("ted.l", Role.EMPLOYEE);
    private static final CurrentUser ADMIN = new CurrentUser("alice.admin", Role.ADMIN);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;
    @Autowired
    private AccessRequestService requestService;

    private long submitAndApprove(String system) {
        long id = requestService.create(
                new CreateAccessRequest("Ted L", system, AccessLevel.READ, "Integration test request"), EMPLOYEE).id();
        requestService.review(id, new ReviewRequest(ReviewDecision.APPROVED, "ok"), ADMIN);
        return id;
    }

    @Test
    void adminsAndSubmitterReceiveEmails() {
        long id = submitAndApprove("Confluence");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(mailsAbout(id))
                .containsExactlyInAnyOrder(
                        "it-admins@example.com | New access request #" + id + ": Confluence",
                        "ted.l@example.com | Your access request #" + id + " was approved"));
    }

    @Test
    void openStreamReceivesTheRequestChangesAndTheRecordedNotifications() {
        List<String> lines = new CopyOnWriteArrayList<>();
        HttpRequest streamRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/stream"))
                .header("Accept", "text/event-stream").build();
        CompletableFuture<HttpResponse<Stream<String>>> response =
                http.sendAsync(streamRequest, HttpResponse.BodyHandlers.ofLines());
        response.thenAccept(r -> r.body().forEach(lines::add)); // runs until the stream closes
        await().atMost(TIMEOUT).until(() -> lines.contains("event:ready"));

        long id = submitAndApprove("Jira");

        String requestData = "\"requestId\":" + id + ",";
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<String> events = eventsFor(lines, requestData);
            assertThat(events).contains(
                    "request:AccessRequestSubmitted:PENDING",
                    "request:AccessRequestReviewed:APPROVED");
            assertThat(events).filteredOn(e -> e.equals("notification:PROCESSED")).hasSize(2);
        });
        response.cancel(true);
    }

    /** Turns "event:x" / "data:{...}" line pairs about one request into "x:eventType:status" strings. */
    private List<String> eventsFor(List<String> lines, String requestData) {
        List<String> out = new ArrayList<>();
        String eventName = null;
        for (String line : List.copyOf(lines)) {
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length());
            } else if (line.startsWith("data:") && line.contains(requestData)) {
                JsonNode data = json.readTree(line.substring("data:".length()));
                out.add("request".equals(eventName)
                        ? eventName + ":" + data.get("eventType").asString() + ":" + data.get("status").asString()
                        : eventName + ":" + data.get("processingStatus").asString());
            }
        }
        return out;
    }

    /** "to | subject" of every email in Mailpit that mentions request #id. */
    private List<String> mailsAbout(long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(TestcontainersConfiguration.mailpitApi() + "/messages?limit=500")).build();
        JsonNode body = json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
        List<String> out = new ArrayList<>();
        for (JsonNode message : body.get("messages")) {
            String subject = message.get("Subject").asString();
            if (subject.contains("#" + id + " ") || subject.contains("#" + id + ":")) {
                out.add(message.get("To").get(0).get("Address").asString() + " | " + subject);
            }
        }
        return out;
    }
}
