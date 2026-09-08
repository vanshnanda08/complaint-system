package com.civictrack.issue.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything a guard needs that is not already on the issue or the actor.
 *
 * <p>Guards are pure functions of {@code (issue, actor, context)} and perform
 * no queries. That is deliberate: a guard that can query is a guard whose
 * result depends on when it runs, and the transition table would then be a set
 * of rules that cannot be evaluated without a database. The caller resolves the
 * assignee, tallies the votes and reads the clock <em>before</em> calling
 * {@link TransitionPolicy#check}, which also means the whole table is
 * exercisable by a plain unit test over all eighty-one state pairs.
 */
public record TransitionContext(
        Instant now,
        String note,
        String proofPhotoUrl,
        UUID assigneeId,
        UUID assigneeDepartmentId,
        boolean recurrenceWithinWindow,
        VerificationTally tally,
        Duration autoCloseAfter) {

    public static Builder at(Instant now) {
        return new Builder(now);
    }

    public static final class Builder {
        private final Instant now;
        private String note;
        private String proofPhotoUrl;
        private UUID assigneeId;
        private UUID assigneeDepartmentId;
        private boolean recurrenceWithinWindow;
        private VerificationTally tally = VerificationTally.none(0);
        private Duration autoCloseAfter = Duration.ofDays(7);

        private Builder(Instant now) {
            this.now = now;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public Builder proofPhotoUrl(String url) {
            this.proofPhotoUrl = url;
            return this;
        }

        public Builder assignee(UUID id, UUID departmentId) {
            this.assigneeId = id;
            this.assigneeDepartmentId = departmentId;
            return this;
        }

        public Builder recurrenceWithinWindow(boolean value) {
            this.recurrenceWithinWindow = value;
            return this;
        }

        public Builder tally(VerificationTally tally) {
            this.tally = tally;
            return this;
        }

        public Builder autoCloseAfter(Duration duration) {
            this.autoCloseAfter = duration;
            return this;
        }

        public TransitionContext build() {
            return new TransitionContext(now, note, proofPhotoUrl, assigneeId,
                    assigneeDepartmentId, recurrenceWithinWindow, tally, autoCloseAfter);
        }
    }
}
