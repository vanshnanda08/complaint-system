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
 */
public record ClusterResultDto(
        UUID reportId,
        UUID issueId,
        String publicRef,
        ClusterDecision clusterDecision,
        int reportCount,
        int distinctReporterCount,
        Double distanceToClusterM,
        String status,
        Instant dueAt,
        String message
) {
    public static ClusterResultDto from(ClusterOutcome o) {
        return new ClusterResultDto(
                o.reportId(), o.issueId(), o.publicRef(), o.decision(),
                o.reportCount(), o.distinctReporterCount(),
                round1(o.distanceToClusterM()), o.status(), o.dueAt(),
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
