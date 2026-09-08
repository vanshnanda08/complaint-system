package com.civictrack.publicapi.dto;

import com.civictrack.report.Report;

import java.time.Instant;
import java.util.UUID;

/**
 * One member report, with the clustering audit trail that made the cluster
 * inspector worth building.
 *
 * <p>{@code reporterId}, {@code deviceId} and {@code photoHash} are absent.
 * The first two identify a person; the third is an anti-fraud perceptual hash,
 * and publishing it would tell somebody trying to pass off a stale photo
 * exactly what they have to defeat.
 *
 * <p>{@code sequence} is the report's position in the cluster's own history,
 * assigned by the caller from creation order. It is what the side panel counts
 * with, and it is not stored: it is a property of the list, not of the row.
 */
public record PublicReportDto(
        UUID id,
        int sequence,
        Instant createdAt,
        double lat,
        double lng,
        double gpsAccuracyM,
        boolean manualPin,
        String clusterDecision,
        Double clusterDistanceM,
        Double effectiveRadiusM,
        Double projectedExtentM,
        String photoUrl,
        String description,
        String landmark
) {
    public static PublicReportDto from(Report r, int sequence) {
        return new PublicReportDto(
                r.getId(), sequence, r.getCreatedAt(),
                r.getLocation().getY(), r.getLocation().getX(),
                r.getGpsAccuracyM(), r.isManualPin(),
                r.getClusterDecision().name(),
                round1(r.getClusterDistanceM()),
                round1(r.getEffectiveRadiusM()),
                round1(r.getProjectedExtentM()),
                r.getPhotoUrl(), r.getDescription(), r.getLandmark());
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
