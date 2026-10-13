package com.itaccess.portal.domain;

import java.time.OffsetDateTime;

/** Row of REQUEST_HISTORY: one entry per status change, including the initial PENDING. */
public class RequestHistory {

    private Long id;
    private Long requestId;
    private RequestStatus oldStatus;
    private RequestStatus newStatus;
    private String reviewComment;
    private OffsetDateTime changedAt;
    private String changedBy;

    public RequestHistory() {
    }

    public RequestHistory(Long requestId, RequestStatus oldStatus, RequestStatus newStatus,
                          String reviewComment, OffsetDateTime changedAt, String changedBy) {
        this.requestId = requestId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.reviewComment = reviewComment;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public RequestStatus getOldStatus() {
        return oldStatus;
    }

    public void setOldStatus(RequestStatus oldStatus) {
        this.oldStatus = oldStatus;
    }

    public RequestStatus getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(RequestStatus newStatus) {
        this.newStatus = newStatus;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public OffsetDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(OffsetDateTime changedAt) {
        this.changedAt = changedAt;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }
}
