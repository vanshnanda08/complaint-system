package com.civictrack.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the aspect that makes {@code @Timed} record anything.
 *
 * <p>Instrumenting the ingest path from day one rather than building a timing
 * harness in week 7 is deliberate: Micrometer's percentile histograms give
 * p50/p95/p99 directly, and a harness bolted on later would measure a system
 * that has already been tuned against different numbers.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
