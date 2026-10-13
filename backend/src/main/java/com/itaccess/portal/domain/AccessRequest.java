package com.itaccess.portal.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Row of ACCESS_REQUEST. MyBatis populates it through the no-arg constructor and setters
 * (columns are matched by name with map-underscore-to-camel-case).
 * This class never leaves the service layer; controllers return DTOs.
 */
public class AccessRequest {

    private Long id;
    private String requesterName;
    private String systemName;
    private AccessLevel accessLevel;
    private String reason;
    private RequestStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** Only filled by the joined "find with history" query. */
    private List<RequestHistory> history = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public void setRequesterName(String requesterName) {
        this.requesterName = requesterName;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<RequestHistory> getHistory() {
        return history;
    }

    public void setHistory(List<RequestHistory> history) {
        this.history = history;
    }
}
