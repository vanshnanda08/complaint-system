package com.civictrack.moderation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the review queue (blueprint 3.17): the issue, why it was
 * flagged, and its member report positions for the thumbnail cluster preview.
 * Positions only -- no reporter, no device, no photo. A thumbnail needs a
 * shape, not a person.
 */
public record ReviewItemDto(
        UUID id,
        String publicRef,
        String categoryCode,
        String categoryName,
        UUID wardId,
        String wardName,
        String status,
        String priority,
        String reviewReason,
        double lat,
        double lng,
        int reportCount,
        int distinctReporterCount,
        double extentM,
        double extentCapM,
        int mergeRadiusM,
        Instant firstReportedAt,
        Instant effectiveDeadline,
        List<Point> points
) {
    public record Point(UUID reportId, double lat, double lng, double accuracyM, String clusterDecision) {
    }

    public static double cap(BigDecimal v) {
        return Math.round(v.doubleValue() * 10.0) / 10.0;
    }
}
