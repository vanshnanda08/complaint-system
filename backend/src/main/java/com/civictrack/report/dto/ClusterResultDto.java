package com.civictrack.report.dto;

import com.civictrack.clustering.ClusterOutcome;
import com.civictrack.report.ClusterDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * What the citizen is told happened to their report.
 *
 * <p>A record with a static factory rather than a mapped type (DD-008). The
 * factory is also where the honesty lives: {@code message} states plainly
 * whether the report started a ticket or strengthened one, because a citizen
 * watching their report become the third piece of evidence on an existing case
 * is the product working, and a silent merge looks identical to a report going
 * nowhere.
 *
 * <p><b>Two groups of fields, filled from two different places, and the split
 * is deliberate.</b> Everything up to {@code needsReview} comes off
 * {@link ClusterOutcome}, which the clustering transaction already computed.
 * The three display names -- priority band, ward, department -- do not exist
 * on {@code ClusterOutcome} and are supplied by the controller
 * <em>after</em> that transaction commits.
 *
 * <p>They are not added to {@code ClusterOutcome} because
 * {@code ClusteringService.ingest} runs holding {@code pg_advisory_xact_lock}
 * and row locks on the candidate issues, and every millisecond spent inside
 * that lock is a millisecond during which every other report of the same
 * defect in the same cell is queued behind it. Three joins to fetch strings
 * for a screen is a bad trade against the one piece of contention the whole
 * clustering design exists to manage.
 *
 * <p>The blueprint's own version of this contract (5) is stale and was not
 * followed: it names the reference {@code "reference"} and types the issue id
 * as an integer. The field names here are the contract.
 */
public record ClusterResultDto(
        UUID reportId,
        UUID issueId,
        String publicRef,
        ClusterDecision clusterDecision,
        int reportCount,
        int distinctReporterCount,
        Double distanceToClusterM,
        Double effectiveRadiusM,
        Double projectedExtentM,
        boolean needsReview,
        String status,
        Instant dueAt,
        String priorityBand,
        String wardName,
        String departmentName,
        String message
) {
    /**
     * Builds the response from the clustering outcome plus the three display
     * names looked up after the transaction closed. Any of the names may be
     * null -- an issue's department is nullable in the schema, and a lookup
     * that failed must degrade the label rather than the submission. The
     * citizen's report is already durably recorded by the time this runs.
     */
    public static ClusterResultDto from(ClusterOutcome o,
                                        String priorityBand,
                                        String wardName,
                                        String departmentName) {
        return new ClusterResultDto(
                o.reportId(), o.issueId(), o.publicRef(), o.decision(),
                o.reportCount(), o.distinctReporterCount(),
                round1(o.distanceToClusterM()),
                round1(o.effectiveRadiusM()),
                round1(o.projectedExtentM()),
                o.needsReview(),
                o.status(), o.dueAt(),
                priorityBand, wardName, departmentName,
                messageFor(o));
    }

    private static String messageFor(ClusterOutcome o) {
        return switch (o.decision()) {
            case MERGED, MERGED_LOW_CONF -> ordinal(o.reportCount())
                    + " report for this issue. Merged with %d existing report%s."
                            .formatted(o.reportCount() - 1, o.reportCount() == 2 ? "" : "s");
            case SPLIT_LOW_CONF -> "Recorded as a separate issue. There is a similar report nearby, "
                    + "so a supervisor will confirm whether they are the same problem.";
            case SPLIT_EXTENT_CAPPED -> "Recorded as a separate issue. The nearby report cluster "
                    + "already covers its maximum area, so a supervisor will review the boundary.";
            case NEW_ISSUE -> "Recorded as a new issue. Yours is the first report for this problem.";
            case MANUAL -> "Assigned by a supervisor.";
        };
    }

    private static String ordinal(int n) {
        String suffix = (n % 100 >= 11 && n % 100 <= 13) ? "th"
                : switch (n % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return "This is the " + n + suffix;
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
