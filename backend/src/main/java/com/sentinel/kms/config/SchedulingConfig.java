package com.sentinel.kms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is only wired when {@code app.nvd.sync-enabled=true}, so the
 * default (dev/demo) setup runs no background jobs at all.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.nvd.sync-enabled", havingValue = "true")
public class SchedulingConfig {
}
