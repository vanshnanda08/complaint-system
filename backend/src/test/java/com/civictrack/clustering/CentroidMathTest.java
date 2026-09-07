package com.civictrack.clustering;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The clustering arithmetic, tested without a database. */
class CentroidMathTest {

    private static final double MIN_ACCURACY = 3.0;

    @Test
    @DisplayName("weight is inverse variance, so a 5m fix pulls 4x harder than a 10m fix")
    void weightIsInverseVariance() {
        double w5 = CentroidMath.weight(5, MIN_ACCURACY);
        double w10 = CentroidMath.weight(10, MIN_ACCURACY);

        assertThat(w5 / w10).isCloseTo(4.0, within(1e-9));
    }

    @Test
    @DisplayName("accuracy is floored, so a spuriously precise fix cannot dominate")
    void weightIsFlooredAtMinimumAccuracy() {
        // A browser reporting 0.5 m accuracy is not telling the truth. Without
        // the floor its weight would be 36x that of an honest 3 m fix and it
        // would effectively pin the centroid by itself.
        assertThat(CentroidMath.weight(0.5, MIN_ACCURACY))
                .isEqualTo(CentroidMath.weight(MIN_ACCURACY, MIN_ACCURACY));
    }

    @Test
    @DisplayName("cluster uncertainty falls as evidence accumulates")
    void sigmaFallsWithMoreReports() {
        double w = CentroidMath.weight(10, MIN_ACCURACY);

        double sigmaTwo = CentroidMath.sigmaIssue(2 * w);
        double sigmaTwenty = CentroidMath.sigmaIssue(20 * w);

        // Two 10m reports give ~7m; twenty give ~2m. This is what makes a
        // well-established cluster earn a tighter radius with no tuning.
        assertThat(sigmaTwo).isCloseTo(7.07, within(0.05));
        assertThat(sigmaTwenty).isCloseTo(2.24, within(0.05));
        assertThat(sigmaTwenty).isLessThan(sigmaTwo);
    }

    @Test
    @DisplayName("sigma rejects a non-positive weight sum rather than returning infinity")
    void sigmaRejectsNonPositiveSumW() {
        assertThatThrownBy(() -> CentroidMath.sigmaIssue(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("effective radius widens with both report and cluster uncertainty")
    void effectiveRadiusWidensWithUncertainty() {
        double tight = CentroidMath.effectiveRadius(25, 5, 2, 60);
        double loose = CentroidMath.effectiveRadius(25, 40, 8, 60);

        assertThat(tight).isCloseTo(25 + 2.5 + 1.0, within(1e-9));
        assertThat(loose).isCloseTo(25 + 20.0 + 4.0, within(1e-9));
    }

    @Test
    @DisplayName("one very inaccurate report cannot inflate the radius without bound")
    void accuracyContributionIsCapped() {
        double at60 = CentroidMath.effectiveRadius(25, 60, 0, 60);
        double at149 = CentroidMath.effectiveRadius(25, 149, 0, 60);

        assertThat(at149).isEqualTo(at60);
    }

    @Test
    @DisplayName("incremental folding equals a batch recompute over the same reports")
    void incrementalEqualsBatch() {
        // The O(1) update is only worth anything if it is exact. Fifty reports
        // folded one at a time must land where a single batch computation would.
        double[] lats = new double[50];
        double[] lngs = new double[50];
        double[] accs = new double[50];
        for (int i = 0; i < 50; i++) {
            lats[i] = GeoFixtures.latOffsetM(i * 0.4);
            lngs[i] = GeoFixtures.lngOffsetM(i * 0.3);
            accs[i] = 4 + (i % 17);
        }

        CentroidMath.RunningSums running = null;
        double batchW = 0, batchWx = 0, batchWy = 0;
        for (int i = 0; i < 50; i++) {
            double w = CentroidMath.weight(accs[i], MIN_ACCURACY);
            running = running == null
                    ? new CentroidMath.RunningSums(w, w * lngs[i], w * lats[i])
                    : CentroidMath.fold(running.sumW(), running.sumWx(), running.sumWy(),
                                        w, lats[i], lngs[i]);
            batchW += w;
            batchWx += w * lngs[i];
            batchWy += w * lats[i];
        }

        assertThat(running.lat()).isCloseTo(batchWy / batchW, within(1e-12));
        assertThat(running.lng()).isCloseTo(batchWx / batchW, within(1e-12));
    }

    @Test
    @DisplayName("projected extent takes the worse of a shifted old member and the new one")
    void projectedExtentIsTheTriangleInequalityBound() {
        // Existing furthest member at 30 m, centroid moves 5 m: that member is
        // now at most 35 m away. The new report is at 12 m. Extent is 35.
        assertThat(CentroidMath.projectedExtent(30, 5, 12)).isEqualTo(35.0);

        // But if the arriving report is itself the furthest thing from the new
        // centroid, it sets the extent.
        assertThat(CentroidMath.projectedExtent(10, 1, 48)).isEqualTo(48.0);
    }
}
