package com.civictrack.clustering;

import java.util.UUID;

/**
 * One arriving report, as the clustering engine needs it.
 *
 * <p>The photo has already been uploaded by the time this is constructed --
 * a slow Cloudinary call must never be made while holding the advisory lock.
 */
public record IngestReportCommand(
        String categoryCode,
        double lat,
        double lng,
        double accuracyM,
        boolean manualPin,
        String description,
        String addressText,
        String landmark,
        String photoUrl,
        String photoHash,
        UUID reporterId,
        String deviceId
) {
}
