package com.civictrack.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Knobs for the deadline clock and the escalation sweep.
 *
 * <p>Standing rule 1 puts per-defect numbers in the categories table, and the
 * SLA hours themselves live there. What is here is the behaviour of the
 * <em>engine</em>: how often it runs, how large a batch it takes, how far the
 * ladder goes, and the floor below which a re-armed deadline is not allowed to
 * fall. None of those vary by whether the defect is a pothole or a manhole.
 */
@ConfigurationProperties(prefix = "civictrack.sla")
public record SlaProperties(

        /** Spring cron for the sweep. "-" disables the schedule entirely. */
        @DefaultValue("0 */5 * * * *") String cron,

        /** Terminal rung of the escalation ladder (DD-005). */
        @DefaultValue("4") int maxEscalationLevel,

        /** Issues locked per batch by the sweep. */
        @DefaultValue("100") int batchSize,

        /**
         * Lower bound on a re-armed deadline. Without it, escalation halving
         * would eventually produce deadlines minutes away, which no crew can
         * meet and which therefore stop carrying information.
         */
        @DefaultValue("PT2H") Duration rearmFloor,

        /**
         * Demo profile only: when positive, overrides every category's SLA with
         * this many hours so that a breach happens while an audience is
         * watching. Zero in every other profile, and asserted to be zero by
         * SlaServiceTest so it cannot be left on by accident.
         */
        @DefaultValue("0") double demoOverrideHours
) {
    public boolean demoOverrideActive() {
        return demoOverrideHours > 0;
    }
}
