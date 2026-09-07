package com.civictrack.clustering;

/**
 * The clustering arithmetic, as pure functions.
 *
 * <p>Separated from {@link ClusteringService} so that the maths can be tested
 * without a database, a transaction or a lock. Everything here is
 * side-effect-free and deterministic.
 *
 * <p>What is deliberately <em>absent</em> is any notion of distance in metres.
 * These functions operate on weights, running sums and radii; the one place a
 * geodesic distance enters is as a parameter supplied by PostGIS. Standing
 * rule 2.
 */
public final class CentroidMath {

    private CentroidMath() {
    }

    /**
     * Inverse-variance weight for a report of the given GPS accuracy.
     *
     * <p>A report accurate to 5 m should pull the centroid far harder than one
     * accurate to 60 m, and the honest weighting for that is 1/sigma^2 rather
     * than anything tuned. The floor exists because consumer GPS does not
     * deliver sub-3-metre accuracy in a street canyon regardless of what the
     * browser claims, and an unclamped weight from a spuriously low accuracy
     * would dominate every other report in the cluster.
     *
     * <p>Units are 1/m^2, which is what makes {@link #sigmaIssue} fall out of
     * the running sum for free.
     */
    public static double weight(double accuracyM, double minAccuracyM) {
        double clamped = Math.max(accuracyM, minAccuracyM);
        return 1.0 / (clamped * clamped);
    }

    /**
     * Standard error of the cluster centroid, in metres.
     *
     * <p>Because each weight is 1/m^2, the sum of weights is too, and the
     * positional uncertainty of the weighted mean is 1/sqrt(sum). Two reports
     * at 10 m accuracy give about 7 m; twenty give about 2 m. A well-evidenced
     * cluster therefore earns a tighter effective radius automatically, with
     * nothing to tune.
     */
    public static double sigmaIssue(double sumW) {
        if (sumW <= 0) {
            throw new IllegalArgumentException("sumW must be positive, was " + sumW);
        }
        return 1.0 / Math.sqrt(sumW);
    }

    /**
     * Adaptive merge radius: R_cat + half the new report's accuracy + half the
     * cluster's own positional uncertainty.
     *
     * <p>Both correction terms widen the radius when the evidence is weak. An
     * uncertain report gets more benefit of the doubt, and so does a young
     * cluster whose centroid is not yet well determined. The new report's
     * accuracy is capped at 60 m so that one bad fix cannot inflate the radius
     * without bound; beyond that the report is barely evidence of position at
     * all, and above 150 m it is rejected outright at ingest.
     */
    public static double effectiveRadius(double categoryRadiusM,
                                         double reportAccuracyM,
                                         double sigmaIssueM,
                                         double accuracyContributionCapM) {
        return categoryRadiusM
                + 0.5 * Math.min(reportAccuracyM, accuracyContributionCapM)
                + 0.5 * sigmaIssueM;
    }

    /**
     * The running sums after folding in one report. O(1): no rescan of member
     * reports, which is the single most important implementation detail in the
     * whole algorithm.
     *
     * <p>The sums are accumulated in planar lon/lat, which is a deliberate and
     * bounded exception to standing rule 2. At Ludhiana's latitude a degree of
     * longitude is about 96 km against 111 km for latitude, so the axes are
     * anisotropic -- but over cluster extents under 150 m the induced centroid
     * error is sub-millimetre, and it never reaches a decision: every threshold
     * comparison uses a geodesic distance from PostGIS. The anisotropy affects
     * only the representative point.
     */
    public static RunningSums fold(double sumW, double sumWx, double sumWy,
                                   double weight, double lat, double lng) {
        return new RunningSums(sumW + weight,
                               sumWx + weight * lng,
                               sumWy + weight * lat);
    }

    /**
     * Conservative O(1) upper bound on what the cluster extent would become if
     * a report were merged (DD-001).
     *
     * <p>Merging moves the centroid, so every existing member's distance to it
     * changes. Recomputing those exactly is an O(n) rescan -- precisely what
     * the running sums exist to avoid. Instead, note that if the centroid moves
     * by delta then by the triangle inequality no existing member can be
     * further than {@code oldMaxMemberDist + delta} from the new centroid. The
     * arriving report sits at {@code newMemberDist}. The extent is the larger
     * of the two.
     *
     * <p>This is an upper bound, not the true value, so the cap can refuse a
     * merge that an exact recomputation would have permitted. That error runs
     * toward creating a separate flagged issue for a human to look at, which is
     * the safe direction -- but it does mean a measured rate of extent-capped
     * decisions slightly overcounts, and the evaluation should say so rather
     * than let a reader assume the bound is tight.
     */
    public static double projectedExtent(double oldMaxMemberDistM,
                                         double centroidShiftM,
                                         double newMemberDistM) {
        return Math.max(oldMaxMemberDistM + centroidShiftM, newMemberDistM);
    }

    /** The weighted mean position implied by a set of running sums. */
    public record RunningSums(double sumW, double sumWx, double sumWy) {

        public double lat() {
            return sumWy / sumW;
        }

        public double lng() {
            return sumWx / sumW;
        }
    }
}
