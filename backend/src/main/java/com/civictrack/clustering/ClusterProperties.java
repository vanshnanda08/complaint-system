package com.civictrack.clustering;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clustering knobs that are genuinely global rather than per-category.
 *
 * <p>Standing rule 1 puts every tunable number in the categories table, and
 * that rule holds for anything that varies by defect type -- radius, SLA,
 * extent multiplier, band policy. What remains here are properties of the
 * sensing apparatus and of the concurrency strategy, which do not vary by
 * whether the defect is a pothole or a manhole.
 */
@ConfigurationProperties(prefix = "civictrack.clustering")
public record ClusterProperties(

        /** Floor on GPS accuracy. Consumer GPS does not beat this in a street. */
        double minAccuracyM,

        /** Above this, a report is rejected and the user places a pin by hand. */
        double maxAccuracyM,

        /** Cap on how much one report's accuracy may widen the effective radius. */
        double accuracyContributionCapM,

        /** Upper edge of the low-confidence band, as a multiple of R_eff. */
        double lowConfidenceFactor,

        /** Advisory-lock cell size in degrees. 0.002 is roughly 200 m x 220 m. */
        double cellGridDegrees,

        /** Additive slack on the candidate search radius, in metres. */
        double searchRadiusPaddingM
) {
}
