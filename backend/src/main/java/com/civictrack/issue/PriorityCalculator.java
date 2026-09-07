package com.civictrack.issue;

import com.civictrack.category.Category;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Priority scoring.
 *
 * <pre>
 *   score = severity_weight(category)
 *         + 12 * log2(1 + distinct_reporters)
 *         + 0.15 * age_hours
 *         + 20 * escalation_level
 *         + 15 * reopen_count
 * </pre>
 *
 * <p>Two choices in that formula carry the anti-gaming argument, and both are
 * worth being able to defend out loud.
 *
 * <p><b>Distinct reporters, not raw report count.</b> One person refreshing the
 * form twenty times is one reporter. Volume only becomes a priority signal when
 * it represents distinct people.
 *
 * <p><b>Logarithmic, not linear.</b> Going from 1 to 5 reporters matters a
 * great deal; going from 40 to 45 matters very little. Without the log, one
 * organised street could monopolise the queue by out-reporting the rest of the
 * city, which would make the priority signal a measure of coordination rather
 * than of need.
 */
@Component
public class PriorityCalculator {

    private static final double REPORTER_WEIGHT = 12.0;
    private static final double AGE_WEIGHT_PER_HOUR = 0.15;
    private static final double ESCALATION_WEIGHT = 20.0;
    private static final double REOPEN_WEIGHT = 15.0;

    /**
     * The age term excludes time the SLA clock spent paused, so an issue does
     * not climb the queue while the department is waiting on citizens to vote.
     * Charging that time here while explicitly not charging it in the deadline
     * would make the two halves of the same policy disagree.
     */
    public double score(Issue issue, Category category, Instant now) {
        long elapsed = Duration.between(issue.getFirstReportedAt(), now).toSeconds();
        double ageHours = Math.max(0, elapsed - issue.getPausedSeconds()) / 3600.0;

        return category.getSeverityWeight()
                + REPORTER_WEIGHT * log2(1 + issue.getDistinctReporterCount())
                + AGE_WEIGHT_PER_HOUR * ageHours
                + ESCALATION_WEIGHT * issue.getEscalationLevel()
                + REOPEN_WEIGHT * issue.getReopenCount();
    }

    public Priority band(double score) {
        return Priority.fromScore(score);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
