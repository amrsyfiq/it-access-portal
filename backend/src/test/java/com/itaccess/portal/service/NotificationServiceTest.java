package com.itaccess.portal.service;

import com.itaccess.portal.domain.NotificationLog;
import com.itaccess.portal.domain.RequestHistory;
import com.itaccess.portal.domain.ProcessingStatus;
import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.mapper.AccessRequestMapper;
import com.itaccess.portal.mapper.NotificationLogMapper;
import com.itaccess.portal.mapper.RequestHistoryMapper;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import com.itaccess.portal.messaging.event.NotificationRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mail.MailSendException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");

    @Mock
    private NotificationLogMapper notificationMapper;
    @Mock
    private AccessRequestMapper requestMapper;
    @Mock
    private RequestHistoryMapper historyMapper;
    @Mock
    private NotificationMailer mailer;
    @Mock
    private ApplicationEventPublisher events;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationMapper, requestMapper, historyMapper, mailer, events,
                Clock.fixed(NOW, ZoneOffset.UTC), "Legacy Mainframe");
    }

    private static AccessRequestEvent submitted(String system) {
        return AccessRequestEvent.submitted(7L, system, "ted.l", NOW);
    }

    @Test
    void newEventIsRecordedAsProcessed() {
        AccessRequestEvent event = submitted("GitLab");
        when(notificationMapper.findByEventId(event.eventId().toString())).thenReturn(Optional.empty());

        service.handle(event);

        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationMapper).insertProcessed(row.capture());
        assertThat(row.getValue().getEventId()).isEqualTo(event.eventId().toString());
        assertThat(row.getValue().getRequestId()).isEqualTo(7L);
        assertThat(row.getValue().getEventType()).isEqualTo("AccessRequestSubmitted");
        assertThat(row.getValue().getProcessedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void submittedEventEmailsTheAdminsAndAnnouncesTheResult() {
        AccessRequestEvent event = submitted("GitLab");
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.empty());

        service.handle(event);

        verify(mailer).send(event, null); // admins: no submitter lookup needed
        verifyNoInteractions(historyMapper);
        verify(events).publishEvent(new NotificationRecordedEvent(7L, event.eventId().toString(),
                ProcessingStatus.PROCESSED));
    }

    @Test
    void reviewedEventEmailsWhoeverSubmittedTheRequest() {
        AccessRequestEvent event = AccessRequestEvent.reviewed(7L, "SAP", RequestStatus.APPROVED, "alice.admin", NOW);
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.empty());
        when(historyMapper.findByRequestId(7L)).thenReturn(List.of(
                new RequestHistory(7L, null, RequestStatus.PENDING, null, null, "ted.l"),
                new RequestHistory(7L, RequestStatus.PENDING, RequestStatus.APPROVED, null, null, "alice.admin")));

        service.handle(event);

        verify(mailer).send(event, "ted.l");
    }

    @Test
    void mailServerFailurePropagatesSoTheTransactionRollsBackAndKafkaRetries() {
        AccessRequestEvent event = submitted("GitLab");
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.empty());
        doThrow(new MailSendException("Connection refused")).when(mailer).send(any(), any());

        assertThatThrownBy(() -> service.handle(event)).isInstanceOf(MailSendException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void alreadyProcessedEventIsSkipped() {
        AccessRequestEvent event = submitted("GitLab");
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.of(row(ProcessingStatus.PROCESSED)));

        service.handle(event);

        verify(notificationMapper, never()).insertProcessed(any());
        verify(notificationMapper, never()).markProcessed(anyString(), any());
        verifyNoInteractions(mailer);
    }

    @Test
    void previouslyFailedEventIsMarkedProcessedWhenItNowSucceeds() {
        AccessRequestEvent event = submitted("GitLab");
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.of(row(ProcessingStatus.FAILED)));

        service.handle(event);

        verify(notificationMapper).markProcessed(event.eventId().toString(), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(notificationMapper, never()).insertProcessed(any());
    }

    @Test
    void concurrentDuplicateInsertIsTreatedAsAlreadyProcessed() {
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.empty());
        when(notificationMapper.insertProcessed(any())).thenThrow(new DuplicateKeyException("ORA-00001"));

        service.handle(submitted("GitLab")); // no exception -> message is acknowledged
        verifyNoInteractions(mailer); // the consumer that recorded it sends the email: no duplicate
    }

    @Test
    void configuredSystemFailsBeforeAnyEmailIsSent() {
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.empty());

        // The row is written first, but the exception rolls the transaction back (proven against
        // Oracle in MessagingIntegrationTest), and no email goes out.
        assertThatThrownBy(() -> service.handle(submitted("legacy mainframe")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated notification failure");
        verifyNoInteractions(mailer);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void recordFailureStoresTruncatedErrorWithoutProcessedTime() {
        AccessRequestEvent event = AccessRequestEvent.reviewed(7L, "SAP", RequestStatus.APPROVED, "alice", NOW);

        service.recordFailure(event, "x".repeat(5000));

        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationMapper).recordFailure(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("AccessRequestReviewed");
        assertThat(row.getValue().getErrorMessage()).hasSize(2000);
        assertThat(row.getValue().getProcessedAt()).isNull();
        verify(events).publishEvent(new NotificationRecordedEvent(7L, event.eventId().toString(),
                ProcessingStatus.FAILED));
    }

    private static NotificationLog row(ProcessingStatus status) {
        NotificationLog log = new NotificationLog();
        log.setProcessingStatus(status);
        return log;
    }
}
