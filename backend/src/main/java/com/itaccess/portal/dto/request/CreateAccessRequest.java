package com.itaccess.portal.dto.request;

import com.itaccess.portal.domain.AccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/requests. Validated by @Valid in the controller before the service sees it. */
public record CreateAccessRequest(
        @NotBlank @Size(max = 100) String requesterName,
        @NotBlank @Size(max = 100) String systemName,
        @NotNull AccessLevel accessLevel,
        @NotBlank @Size(min = 10, max = 1000) String reason) {
}
