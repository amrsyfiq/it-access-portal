package com.itaccess.portal.dto.response;

import com.itaccess.portal.domain.NotificationLog;
import com.itaccess.portal.domain.ProcessingStatus;

import java.time.OffsetDateTime;

public record NotificationLogResponse(
        String eventId,
        String eventType,
        ProcessingStatus processingStatus,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt,
        String errorMessage) {

    public static NotificationLogResponse from(NotificationLog n) {
        return new NotificationLogResponse(n.getEventId(), n.getEventType(), n.getProcessingStatus(),
                n.getCreatedAt(), n.getProcessedAt(), n.getErrorMessage());
    }
}
