package com.civictrack.clustering;

import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.category.LowConfAction;
import com.civictrack.common.error.LowAccuracyException;
import com.civictrack.common.error.OutsideServiceAreaException;
import com.civictrack.common.error.UnknownCategoryException;
import com.civictrack.common.geo.GeoFactory;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.issue.IssueStatusService;
import com.civictrack.issue.Priority;
import com.civictrack.issue.PriorityCalculator;
import com.civictrack.report.ClusterDecision;
import com.civictrack.report.Report;
import com.civictrack.report.ReportRepository;
import com.civictrack.sla.SlaService;
import com.civictrack.ward.WardRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Incremental online geo-clustering: decide, inside the request cycle, whether
 * an arriving report belongs to an existing issue or starts a new one.
 *
 * <p><b>Why not DBSCAN.</b> DBSCAN is batch density clustering over a static
 * point set. Adding one point can restructure clusters globally, and cluster
 * identity is not stable between runs. But an issue id here is a public ticket
 * number that a citizen has in a message and a crew has in a work queue -- it
 * cannot be reassigned by a re-clustering pass. Reports also arrive as an
 * unbounded stream, so a batch algorithm would have to re-run on every insert.
 * What this needs instead is leader clustering: single-pass, assignment final
 * unless a human intervenes, distance measured against a maintained cluster
 * representative rather than against every member.
 *
 * <p><b>The known limitation, stated rather than hidden.</b> Single-pass
 * assignment is order-dependent, and it cannot merge two clusters that later
 * turn out to be one. That is exactly why the {@code needs_review} flag and the
 * supervisor split/merge tool exist: they are the designed escape hatch for the
 * algorithm's known weakness, not an afterthought bolted on later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClusteringService {

    private final IssueRepository issueRepo;
    private final ReportRepository reportRepo;
    private final CategoryRepository categoryRepo;
    private final WardRepository wardRepo;
    private final IssueStatusService statusService;
    private final PriorityCalculator priorityCalculator;
    private final SlaService slaService;
    private final ClusterProperties props;

    /**
     * Ingests one report and returns what happened to it.
     *
     * <p>The whole method is one transaction, and it has to be: the advisory
     * lock is transaction-scoped, the candidate row locks are transaction
     * scoped, and the issue update plus report insert must commit together or
     * not at all. A partially applied merge would leave a centroid that no set
     * of reports produces.
     */
    @Timed(value = "civictrack.ingest", description = "Report ingest including clustering decision")
    @Transactional
    public ClusterOutcome ingest(IngestReportCommand cmd) {

        // ---- preconditions, before any lock is taken --------------------

        if (cmd.accuracyM() > props.maxAccuracyM()) {
            // Beyond this the fix is barely evidence of position. Rejecting is
            // kinder than silently clustering on noise: the client responds by
            // asking the user to drag a pin, which produces better data than
            // any amount of server-side cleverness.
            throw new LowAccuracyException(cmd.accuracyM(), props.maxAccuracyM());
        }

        Category category = categoryRepo.findActive(cmd.categoryCode())
                .orElseThrow(() -> new UnknownCategoryException(cmd.categoryCode()));

        UUID wardId = wardRepo.findIdContaining(cmd.lat(), cmd.lng())
                .orElseThrow(() -> new OutsideServiceAreaException(cmd.lat(), cmd.lng()));

        // ---- layer 1: serialise everyone reporting the same thing here ---

        issueRepo.acquireCellLock(category.getCode(), cmd.lat(), cmd.lng(), props.cellGridDegrees());

        double weight = CentroidMath.weight(cmd.accuracyM(), props.minAccuracyM());
        double searchRadius = props.lowConfidenceFactor() * category.getMergeRadiusM()
                + props.searchRadiusPaddingM();

        // ---- layer 2: find and row-lock the nearest candidates -----------

        List<UUID> lockedIds = issueRepo.lockMergeCandidateIds(
                category.getCode(), wardId, cmd.lat(), cmd.lng(),
                searchRadius, category.getReopenWindowDays());

        if (lockedIds.isEmpty()) {
            return createNewIssue(cmd, category, wardId, weight,
                    ClusterDecision.NEW_ISSUE, null, null, null, null);
        }

        // ---- DD-003: re-read under the locks, and decide only on that ----
        //
        // PostgreSQL evaluates ORDER BY and LIMIT before it acquires row locks
        // under FOR UPDATE. Between the moment the query above computed its
        // ordering and the moment its locks were granted, a concurrent
        // transaction could have committed a centroid update to one of these
        // very rows -- the advisory lock does not prevent that, because it only
        // serialises the ~200 m cell the new report falls in, and a centroid
        // can be moved by a report arriving in an adjacent cell.
        //
        // So every value the ordering query computed is potentially stale, and
        // it deliberately returned ids only. Now that the locks are held, no
        // further update can intervene: this re-read is the first point at
        // which distance, sum_w and centroid are trustworthy. The band decision
        // below uses these values and nothing else.
        List<IssueRepository.CandidateRow> candidates =
                issueRepo.reReadLockedCandidates(lockedIds, cmd.lat(), cmd.lng());

        if (candidates.isEmpty()) {
            return createNewIssue(cmd, category, wardId, weight,
                    ClusterDecision.NEW_ISSUE, null, null, null, null);
        }

        // Re-sorted by the re-read distance, so "best" reflects post-lock
        // truth. The nearest candidate before locking need not be the nearest
        // after it.
        IssueRepository.CandidateRow best = candidates.get(0);

        double sigmaIssue = CentroidMath.sigmaIssue(best.getSumW());
        double effectiveRadius = CentroidMath.effectiveRadius(
                category.getMergeRadiusM(), cmd.accuracyM(), sigmaIssue,
                props.accuracyContributionCapM());
        double distance = best.getDistanceM();

        // ---- the three decision bands ------------------------------------

        if (distance > props.lowConfidenceFactor() * effectiveRadius) {
            return createNewIssue(cmd, category, wardId, weight,
                    ClusterDecision.NEW_ISSUE, distance, effectiveRadius, null, null);
        }

        boolean inLowConfidenceBand = distance > effectiveRadius;

        // DD-002: the band's action is a per-category decision. Merging
        // optimistically is right where a wrong merge costs one click to split.
        // It is wrong where concealing a duplicate is itself the hazard, which
        // is why safety-critical categories carry SPLIT_FLAG.
        if (inLowConfidenceBand && category.getLowConfAction() == LowConfAction.SPLIT_FLAG) {
            return createNewIssue(cmd, category, wardId, weight,
                    ClusterDecision.SPLIT_LOW_CONF, distance, effectiveRadius, null,
                    "LOW_CONF_SPLIT");
        }

        // ---- DD-001: would this merge push the cluster past its cap? ------

        CentroidMath.RunningSums projected = CentroidMath.fold(
                best.getSumW(), best.getSumWx(), best.getSumWy(), weight, cmd.lat(), cmd.lng());

        IssueRepository.ExtentProjectionRow geo = issueRepo.projectExtent(
                best.getCentroidLat(), best.getCentroidLng(),
                projected.lat(), projected.lng(),
                cmd.lat(), cmd.lng());

        double projectedExtent = CentroidMath.projectedExtent(
                best.getMaxMemberDistM(), geo.getCentroidShiftM(), geo.getNewMemberDistM());

        if (projectedExtent > category.extentCapM()) {
            // Every individual merge along a linear defect can be locally valid
            // while the cluster as a whole walks arbitrarily far from where it
            // started. Refusing here is what stops the chain. The projected
            // extent is recorded on the report either way, so the ablation can
            // be run over the audit trail.
            log.debug("Extent cap refused merge into {}: projected {}m exceeds cap {}m",
                    best.getId(), projectedExtent, category.extentCapM());
            return createNewIssue(cmd, category, wardId, weight,
                    ClusterDecision.SPLIT_EXTENT_CAPPED, distance, effectiveRadius,
                    projectedExtent, "EXTENT_CAP");
        }

        ClusterDecision decision = inLowConfidenceBand
                ? ClusterDecision.MERGED_LOW_CONF
                : ClusterDecision.MERGED;

        return mergeInto(best, cmd, category, weight, decision,
                distance, effectiveRadius, projected, projectedExtent, geo.getNewMemberDistM());
    }

    // ------------------------------------------------------------------
    // creation
    // ------------------------------------------------------------------

    private ClusterOutcome createNewIssue(IngestReportCommand cmd, Category category, UUID wardId,
                                          double weight, ClusterDecision decision,
                                          Double distance, Double effectiveRadius,
                                          Double projectedExtent, String reviewReason) {
        Instant now = Instant.now();

        Issue issue = new Issue();
        issue.setPublicRef(issueRepo.nextPublicRef());
        issue.setCategoryCode(category.getCode());
        issue.setWardId(wardId);
        issue.setDepartmentId(category.getDepartmentId());
        issue.setCentroid(GeoFactory.point(cmd.lat(), cmd.lng()));
        issue.setSumW(weight);
        issue.setSumWx(weight * cmd.lng());
        issue.setSumWy(weight * cmd.lat());
        // A single-report cluster has zero extent by definition: the centroid
        // is the report.
        issue.setMaxMemberDistM(0.0);
        issue.setReportCount(1);
        issue.setDistinctReporterCount(1);
        issue.setFirstReportedAt(now);
        issue.setLastReportedAt(now);
        issue.setCreatedAt(now);
        issue.setUpdatedAt(now);
        // Provisional, replaced by applyPriority once the score is known. The
        // column is NOT NULL, so it cannot be left unset until then.
        issue.setDueAt(now.plusSeconds(category.getDefaultSlaHours() * 3600L));

        if (reviewReason != null) {
            issue.flagForReview(reviewReason);
        }

        Issue saved = issueRepo.save(issue);

        Report report = buildReport(cmd, category, saved.getId(), decision,
                distance, effectiveRadius, projectedExtent);
        Report savedReport = reportRepo.save(report);

        rescore(saved, category, now);

        return outcome(savedReport, saved, decision, distance, effectiveRadius, projectedExtent);
    }

    // ------------------------------------------------------------------
    // merge
    // ------------------------------------------------------------------

    private ClusterOutcome mergeInto(IssueRepository.CandidateRow best, IngestReportCommand cmd,
                                     Category category, double weight, ClusterDecision decision,
                                     double distance, double effectiveRadius,
                                     CentroidMath.RunningSums projected,
                                     double projectedExtent, double newMemberDistM) {
        Instant now = Instant.now();

        // Safe to load: the row is already locked by lockMergeCandidateIds, so
        // this is served from the persistence context or a locked row, and no
        // concurrent writer can be between us and it.
        Issue issue = issueRepo.findById(best.getId()).orElseThrow();

        // O(1) incremental centroid. No rescan of member reports.
        issue.setSumW(projected.sumW());
        issue.setSumWx(projected.sumWx());
        issue.setSumWy(projected.sumWy());
        issue.setCentroid(GeoFactory.point(projected.lat(), projected.lng()));

        // The extent bound computed for the cap decision is also the new
        // stored extent, so the cap and the record agree by construction.
        issue.setMaxMemberDistM(projectedExtent);
        issue.setReportCount(issue.getReportCount() + 1);
        issue.setLastReportedAt(now);
        issue.setUpdatedAt(now);

        if (decision == ClusterDecision.MERGED_LOW_CONF) {
            issue.flagForReview("LOW_CONF_MERGE");
        }

        Report report = buildReport(cmd, category, issue.getId(), decision,
                distance, effectiveRadius, projectedExtent);
        // Flushed, not just saved: countDistinctReporters below is native and
        // reads committed table state, so an unflushed insert is invisible to it.
        Report savedReport = reportRepo.saveAndFlush(report);

        // Reopen-on-recurrence. A department cannot close a ticket, have the
        // problem recur inside the window, and be handed a fresh clock for it.
        if (issue.getStatus() == IssueStatus.RESOLVED) {
            reopen(issue, category);
        }

        // Must run after the report insert, since it counts member reports.
        issue.setDistinctReporterCount(issueRepo.countDistinctReporters(issue.getId()));

        rescore(issue, category, now);

        return outcome(savedReport, issue, decision, distance, effectiveRadius, projectedExtent);
    }

    private void reopen(Issue issue, Category category) {
        statusService.transition(issue, IssueStatus.REOPENED, IssueStatusService.ACTOR_SYSTEM,
                null, "Recurrence reported within the reopen window");
        issue.setReopenCount(issue.getReopenCount() + 1);
        issue.setResolvedAt(null);

        // DD-005: every increment is capped, from this path as much as from the
        // SLA sweep. An uncapped increment would climb past the terminal level,
        // where the ladder resolves to nobody and the issue silently loses its
        // owner -- the opposite of what escalation is for.
        issue.setEscalationLevel(Math.min(4, Math.max(1, issue.getEscalationLevel() + 1)));
    }

    // ------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------

    private void rescore(Issue issue, Category category, Instant now) {
        double score = priorityCalculator.score(issue, category, now);
        issue.setPriorityScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
        slaService.applyPriority(issue, category, priorityCalculator.band(score));
    }

    private Report buildReport(IngestReportCommand cmd, Category category, UUID issueId,
                               ClusterDecision decision, Double distance,
                               Double effectiveRadius, Double projectedExtent) {
        Report report = new Report();
        report.setIssueId(issueId);
        report.setReporterId(cmd.reporterId());
        report.setDeviceId(cmd.deviceId());
        report.setCategoryCode(category.getCode());
        report.setLocation(GeoFactory.point(cmd.lat(), cmd.lng()));
        report.setGpsAccuracyM(cmd.accuracyM());
        report.setManualPin(cmd.manualPin());
        report.setDescription(cmd.description());
        report.setAddressText(cmd.addressText());
        report.setLandmark(cmd.landmark());
        report.setPhotoUrl(cmd.photoUrl());
        report.setPhotoHash(cmd.photoHash());
        report.setClusterDecision(decision);
        report.setClusterDistanceM(distance);
        report.setEffectiveRadiusM(effectiveRadius);
        report.setProjectedExtentM(projectedExtent);
        return report;
    }

    private ClusterOutcome outcome(Report report, Issue issue, ClusterDecision decision,
                                   Double distance, Double effectiveRadius, Double projectedExtent) {
        return new ClusterOutcome(
                report.getId(), issue.getId(), issue.getPublicRef(), decision,
                issue.getReportCount(), issue.getDistinctReporterCount(),
                distance, effectiveRadius, projectedExtent,
                issue.getStatus().name(), issue.getDueAt(), issue.isNeedsReview());
    }
}
