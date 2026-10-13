package com.itaccess.portal.dto.request;

import com.itaccess.portal.domain.RequestStatus;

/** The only statuses a reviewer may set. Keeps "PENDING" out of the PATCH body by construction. */
public enum ReviewDecision {
    APPROVED,
    REJECTED;

    public RequestStatus toStatus() {
        return RequestStatus.valueOf(name());
    }
}
