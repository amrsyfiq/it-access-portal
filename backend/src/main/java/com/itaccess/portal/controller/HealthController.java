package com.itaccess.portal.controller;

import com.itaccess.portal.dto.response.HealthResponse;
import com.itaccess.portal.mapper.HealthMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple public health check for the UI and the gateway. Richer diagnostics (including Hikari pool
 * metrics) are under /actuator, which is not meant to be exposed through the gateway.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthMapper healthMapper;

    public HealthController(HealthMapper healthMapper) {
        this.healthMapper = healthMapper;
    }

    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        try {
            healthMapper.ping();
            return ResponseEntity.ok(new HealthResponse("UP", "UP"));
        } catch (RuntimeException ex) {
            log.warn("Database health check failed: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse("DOWN", "DOWN"));
        }
    }
}
