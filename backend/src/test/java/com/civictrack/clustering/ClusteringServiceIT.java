package com.civictrack.clustering;

import com.civictrack.IntegrationTestBase;
import com.civictrack.support.Fixtures;
import com.civictrack.common.error.LowAccuracyException;
import com.civictrack.common.error.OutsideServiceAreaException;
import com.civictrack.common.error.UnknownCategoryException;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.report.ClusterDecision;
import com.civictrack.report.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The clustering decision rules, against real PostGIS.
 *
 * <p>These are the tests that prove the project's central claim. None of them
 * can run on H2: every one turns on a geodesic distance, a ward containment
 * test, or a spatial index.
 */
class ClusteringServiceIT extends IntegrationTestBase {

    @Autowired private ClusteringService clustering;
    @Autowired private Fixtures fixtures;
    @Autowired private IssueRepository issueRepo;
    @Autowired private ReportRepository reportRepo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearIssues() {
        // Reference data (categories, wards, departments) is left alone; it is
        // configuration and every test depends on it.
        // Fixtures.clearIssues() rather than three DELETEs inlined here.
        //
        // The inline version deleted issue_status_history, reports and issues
        // and stopped there, which leaves escalation_events, verifications and
        // notifications holding foreign keys into issues. It worked only while
        // no earlier test in the run had produced an escalation -- and whether
        // one had depends on Surefire's run order, which differs by platform.
        // Under -Dsurefire.runOrder=reversealphabetical it fails outright with
        //   violates foreign key constraint "escalation_events_issue_id_fkey"
        // The complete, FK-ordered sequence lives in one place. See DD-052.
        fixtures.clearIssues();
    }

    // ------------------------------------------------------------------
    // the three decision bands
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two potholes 12 m apart become one issue with two reports")
    void mergesWithinRadius() {
        ClusterOutcome first = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        ClusterOutcome second = ingestPothole(GeoFixtures.latOffsetM(12), GeoFixtures.LNG, 8);

        assertThat(first.decision()).isEqualTo(ClusterDecision.NEW_ISSUE);
        assertThat(second.decision()).isEqualTo(ClusterDecision.MERGED);
        assertThat(second.issueId()).isEqualTo(first.issueId());
        assertThat(second.reportCount()).isEqualTo(2);
        assertThat(issueRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("two potholes 90 m apart stay two issues")
    void separatesBeyondRadius() {
        ClusterOutcome first = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        ClusterOutcome second = ingestPothole(GeoFixtures.latOffsetM(90), GeoFixtures.LNG, 8);

        assertThat(second.decision()).isEqualTo(ClusterDecision.NEW_ISSUE);
        assertThat(second.issueId()).isNotEqualTo(first.issueId());
        assertThat(issueRepo.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a report in the low-confidence band merges and is flagged for review")
    void flagsBoundaryCaseUnderMergeFlagPolicy() {
        // POTHOLE R_cat is 25 m and carries MERGE_FLAG. With an 8 m fix on a
        // single-report cluster, R_eff lands near 33 m and the band runs to
        // about 50 m, so 42 m is inside it.
        ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        ClusterOutcome second = ingestPothole(GeoFixtures.latOffsetM(42), GeoFixtures.LNG, 8);

        assertThat(second.decision()).isEqualTo(ClusterDecision.MERGED_LOW_CONF);
        assertThat(second.needsReview()).isTrue();
        assertThat(issueRepo.count()).isEqualTo(1);

        Issue issue = issueRepo.findById(second.issueId()).orElseThrow();
        assertThat(issue.getReviewReason()).isEqualTo("LOW_CONF_MERGE");
    }

    // ------------------------------------------------------------------
    // DD-002: the band's action is per-category
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DD-002: the same geometry splits under SPLIT_FLAG and merges under MERGE_FLAG")
    void lowConfidenceBandActionIsPerCategory() {
        // OPEN_MANHOLE has R_cat 20 m and SPLIT_FLAG. A second manhole hidden
        // behind an incremented counter on an existing ticket is a hazard, not
        // a data-quality nuisance, so the safety-critical category errs the
        // other way.
        ingest("OPEN_MANHOLE", GeoFixtures.LAT, GeoFixtures.LNG, 8);
        ClusterOutcome manhole = ingest("OPEN_MANHOLE", GeoFixtures.latOffsetM(34), GeoFixtures.LNG, 8);

        assertThat(manhole.decision()).isEqualTo(ClusterDecision.SPLIT_LOW_CONF);
        assertThat(manhole.needsReview()).isTrue();
        assertThat(issueRepo.count()).isEqualTo(2);

        Issue created = issueRepo.findById(manhole.issueId()).orElseThrow();
        assertThat(created.getReviewReason()).isEqualTo("LOW_CONF_SPLIT");
    }

    // ------------------------------------------------------------------
    // hard preconditions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pothole and a garbage pile at identical coordinates are two problems")
    void differentCategoriesNeverMerge() {
        ClusterOutcome pothole = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        ClusterOutcome garbage = ingest("GARBAGE_DUMP", GeoFixtures.LAT, GeoFixtures.LNG, 8);

        assertThat(garbage.issueId()).isNotEqualTo(pothole.issueId());
        assertThat(garbage.decision()).isEqualTo(ClusterDecision.NEW_ISSUE);
        assertThat(issueRepo.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("clustering never crosses a ward boundary")
    void neverMergesAcrossWards() {
        // Straddling the lat 30.90 line between ward 1 and ward 3, 40 m apart
        // -- inside the POTHOLE merge radius, so only the ward precondition can
        // keep them separate.
        ClusterOutcome north = ingestPothole(30.90018, 75.8200, 8);
        ClusterOutcome south = ingestPothole(30.89982, 75.8200, 8);

        Integer wardNorth = wardNumberOf(north.issueId());
        Integer wardSouth = wardNumberOf(south.issueId());

        assertThat(wardNorth).isNotEqualTo(wardSouth);
        assertThat(south.issueId()).isNotEqualTo(north.issueId());
        assertThat(south.decision()).isEqualTo(ClusterDecision.NEW_ISSUE);
    }

    @Test
    @DisplayName("a report with unusable GPS accuracy is rejected, not clustered on noise")
    void rejectsLowAccuracyReports() {
        assertThatThrownBy(() -> ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 400))
                .isInstanceOf(LowAccuracyException.class);

        assertThat(issueRepo.count()).isZero();
    }

    @Test
    @DisplayName("a report outside every ward is rejected as outside the service area")
    void rejectsReportsOutsideServiceArea() {
        assertThatThrownBy(() -> ingestPothole(28.6139, 77.2090, 8))
                .isInstanceOf(OutsideServiceAreaException.class);
    }

    @Test
    @DisplayName("an unknown category is rejected")
    void rejectsUnknownCategory() {
        assertThatThrownBy(() -> ingest("NOT_A_CATEGORY", GeoFixtures.LAT, GeoFixtures.LNG, 8))
                .isInstanceOf(UnknownCategoryException.class);
    }

    // ------------------------------------------------------------------
    // weighted centroid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a 5 m report pulls the centroid four times harder than a 10 m one")
    void centroidIsWeightedByAccuracy() {
        // Two reports 20 m apart on the same meridian: one accurate to 5 m, one
        // to 10 m. Weights are 1/25 and 1/100, so the centroid should sit at
        // one fifth of the way from the accurate report to the inaccurate one.
        double southLat = GeoFixtures.LAT;
        double northLat = GeoFixtures.latOffsetM(20);

        ClusterOutcome first = ingestPothole(southLat, GeoFixtures.LNG, 5);
        ingestPothole(northLat, GeoFixtures.LNG, 10);

        Issue issue = issueRepo.findById(first.issueId()).orElseThrow();
        double centroidLat = issue.getCentroid().getY();

        double expected = (southLat * (1.0 / 25) + northLat * (1.0 / 100)) / ((1.0 / 25) + (1.0 / 100));
        assertThat(centroidLat).isCloseTo(expected, within(1e-10));

        // Sanity in metres: the centroid sits 4 m from the accurate report and
        // 16 m from the inaccurate one, not 10 m from each.
        assertThat(metresBetween(centroidLat, GeoFixtures.LNG, southLat, GeoFixtures.LNG))
                .isCloseTo(4.0, within(0.3));
    }

    @Test
    @DisplayName("distinct reporters counts people, not submissions")
    void distinctReportersCountsPeopleNotSubmissions() {
        // One device submitting three times is one reporter. This is the value
        // priority scales on, which is what stops someone buying urgency by
        // refreshing the form.
        ingest("POTHOLE", GeoFixtures.LAT, GeoFixtures.LNG, 8, "device-A");
        ingest("POTHOLE", GeoFixtures.latOffsetM(4), GeoFixtures.LNG, 8, "device-A");
        ClusterOutcome third = ingest("POTHOLE", GeoFixtures.latOffsetM(8), GeoFixtures.LNG, 8, "device-A");

        assertThat(third.reportCount()).isEqualTo(3);
        assertThat(third.distinctReporterCount()).isEqualTo(1);

        ClusterOutcome fourth = ingest("POTHOLE", GeoFixtures.latOffsetM(6), GeoFixtures.LNG, 8, "device-B");
        assertThat(fourth.distinctReporterCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // DD-001: extent cap
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DD-001: a uniformly spaced line self-limits on distance, before the cap binds")
    void uniformLineSelfLimitsOnDistanceNotExtent() {
        // Worth pinning, because it is the scenario most people picture when
        // they hear "drift chaining", and it does not actually chain.
        //
        // With reports at a fixed 20 m spacing, the centroid is the mean of
        // members so far, so after k reports it sits at (k-1)*10 m while the
        // arriving report is at k*20 m. The gap is 10k+10, which grows without
        // limit. It crosses the band ceiling at the fourth or fifth report and
        // the cluster splits on distance -- the extent cap is never consulted.
        //
        // So a uniform line is NOT evidence for or against DD-001. Generating
        // one and observing bounded extents would be measuring the distance
        // band and calling it the extent cap.
        for (int i = 0; i < 30; i++) {
            ingestPothole(GeoFixtures.LAT, GeoFixtures.lngOffsetM(i * 20.0), 8);
        }

        assertThat(issueRepo.count()).isGreaterThan(1);
        assertThat(cappedDecisionCount())
                .as("a uniform line splits on distance; the extent cap should not fire")
                .isZero();
    }

    @Test
    @DisplayName("DD-001: drift chaining is bounded, and the cap is what bounds it")
    void driftIsBounded() {
        // The real chaining scenario, which requires each report to arrive near
        // the CURRENT centroid rather than at a fixed spacing. Placing each one
        // a constant step east of wherever the centroid has moved to means
        // every merge is comfortably inside R_eff, so the distance band never
        // objects -- and yet the centroid marches east at step * H(n), the
        // harmonic series, which is unbounded.
        //
        // That is the defect in one sentence: every individual decision is
        // locally correct and the cluster still walks off down the road.
        double stepM = 20.0;
        double capM = 2.00 * 25;   // POTHOLE: max_extent_multiplier x merge_radius_m

        ClusterOutcome outcome = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        for (int i = 0; i < 25; i++) {
            Issue current = issueRepo.findById(outcome.issueId()).orElseThrow();
            double nextLng = current.getCentroid().getX()
                    + stepM / 95_545.0;   // metres per degree longitude at this latitude
            outcome = ingestPothole(GeoFixtures.LAT, nextLng, 8);
        }

        assertThat(cappedDecisionCount())
                .as("without the cap this walks indefinitely; the cap must refuse at least one merge")
                .isPositive();

        assertThat(issueRepo.count())
                .as("a refused merge starts a separate issue")
                .isGreaterThan(1);

        assertThat(issueRepo.findAll())
                .allSatisfy(issue -> assertThat(issue.getMaxMemberDistM())
                        .as("issue %s exceeded its extent cap", issue.getPublicRef())
                        .isLessThanOrEqualTo(capM));
    }

    @Test
    @DisplayName("DD-001: a capped merge is recorded as such and flagged for review")
    void extentCappedDecisionIsAudited() {
        double stepM = 20.0;
        ClusterOutcome outcome = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        for (int i = 0; i < 25; i++) {
            Issue current = issueRepo.findById(outcome.issueId()).orElseThrow();
            outcome = ingestPothole(GeoFixtures.LAT,
                    current.getCentroid().getX() + stepM / 95_545.0, 8);
        }

        assertThat(cappedDecisionCount()).isPositive();

        // The projected extent is recorded on every report that reached the
        // extent check, whether or not the merge was refused, so the ablation
        // in the evaluation can be run over the audit trail rather than by
        // re-running the pipeline.
        Integer withProjection = jdbc.queryForObject(
                "SELECT count(*) FROM reports WHERE projected_extent_m IS NOT NULL", Integer.class);
        assertThat(withProjection).isPositive();

        assertThat(issueRepo.findAll().stream().filter(Issue::isNeedsReview).count()).isPositive();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM issues WHERE review_reason = 'EXTENT_CAP'", Integer.class))
                .isPositive();
    }

    // ------------------------------------------------------------------
    // reopen on recurrence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a recurrence inside the reopen window reopens the issue rather than starting a new one")
    void reopensOnRecurrence() {
        ClusterOutcome first = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);

        // Resolve it three days ago. POTHOLE's reopen window is 14 days, so a
        // fresh report at the same spot is a recurrence, and the department
        // does not get a clean clock for it.
        jdbc.update("UPDATE issues SET status = 'RESOLVED', resolved_at = now() - interval '3 days' "
                + "WHERE id = ?", first.issueId());

        ClusterOutcome recurrence = ingestPothole(GeoFixtures.latOffsetM(6), GeoFixtures.LNG, 8);

        assertThat(recurrence.issueId()).isEqualTo(first.issueId());
        Issue issue = issueRepo.findById(first.issueId()).orElseThrow();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.REOPENED);
        assertThat(issue.getReopenCount()).isEqualTo(1);
        assertThat(issue.getEscalationLevel()).isGreaterThanOrEqualTo(1);

        // Standing rule 5: the transition left an audit row.
        Integer history = jdbc.queryForObject(
                "SELECT count(*) FROM issue_status_history WHERE issue_id = ? AND to_status = 'REOPENED'",
                Integer.class, first.issueId());
        assertThat(history).isEqualTo(1);
    }

    @Test
    @DisplayName("a recurrence outside the reopen window starts a new issue")
    void doesNotReopenOutsideWindow() {
        ClusterOutcome first = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        jdbc.update("UPDATE issues SET status = 'RESOLVED', resolved_at = now() - interval '90 days' "
                + "WHERE id = ?", first.issueId());

        ClusterOutcome later = ingestPothole(GeoFixtures.latOffsetM(6), GeoFixtures.LNG, 8);

        assertThat(later.issueId()).isNotEqualTo(first.issueId());
        assertThat(later.decision()).isEqualTo(ClusterDecision.NEW_ISSUE);
    }

    @Test
    @DisplayName("DD-005: reopen cannot push the escalation level past the terminal rung")
    void reopenEscalationIsCapped() {
        ClusterOutcome first = ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        jdbc.update("UPDATE issues SET status='RESOLVED', resolved_at=now() - interval '1 day', "
                + "escalation_level = 4 WHERE id = ?", first.issueId());

        ingestPothole(GeoFixtures.latOffsetM(5), GeoFixtures.LNG, 8);

        // Without the cap this would reach 5, where the ladder resolves to
        // nobody and the issue silently loses its owner. The database CHECK
        // would also refuse it, which is the second line of defence.
        assertThat(issueRepo.findById(first.issueId()).orElseThrow().getEscalationLevel())
                .isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // audit trail
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every report records the distance and radius its decision was made on")
    void everyReportCarriesItsClusteringAudit() {
        ingestPothole(GeoFixtures.LAT, GeoFixtures.LNG, 8);
        ClusterOutcome merged = ingestPothole(GeoFixtures.latOffsetM(12), GeoFixtures.LNG, 8);

        var reports = reportRepo.findByIssueIdOrderByCreatedAtAsc(merged.issueId());
        assertThat(reports).hasSize(2);

        // The first report started the issue, so it has no candidate distance.
        assertThat(reports.get(0).getClusterDecision()).isEqualTo(ClusterDecision.NEW_ISSUE);
        assertThat(reports.get(0).getClusterDistanceM()).isNull();

        // The second was decided against a candidate, so the distance and the
        // radius it was compared with are both recorded.
        assertThat(reports.get(1).getClusterDecision()).isEqualTo(ClusterDecision.MERGED);
        assertThat(reports.get(1).getClusterDistanceM()).isCloseTo(12.0, within(1.0));
        assertThat(reports.get(1).getEffectiveRadiusM()).isNotNull();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ClusterOutcome ingestPothole(double lat, double lng, double accuracy) {
        return ingest("POTHOLE", lat, lng, accuracy);
    }

    private ClusterOutcome ingest(String category, double lat, double lng, double accuracy) {
        return ingest(category, lat, lng, accuracy, "device-" + UUID.randomUUID());
    }

    private ClusterOutcome ingest(String category, double lat, double lng,
                                  double accuracy, String deviceId) {
        return clustering.ingest(new IngestReportCommand(
                category, lat, lng, accuracy, false,
                "test report", "Test Road", null,
                "https://example.test/photo.jpg", null, null, deviceId));
    }

    private Integer cappedDecisionCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reports WHERE cluster_decision = 'SPLIT_EXTENT_CAPPED'",
                Integer.class);
    }

    private Integer wardNumberOf(UUID issueId) {
        return jdbc.queryForObject(
                "SELECT w.ward_number FROM issues i JOIN wards w ON w.id = i.ward_id WHERE i.id = ?",
                Integer.class, issueId);
    }

    private double metresBetween(double lat1, double lng1, double lat2, double lng2) {
        return jdbc.queryForObject("""
                SELECT ST_Distance(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                                   ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """, Double.class, lng1, lat1, lng2, lat2);
    }
}
