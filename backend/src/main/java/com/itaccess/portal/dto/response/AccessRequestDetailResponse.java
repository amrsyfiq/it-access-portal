package com.itaccess.portal.dto.response;

import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.domain.AccessRequest;
import com.itaccess.portal.domain.RequestStatus;

import java.time.OffsetDateTime;
import java.util.List;

/** GET /api/requests/{id}: the request plus its history, loaded with one joined query. */
public record AccessRequestDetailResponse(
        Long id,
        String requesterName,
        String systemName,
        AccessLevel accessLevel,
        String reason,
        RequestStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<HistoryEntryResponse> history) {

    public static AccessRequestDetailResponse from(AccessRequest r) {
        return new AccessRequestDetailResponse(r.getId(), r.getRequesterName(), r.getSystemName(),
                r.getAccessLevel(), r.getReason(), r.getStatus(), r.getCreatedAt(), r.getUpdatedAt(),
                r.getHistory().stream().map(HistoryEntryResponse::from).toList());
    }
}
