package com.itaccess.portal.dto.response;

import com.itaccess.portal.domain.RequestHistory;
import com.itaccess.portal.domain.RequestStatus;

import java.time.OffsetDateTime;

public record HistoryEntryResponse(
        Long id,
        RequestStatus oldStatus,
        RequestStatus newStatus,
        String comment,
        OffsetDateTime changedAt,
        String changedBy) {

    public static HistoryEntryResponse from(RequestHistory h) {
        return new HistoryEntryResponse(h.getId(), h.getOldStatus(), h.getNewStatus(), h.getReviewComment(),
                h.getChangedAt(), h.getChangedBy());
    }
}
