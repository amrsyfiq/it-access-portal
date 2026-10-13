package com.itaccess.portal.dto.response;

import com.itaccess.portal.domain.AccessLevel;
import com.itaccess.portal.domain.AccessRequest;
import com.itaccess.portal.domain.RequestStatus;

import java.time.OffsetDateTime;

/** List item / summary view of a request. */
public record AccessRequestResponse(
        Long id,
        String requesterName,
        String systemName,
        AccessLevel accessLevel,
        String reason,
        RequestStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AccessRequestResponse from(AccessRequest r) {
        return new AccessRequestResponse(r.getId(), r.getRequesterName(), r.getSystemName(), r.getAccessLevel(),
                r.getReason(), r.getStatus(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
