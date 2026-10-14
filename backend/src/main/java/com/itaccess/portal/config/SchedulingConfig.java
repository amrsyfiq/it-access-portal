package com.itaccess.portal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled methods (the live-update heartbeat in LiveUpdateService). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
