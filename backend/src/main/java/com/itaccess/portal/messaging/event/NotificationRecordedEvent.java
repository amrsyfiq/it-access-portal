package com.itaccess.portal.messaging.event;

import com.itaccess.portal.domain.ProcessingStatus;

/**
 * In-process Spring event (not a Kafka message): NotificationService raises it when it has recorded a
 * notification, and the live-update stream pushes it to open browsers after the commit.
 */
public record NotificationRecordedEvent(long requestId, String eventId, ProcessingStatus processingStatus) {
}
