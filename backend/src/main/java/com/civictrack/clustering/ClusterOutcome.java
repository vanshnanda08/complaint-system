package com.civictrack.clustering;

import com.civictrack.report.ClusterDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened to an arriving report.
 *
 * <p>Returned to the citizen more or less verbatim. Telling someone their
 * report became the third piece of evidence on an existing ticket, and how far
 * it was from that ticket's centre, is the product in one response body: the
 * report visibly strengthens a case rather than vanishing into a queue.
 */
public record ClusterOutcome(
        UUID reportId,
        UUID issueId,
        String publicRef,
        ClusterDecision decision,
        int reportCount,
        int distinctReporterCount,
        Double distanceToClusterM,
        Double effectiveRadiusM,
        Double projectedExtentM,
        String status,
        Instant dueAt,
        boolean needsReview
) {
    /** True when the report joined an existing issue rather than starting one. */
    public boolean merged() {
        return decision == ClusterDecision.MERGED || decision == ClusterDecision.MERGED_LOW_CONF;
    }
}
