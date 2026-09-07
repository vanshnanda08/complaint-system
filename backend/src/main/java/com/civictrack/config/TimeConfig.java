package com.civictrack.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application clock.
 *
 * <p>Nothing in the SLA, escalation or priority code calls
 * {@code Instant.now()}. It injects this bean and calls {@code clock.instant()}
 * instead, for one reason: those three things are all functions of elapsed
 * time, and a test that cannot control time can only verify them by sleeping.
 * Sleeping makes a suite slow and flaky, and it makes the interesting cases --
 * "priority rises over three days of neglect", "escalation re-arms and breaches
 * again" -- untestable at any speed.
 *
 * <p>The demo profile does not substitute for this. Three-minute SLAs make a
 * breach visible on a projector; they do not let a test assert that an issue's
 * score at t+200h is higher than at t+0 without waiting 200 hours or shrinking
 * the numbers until the test no longer resembles the system.
 *
 * <p>UTC rather than the system default: every timestamp column is
 * {@code TIMESTAMPTZ} and Hibernate is configured with {@code jdbc.time_zone:
 * UTC}, so a clock in the JVM's local zone would be the only component in the
 * stack with an opinion about zones.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
