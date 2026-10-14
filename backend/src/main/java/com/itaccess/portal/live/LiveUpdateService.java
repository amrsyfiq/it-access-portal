package com.itaccess.portal.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the open Server-Sent Events connections (one per browser tab) and pushes events to all of them.
 *
 * Each connection is an HTTP response that stays open; every push writes one more
 * "event: ...\ndata: {...}\n\n" block into it. Connections close after portal.live.stream-timeout and
 * the browser reconnects; a heartbeat comment keeps idle connections from being cut by proxies.
 *
 * Connections live in this JVM's memory: with several backend instances, each instance pushes to its
 * own browsers, which is why LiveUpdateListener reads Kafka in a consumer group of its own per instance.
 */
@Service
public class LiveUpdateService {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateService.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final long timeoutMillis;

    public LiveUpdateService(@Value("${portal.live.stream-timeout:5m}") Duration timeout) {
        this.timeoutMillis = timeout.toMillis();
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        try {
            // First message, so the browser knows the stream is up (and proxies flush the headers).
            emitter.send(SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            emitters.remove(emitter);
        }
        log.debug("Live-update stream opened ({} open)", emitters.size());
        return emitter;
    }

    /** Sends one named event with a JSON payload to every open stream. Never throws. */
    public void broadcast(String eventName, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                // Browser went away (tab closed, network dropped): forget this connection.
                emitters.remove(emitter);
            }
        }
    }

    @Scheduled(fixedDelayString = "${portal.live.heartbeat-interval:20s}")
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter);
            }
        }
    }

    int openStreams() {
        return emitters.size();
    }
}
