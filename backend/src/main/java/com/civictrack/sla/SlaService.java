package com.civictrack.sla;

import com.civictrack.category.Category;
import com.civictrack.issue.Issue;
import com.civictrack.issue.Priority;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Deadline computation and the tightening rule.
 *
 * <p>Phase 2 scope: the deadline itself. The breach sweep, the escalation
 * ladder and the pause-aware breach evaluation arrive in phase 4.
 */
@Service
public class SlaService {

    /** Multipliers applied to the category's base SLA, by priority band. */
    private static double factorFor(Priority priority) {
        return switch (priority) {
            case CRITICAL -> 0.25;
            case HIGH -> 0.5;
            case MEDIUM -> 1.0;
            case LOW -> 1.5;
        };
    }

    /** The deadline an issue at this priority would have, measured from first report. */
    public Instant deadlineFor(Issue issue, Category category, Priority priority) {
        long seconds = Math.round(category.getDefaultSlaHours() * 3600.0 * factorFor(priority));
        return issue.getFirstReportedAt().plus(Duration.ofSeconds(seconds));
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
}
