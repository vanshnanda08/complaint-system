package com.civictrack.sla;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled entry point. Nothing but scheduling and locking lives here.
 *
 * <p>{@code lockAtMostFor} bounds how long a crashed instance can keep the lock
 * -- without it, a JVM killed mid-sweep would block every later sweep forever.
 * {@code lockAtLeastFor} bounds the other direction: a sweep that finishes in
 * fifty milliseconds would otherwise let a second instance's clock, a few
 * hundred milliseconds behind, start a second run immediately.
 *
 * <p>The cron is {@code "-"} in the test profile, Spring's own disable value.
 * A background job firing during an integration test would mutate the very
 * escalation levels the test is asserting on, and the resulting flake would
 * look exactly like a concurrency bug.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlaEscalationJob {

    private final SlaSweepService sweepService;

    @Scheduled(cron = "${civictrack.sla.cron:0 */5 * * * *}")
    @SchedulerLock(name = "slaEscalation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1S")
    public void run() {
        sweepService.sweep();
    }
}
