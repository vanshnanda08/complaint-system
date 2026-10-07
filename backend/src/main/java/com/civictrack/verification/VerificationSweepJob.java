package com.civictrack.verification;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled entry point for {@link VerificationSweepService}. Nothing but
 * scheduling and locking lives here, for the reasons {@code SlaEscalationJob}
 * gives -- including why the cron is {@code "-"} under the test profile.
 *
 * <p>Its own lock name rather than sharing the SLA sweep's. The two touch
 * disjoint sets of issues -- the SLA sweep skips PENDING_VERIFICATION, which is
 * the only status this one settles from -- so serialising them would only make
 * each wait on the other.
 */
@Component
@org.springframework.context.annotation.Profile("!seed")
@RequiredArgsConstructor
public class VerificationSweepJob {

    private final VerificationSweepService sweepService;

    @Scheduled(cron = "${civictrack.verification.cron:0 */5 * * * *}")
    @SchedulerLock(name = "verificationSweep", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1S")
    public void run() {
        sweepService.sweep();
    }
}
