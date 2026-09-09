package com.civictrack.media;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled entry point. Scheduling and locking only, matching
 * {@code SlaEscalationJob}.
 *
 * <p>Nightly at 03:30 rather than midnight: the SLA sweep runs every five
 * minutes and the verification timeout job runs hourly, and stacking a
 * third job on the same boundary on a single free-tier instance with eight
 * connections is asking for pool contention at exactly the hour nobody is
 * watching.
 *
 * <p>{@code lockAtMostFor} is generous because this job is bounded by a
 * third-party API over the network, not by local work. The cron is {@code "-"}
 * in the test profile for the same reason every other job is: a background job
 * mutating rows mid-test produces a flake that reads as a concurrency bug.
 */
@Component
@RequiredArgsConstructor
public class CloudinaryOrphanJob {

    private final CloudinaryOrphanSweep sweep;

    @Scheduled(cron = "${civictrack.cloudinary.cron:0 30 3 * * *}")
    @SchedulerLock(name = "cloudinaryOrphanSweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1S")
    public void run() {
        sweep.sweep();
    }
}
