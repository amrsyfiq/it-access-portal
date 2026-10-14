package com.itaccess.portal.service;

import com.itaccess.portal.domain.NotificationLog;
import com.itaccess.portal.domain.ProcessingStatus;
import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.mapper.AccessRequestMapper;
import com.itaccess.portal.mapper.NotificationLogMapper;
import com.itaccess.portal.messaging.event.AccessRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");

    @Mock
    private NotificationLogMapper notificationMapper;
    @Mock
    private AccessRequestMapper requestMapper;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationMapper, requestMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                "Legacy Mainframe");
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
    void alreadyProcessedEventIsSkipped() {
        AccessRequestEvent event = submitted("GitLab");
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.of(row(ProcessingStatus.PROCESSED)));

        service.handle(event);

        verify(notificationMapper, never()).insertProcessed(any());
        verify(notificationMapper, never()).markProcessed(anyString(), any());
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
    }

    @Test
    void configuredSystemFailsAndRecordsNothing() {
        when(notificationMapper.findByEventId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handle(submitted("legacy mainframe")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated notification failure");
        verify(notificationMapper, never()).insertProcessed(any());
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
    }

    private static NotificationLog row(ProcessingStatus status) {
        NotificationLog log = new NotificationLog();
        log.setProcessingStatus(status);
        return log;
    }
}
