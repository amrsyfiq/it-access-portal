package com.itaccess.portal.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/requests/{id}/status. */
public record ReviewRequest(
        @NotNull ReviewDecision status,
        @Size(max = 1000) String comment) {
}
