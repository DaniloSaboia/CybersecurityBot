package com.sentinel.kms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Legacy NVD configuration marker. Scheduling is enabled globally by KmsApplication;
 * individual source schedulers must guard their own enable flags.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.nvd.sync-enabled", havingValue = "true")
public class SchedulingConfig {
}
