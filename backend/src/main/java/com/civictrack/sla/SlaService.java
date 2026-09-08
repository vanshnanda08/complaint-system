package com.civictrack.sla;

import com.civictrack.category.Category;
import com.civictrack.issue.Issue;
import com.civictrack.issue.Priority;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * The deadline clock: how long an issue has, when it has run out, and what
 * happens to the deadline when it does.
 *
 * <pre>
 *   due_at             = first_reported_at + sla_hours(category, priority)
 *   effective_deadline = due_at + paused_seconds
 *   breached           = now > effective_deadline AND status.clockRunning()
 * </pre>
 *
 * <p>Time comes from the injected {@link java.time.Clock} through the caller,
 * never from {@code Instant.now()} here: every method takes {@code now} as a
 * parameter so that the class is a pure function of its inputs and the sweep,
 * the ingest path and the tests all agree on what time it is.
 */
@Service
@RequiredArgsConstructor
public class SlaService {

    private final SlaProperties props;

    /** Multipliers applied to the category's base SLA, by priority band. */
    private static double factorFor(Priority priority) {
        return switch (priority) {
            case CRITICAL -> 0.25;
            case HIGH -> 0.5;
            case MEDIUM -> 1.0;
            case LOW -> 1.5;
        };
    }

    /**
     * The full allowance for an issue at this priority.
     *
     * <p>The demo override, when set, replaces the category's hours entirely
     * rather than scaling them. Scaling would preserve the relative ordering of
     * categories, which sounds better but is worse on stage: a six-hour manhole
     * and a 168-hour signage ticket scaled by the same factor still differ by
     * a factor of 28, and one of them would not breach during the demo.
     */
    public Duration allowanceFor(Category category, Priority priority) {
        double hours = props.demoOverrideActive()
                ? props.demoOverrideHours()
                : category.getDefaultSlaHours();
        return Duration.ofSeconds(Math.round(hours * 3600.0 * factorFor(priority)));
    }

    /** The deadline an issue at this priority would have, measured from first report. */
    public Instant deadlineFor(Issue issue, Category category, Priority priority) {
        return issue.getFirstReportedAt().plus(allowanceFor(category, priority));
    }

    /**
     * Applies a recomputed priority, moving the deadline earlier if the new
     * band warrants it and never later.
     *
     * <p>A ticket's clock can only get tighter. If a priority downgrade could
     * extend a deadline, a department could buy time by arguing an issue down a
     * band -- so the rule is asymmetric by design, not by oversight. The
     * deadline is always recomputed from {@code first_reported_at} rather than
     * from now, so repeated recomputation is idempotent and an issue cannot
     * accumulate extra time by being re-scored often.
     */
    public void applyPriority(Issue issue, Category category, Priority newPriority) {
        Instant candidate = deadlineFor(issue, category, newPriority);
        if (issue.getDueAt() == null || candidate.isBefore(issue.getDueAt())) {
            issue.setDueAt(candidate);
        }
        issue.setPriority(newPriority);
    }

    /**
     * The deadline as it is actually judged: the stored one, pushed out by
     * however long the department spent waiting on citizens.
     *
     * <p>Paused time is added rather than the deadline being rewritten when the
     * clock resumes, so {@code due_at} always answers "when was this due,
     * measured from the report" and the pause credit stays visible as its own
     * number. Rewriting {@code due_at} on every pause would make the two
     * indistinguishable in the audit trail.
     */
    public Instant effectiveDeadline(Issue issue) {
        return issue.getDueAt().plusSeconds(issue.getPausedSeconds());
    }

    public boolean isBreached(Issue issue, Instant now) {
        return issue.getStatus().clockRunning() && now.isAfter(effectiveDeadline(issue));
    }

    public long breachedBySeconds(Issue issue, Instant now) {
        long seconds = Duration.between(effectiveDeadline(issue), now).toSeconds();
        return Math.max(0, seconds);
    }

    /**
     * The deadline an issue gets when it escalates to {@code level}.
     *
     * <p><b>This deviates from the specification, deliberately (DD-016).</b> The
     * blueprint says {@code due_at = now + remaining/2}. But escalation only
     * happens on breach, and at breach the remaining time is by definition zero
     * or negative -- so {@code remaining/2} is never positive, the floor always
     * wins, and the rule degenerates into a flat two hours at every rung. That
     * is not a halving at all.
     *
     * <p>What is implemented instead halves the issue's <em>full</em> allowance
     * once per rung: level 1 gets half the original SLA, level 2 a quarter,
     * level 3 an eighth, level 4 a sixteenth, each floored at
     * {@code civictrack.sla.rearm-floor}. That is the compounding pressure the
     * specification described, expressed in terms of a quantity that is
     * actually positive when the calculation runs.
     */
    public Instant rearmedDeadline(Category category, Priority priority, int level, Instant now) {
        Duration halved = allowanceFor(category, priority).dividedBy(1L << Math.max(1, level));
        Duration granted = halved.compareTo(props.rearmFloor()) < 0 ? props.rearmFloor() : halved;
        return now.plus(granted);
    }
}
