package com.civictrack.moderation;

import com.civictrack.clustering.CentroidMath;
import com.civictrack.clustering.ClusterProperties;
import com.civictrack.common.geo.GeoFactory;
import com.civictrack.issue.Issue;
import com.civictrack.report.Report;
import com.civictrack.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Full recomputation of a cluster's geometry from its member reports.
 *
 * <p>The phase 7 brief is explicit that split and merge recompute from the
 * members rather than update incrementally, and the reason is that there is no
 * incremental update to make. Ingest folds one report into running sums; a
 * split removes an arbitrary subset, and a merge combines two clusters whose
 * stored {@code max_member_dist_m} values are both upper bounds (DD-001) about
 * two different centroids. Neither bound says anything about the new centroid.
 * So every number is rebuilt from the reports: the sums, the centroid, the
 * exact extent, both counts, and the reporting window.
 *
 * <p>The weights come from {@link CentroidMath#weight}, the one function ingest
 * uses, so a cluster recomputed here has exactly the centroid it would have had
 * if the same reports had arrived through ingest -- order aside, which the
 * weighted mean does not depend on. The extent comes from PostGIS (standing
 * rule 2).
 */
@Service
@RequiredArgsConstructor
public class ClusterRecomputer {

    private final ReportRepository reports;
    private final ClusterProperties props;

    /** What a cluster made of exactly these reports would look like. Persists nothing. */
    public ClusterGeometry compute(List<Report> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("A cluster needs at least one report");
        }
        double sumW = 0;
        double sumWx = 0;
        double sumWy = 0;
        for (Report r : members) {
            CentroidMath.RunningSums next = CentroidMath.fold(sumW, sumWx, sumWy,
                    CentroidMath.weight(r.getGpsAccuracyM(), props.minAccuracyM()),
                    r.getLocation().getY(), r.getLocation().getX());
            sumW = next.sumW();
            sumWx = next.sumWx();
            sumWy = next.sumWy();
        }
        double lat = sumWy / sumW;
        double lng = sumWx / sumW;

        double extent = reports.maxDistanceFrom(members.stream().map(Report::getId).toList(), lat, lng);

        // Same identity rule as IssueRepository.countDistinctReporters: an
        // account by its id, an anonymous phone by its device.
        int distinct = (int) members.stream()
                .map(r -> r.getReporterId() != null ? r.getReporterId().toString() : r.getDeviceId())
                .filter(Objects::nonNull)
                .distinct()
                .count();

        Instant first = members.stream().map(Report::getCreatedAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant last = members.stream().map(Report::getCreatedAt).max(Comparator.naturalOrder()).orElseThrow();

        return new ClusterGeometry(sumW, sumWx, sumWy, lat, lng, extent,
                members.size(), distinct, first, last, CentroidMath.sigmaIssue(sumW));
    }

    /**
     * Writes a computed geometry onto an issue.
     *
     * <p>{@code first_reported_at} only ever moves earlier. It is what the SLA
     * clock runs from, and a split that happened to move the oldest report out
     * of an issue must not hand the department a later start and so a later
     * deadline -- the same asymmetry {@code SlaService.applyPriority} applies to
     * priority. A merge can move it earlier, and should: the problem has been
     * known about since the first of either issue's reports.
     */
    public void apply(Issue issue, ClusterGeometry g) {
        issue.setSumW(g.sumW());
        issue.setSumWx(g.sumWx());
        issue.setSumWy(g.sumWy());
        issue.setCentroid(GeoFactory.point(g.lat(), g.lng()));
        issue.setMaxMemberDistM(g.extentM());
        issue.setReportCount(g.reportCount());
        issue.setDistinctReporterCount(g.distinctReporters());
        if (issue.getFirstReportedAt() == null || g.firstReportedAt().isBefore(issue.getFirstReportedAt())) {
            issue.setFirstReportedAt(g.firstReportedAt());
        }
        issue.setLastReportedAt(g.lastReportedAt());
    }

    /** A cluster's derived numbers, as a value. */
    public record ClusterGeometry(
            double sumW,
            double sumWx,
            double sumWy,
            double lat,
            double lng,
            double extentM,
            int reportCount,
            int distinctReporters,
            Instant firstReportedAt,
            Instant lastReportedAt,
            double positionalUncertaintyM
    ) {
    }
}
