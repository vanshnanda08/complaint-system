package com.civictrack.moderation.dto;

import com.civictrack.moderation.ClusterRecomputer;

import java.time.Instant;

/**
 * A cluster's shape, as a preview shows it. The running sums are left behind,
 * for the same reason the public DTO leaves them: they are internal state of
 * an update, and the server is the only authority on the centroid they imply.
 */
public record ClusterGeometryDto(double lat, double lng, double extentM, int reportCount,
                                 int distinctReporters, double positionalUncertaintyM,
                                 Instant firstReportedAt) {

    public static ClusterGeometryDto from(ClusterRecomputer.ClusterGeometry g) {
        return new ClusterGeometryDto(g.lat(), g.lng(), round1(g.extentM()), g.reportCount(),
                g.distinctReporters(), round1(g.positionalUncertaintyM()), g.firstReportedAt());
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
