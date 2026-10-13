package com.itaccess.portal.controller;

import com.itaccess.portal.domain.RequestStatus;
import com.itaccess.portal.dto.request.CreateAccessRequest;
import com.itaccess.portal.dto.request.ReviewRequest;
import com.itaccess.portal.dto.response.AccessRequestDetailResponse;
import com.itaccess.portal.dto.response.AccessRequestResponse;
import com.itaccess.portal.dto.response.HistoryEntryResponse;
import com.itaccess.portal.dto.response.NotificationLogResponse;
import com.itaccess.portal.dto.response.PageResponse;
import com.itaccess.portal.security.CurrentUser;
import com.itaccess.portal.service.AccessRequestService;
import com.itaccess.portal.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * HTTP layer only: parse and validate input, call the service, choose the status code.
 * No SQL and no business rules here.
 */
@RestController
@RequestMapping("/api/requests")
public class AccessRequestController {

    private final AccessRequestService service;
    private final NotificationService notificationService;

    public AccessRequestController(AccessRequestService service, NotificationService notificationService) {
        this.service = service;
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<AccessRequestResponse> create(@Valid @RequestBody CreateAccessRequest body,
                                                        CurrentUser user) {
        AccessRequestResponse created = service.create(body, user);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public PageResponse<AccessRequestResponse> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, q, page, size);
    }

    @GetMapping("/{id}")
    public AccessRequestDetailResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}/status")
    public AccessRequestDetailResponse review(@PathVariable long id, @Valid @RequestBody ReviewRequest body,
                                              CurrentUser user) {
        return service.review(id, body, user);
    }

    @GetMapping("/{id}/history")
    public List<HistoryEntryResponse> history(@PathVariable long id) {
        return service.history(id);
    }

    /**
     * Notifications recorded by the RabbitMQ consumer for this request. Read-only; used to show that
     * an event was published, consumed and logged. Processing is asynchronous, so a brand-new request
     * may have none for a moment.
     */
    @GetMapping("/{id}/notifications")
    public List<NotificationLogResponse> notifications(@PathVariable long id) {
        return notificationService.forRequest(id);
    }
}
