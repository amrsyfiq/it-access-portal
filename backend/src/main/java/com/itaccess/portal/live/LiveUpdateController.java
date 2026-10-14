package com.itaccess.portal.live;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /api/events/stream: a long-lived text/event-stream response. The browser keeps it open and
 * receives "request" events (a request was submitted or reviewed) and "notification" events (a
 * notification was recorded), so pages can update without polling.
 */
@RestController
public class LiveUpdateController {

    private final LiveUpdateService liveUpdates;

    public LiveUpdateController(LiveUpdateService liveUpdates) {
        this.liveUpdates = liveUpdates;
    }

    @GetMapping(path = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return liveUpdates.subscribe();
    }
}
