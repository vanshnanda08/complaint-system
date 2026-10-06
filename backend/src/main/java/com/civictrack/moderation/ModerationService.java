package com.civictrack.moderation;

import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueNotFoundException;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.issue.IssueStatusService;
import com.civictrack.issue.PriorityCalculator;
import com.civictrack.issue.policy.TransitionContext;
import com.civictrack.report.ClusterDecision;
import com.civictrack.report.Report;
import com.civictrack.report.ReportRepository;
import com.civictrack.sla.SlaService;
import com.civictrack.user.Actor;
import com.civictrack.stream.DashboardChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Supervisor moderation: the designed escape hatch for leader clustering's
 * known weakness (see {@code ClusteringService}). Four actions, each one
 * transaction, each one logged permanently in {@code moderation_actions}.
 *
 * <ul>
 *   <li><b>confirm</b> -- the automatic grouping was right; clear the flag.</li>
 *   <li><b>split</b> -- some of these reports are a different problem; move
 *       them to a new issue.</li>
 *   <li><b>merge</b> -- two issues are one problem; move every report of one
 *       into the other.</li>
 *   <li><b>recategorise</b> -- the citizen picked the wrong category.</li>
 * </ul>
 *
 * <p><b>No report is ever deleted.</b> Reports move between issues and keep
 * their id, photo, reporter and original clustering audit. A merged-away issue
 * is not deleted either: it is closed as REJECTED, marked {@code merged_into_id},
 * and its public reference still resolves (DD-063).
 *
 * <p><b>Locking.</b> Every issue an action touches is row-locked first, in id
 * order when there are two, so that two supervisors merging A into B and B into
 * A at the same moment queue rather than deadlock -- and so that ingest, which
 * row-locks its merge candidates, cannot fold a new report into an issue while
 * its member set is being redrawn here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModerationService {

    /**
     * States in which an issue's membership may be redrawn: the work states,
     * before anything is claimed fixed.
     *
     * <p>Not PENDING_VERIFICATION: moving a reporter out of an issue mid-vote
     * would move a voter out of the round they were voting in, and moving one
     * in would hand somebody a vote on a fix to a problem they did not report.
     * Not RESOLVED, CLOSED or REJECTED: their record is settled, and a
     * recurrence reported later reopens or creates an issue through ingest,
     * which is the path with the rules for that.
     */
    static final Set<IssueStatus> MODERATABLE = EnumSet.of(
            IssueStatus.NEW, IssueStatus.ACKNOWLEDGED, IssueStatus.ASSIGNED,
            IssueStatus.IN_PROGRESS, IssueStatus.REOPENED);

    /** Recategorising changes the owning department, so it stops at assignment. */
    static final Set<IssueStatus> RECATEGORISABLE = EnumSet.of(IssueStatus.NEW, IssueStatus.ACKNOWLEDGED);

    private final IssueRepository issues;
    private final ReportRepository reports;
    private final CategoryRepository categories;
    private final ClusterRecomputer recomputer;
    private final ModerationActionRepository actions;
    private final IssueStatusService statusService;
    private final PriorityCalculator priorityCalculator;
    private final SlaService slaService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ------------------------------------------------------------------
    // confirm
    // ------------------------------------------------------------------

    @Transactional
    public Issue confirm(UUID issueId, Actor actor, String note) {
        Instant now = clock.instant();
        Issue issue = lock(issueId);
        if (!issue.isNeedsReview()) {
            throw new ModerationRefusedException("This issue is not waiting for review.");
        }
        String reason = issue.getReviewReason();
        issue.clearReview();
        issue.setUpdatedAt(now);
        record(ModerationActionType.CONFIRM, issue.getId(), null, actor,
                Map.of("reviewReason", String.valueOf(reason)), note, now);
        return issue;
    }

    // ------------------------------------------------------------------
    // split
    // ------------------------------------------------------------------

    /** What a split would produce. Reads only; nothing is locked or written. */
    @Transactional(readOnly = true)
    public SplitPlan previewSplit(UUID issueId, Collection<UUID> reportIds) {
        Issue issue = load(issueId);
        requireModeratable(issue);
        Partition p = partition(issue, reportIds);
        return new SplitPlan(recomputer.compute(p.remaining()), recomputer.compute(p.selected()));
    }

    @Transactional
    public SplitResult split(UUID issueId, Collection<UUID> reportIds, Actor actor, String note) {
        Instant now = clock.instant();
        Issue original = lock(issueId);
        requireModeratable(original);
        Category category = category(original.getCategoryCode());
        Partition p = partition(original, reportIds);

        Map<String, Object> before = snapshot(original);
        ClusterRecomputer.ClusterGeometry createdGeo = recomputer.compute(p.selected());
        ClusterRecomputer.ClusterGeometry remainingGeo = recomputer.compute(p.remaining());

        // The new issue starts NEW and unassigned, with its SLA clock running
        // from its own earliest report -- which may well mean it is born
        // overdue. That is the truthful answer: the problem has been reported
        // for that long, and it was only hidden inside another ticket.
        Issue created = new Issue();
        created.setPublicRef(issues.nextPublicRef());
        created.setCategoryCode(original.getCategoryCode());
        created.setWardId(original.getWardId());
        created.setDepartmentId(original.getDepartmentId());
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        recomputer.apply(created, createdGeo);
        rescore(created, category, now);
        created = issues.saveAndFlush(created);

        List<Map<String, Object>> moved = move(p.selected(), created.getId());

        recomputer.apply(original, remainingGeo);
        original.clearReview();
        original.setUpdatedAt(now);
        rescore(original, category, now);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("moved", moved);
        detail.put("before", before);
        detail.put("after", Map.of("original", snapshot(original), "created", snapshot(created)));
        record(ModerationActionType.SPLIT, original.getId(), created.getId(), actor, detail, note, now);

        log.info("Issue {} split by {}: {} report(s) moved to new issue {}",
                original.getPublicRef(), actor.id(), moved.size(), created.getPublicRef());
        return new SplitResult(original, created);
    }

    // ------------------------------------------------------------------
    // merge
    // ------------------------------------------------------------------

    /** What merging {@code sourceId} into {@code targetId} would produce. Reads only. */
    @Transactional(readOnly = true)
    public ClusterRecomputer.ClusterGeometry previewMerge(UUID targetId, UUID sourceId) {
        Issue target = load(targetId);
        Issue source = load(sourceId);
        requireMergeable(target, source);
        return recomputer.compute(membersOf(target, source));
    }

    /**
     * Moves every report of {@code source} into {@code target} and closes
     * {@code source} out as REJECTED, pointing at {@code target}.
     *
     * <p>The source's transition is made <em>before</em> its reports move, so
     * that the notification it triggers reaches the people who reported it --
     * after the move, the source has no reporters to tell.
     *
     * <p>The target keeps the higher escalation level and reopen count of the
     * two. A merge folds two records of one problem together; it must not be a
     * way to shed an escalation that either of them had earned.
     */
    @Transactional
    public Issue merge(UUID targetId, UUID sourceId, Actor actor, String note) {
        Instant now = clock.instant();
        if (targetId.equals(sourceId)) {
            throw new ModerationRefusedException("An issue cannot be merged into itself.");
        }
        // Lock in id order so that A-into-B and B-into-A queue, not deadlock.
        boolean targetFirst = targetId.compareTo(sourceId) < 0;
        Issue first = lock(targetFirst ? targetId : sourceId);
        Issue second = lock(targetFirst ? sourceId : targetId);
        Issue target = targetFirst ? first : second;
        Issue source = targetFirst ? second : first;
        requireMergeable(target, source);
        Category category = category(target.getCategoryCode());

        Map<String, Object> before = Map.of("target", snapshot(target), "source", snapshot(source));
        List<Report> sourceReports = reports.findByIssueIdOrderByCreatedAtAsc(source.getId());
        ClusterRecomputer.ClusterGeometry merged = recomputer.compute(membersOf(target, source));

        // Close the source out first; see the method comment.
        source.setMergedIntoId(target.getId());
        source.clearReview();
        String reason = "Merged into " + target.getPublicRef()
                + ": the same problem was already reported there"
                + (note == null || note.isBlank() ? "" : ". " + note.strip());
        statusService.transition(source, IssueStatus.REJECTED, actor,
                TransitionContext.at(now).note(reason).build());

        List<Map<String, Object>> moved = move(sourceReports, target.getId());
        // The source keeps its last geometry -- where it was, for anybody who
        // follows the old reference -- but no longer claims any reports.
        source.setReportCount(0);
        source.setDistinctReporterCount(0);
        source.setUpdatedAt(now);

        recomputer.apply(target, merged);
        target.setEscalationLevel(Math.max(target.getEscalationLevel(), source.getEscalationLevel()));
        target.setReopenCount(Math.max(target.getReopenCount(), source.getReopenCount()));
        target.clearReview();
        target.setUpdatedAt(now);
        rescore(target, category, now);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("moved", moved);
        detail.put("before", before);
        detail.put("after", snapshot(target));
        record(ModerationActionType.MERGE, target.getId(), source.getId(), actor, detail, note, now);

        log.info("Issue {} merged into {} by {}: {} report(s) moved",
                source.getPublicRef(), target.getPublicRef(), actor.id(), moved.size());
        return target;
    }

    // ------------------------------------------------------------------
    // recategorise
    // ------------------------------------------------------------------

    /**
     * Moves the issue to another category, and with it to that category's
     * department, merge radius and extent cap.
     *
     * <p>The issue keeps its reference and its reports, and it is <em>not</em>
     * folded into any existing issue of the new category, however close: it
     * leaves the cluster it was formed in and becomes a cluster of its own in
     * the new category. If it duplicates something there, that is a merge, and
     * a merge is a decision a supervisor makes with the preview in front of
     * them rather than a side effect of fixing a label.
     *
     * <p>The deadline only ever tightens, as everywhere else: a category with a
     * longer SLA does not buy the department more time. Otherwise
     * recategorising would be the way to extend any deadline (DD-063).
     */
    @Transactional
    public Issue recategorise(UUID issueId, String categoryCode, Actor actor, String note) {
        Instant now = clock.instant();
        Issue issue = lock(issueId);
        if (!RECATEGORISABLE.contains(issue.getStatus())) {
            throw new ModerationRefusedException(
                    "Only new or acknowledged issues can be recategorised. This one is "
                    + issue.getStatus().name().toLowerCase().replace('_', ' ')
                    + ", and changing its category would move assigned work to another department.");
        }
        Category to = categories.findActive(categoryCode)
                .orElseThrow(() -> new ModerationRefusedException("There is no active category " + categoryCode + "."));
        String from = issue.getCategoryCode();
        if (to.getCode().equals(from)) {
            throw new ModerationRefusedException("This issue is already in that category.");
        }
        UUID fromDepartment = issue.getDepartmentId();

        issue.setCategoryCode(to.getCode());
        issue.setDepartmentId(to.getDepartmentId());
        issue.clearReview();
        issue.setUpdatedAt(now);
        rescore(issue, to, now);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromCategory", from);
        detail.put("toCategory", to.getCode());
        detail.put("fromDepartment", String.valueOf(fromDepartment));
        detail.put("toDepartment", String.valueOf(to.getDepartmentId()));
        record(ModerationActionType.RECATEGORISE, issue.getId(), null, actor, detail, note, now);
        return issue;
    }

    @Transactional(readOnly = true)
    public List<ModerationAction> history(UUID issueId) {
        return actions.findTouching(issueId);
    }

    // ------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------

    private record Partition(List<Report> selected, List<Report> remaining) {
    }

    private Partition partition(Issue issue, Collection<UUID> reportIds) {
        Set<UUID> wanted = new HashSet<>(reportIds);
        List<Report> members = reports.findByIssueIdOrderByCreatedAtAsc(issue.getId());
        List<Report> selected = new ArrayList<>();
        List<Report> remaining = new ArrayList<>();
        for (Report r : members) {
            (wanted.contains(r.getId()) ? selected : remaining).add(r);
        }
        if (wanted.isEmpty() || selected.isEmpty()) {
            throw new ModerationRefusedException("Select at least one report to split off.");
        }
        if (selected.size() != wanted.size()) {
            throw new ModerationRefusedException(
                    "Some of the selected reports are not on this issue. Reload and select again.");
        }
        if (remaining.isEmpty()) {
            throw new ModerationRefusedException(
                    "Leave at least one report on this issue. Splitting off every report would only "
                    + "rename it.");
        }
        return new Partition(selected, remaining);
    }

    private List<Report> membersOf(Issue target, Issue source) {
        List<Report> all = new ArrayList<>(reports.findByIssueIdOrderByCreatedAtAsc(target.getId()));
        all.addAll(reports.findByIssueIdOrderByCreatedAtAsc(source.getId()));
        return all;
    }

    /**
     * Same category and same ward. The ward is not a clustering nicety: it
     * decides which ward officer an escalation reaches, so a cross-ward merge
     * would silently move a problem out of one officer's accountability.
     */
    private void requireMergeable(Issue target, Issue source) {
        requireModeratable(target);
        requireModeratable(source);
        if (!target.getCategoryCode().equals(source.getCategoryCode())) {
            throw new ModerationRefusedException(
                    "These issues are in different categories. Recategorise one first, then merge.");
        }
        if (!target.getWardId().equals(source.getWardId())) {
            throw new ModerationRefusedException(
                    "These issues are in different wards, and a merge would move one out of its "
                    + "ward officer's accountability.");
        }
    }

    private void requireModeratable(Issue issue) {
        if (!MODERATABLE.contains(issue.getStatus())) {
            throw new ModerationRefusedException(
                    issue.getPublicRef() + " is "
                    + issue.getStatus().name().toLowerCase().replace('_', ' ')
                    + ". Reports can only be moved while an issue is open and not waiting for "
                    + "citizens to verify a fix.");
        }
    }

    /**
     * Re-homes reports, recording what each one's clustering decision was.
     *
     * <p>The report's {@code cluster_decision} becomes MANUAL, because it now
     * sits where a person put it, not where the engine did. The engine's
     * original decision is not lost: it is in the returned list, which goes
     * into the moderation log, so the evaluation can still be run against what
     * the algorithm actually decided.
     */
    private List<Map<String, Object>> move(List<Report> toMove, UUID issueId) {
        List<Map<String, Object>> moved = new ArrayList<>();
        for (Report r : toMove) {
            moved.add(Map.of(
                    "reportId", r.getId().toString(),
                    "fromIssueId", r.getIssueId().toString(),
                    "originalDecision", r.getClusterDecision().name()));
            r.setIssueId(issueId);
            r.setClusterDecision(ClusterDecision.MANUAL);
        }
        reports.saveAllAndFlush(toMove);
        return moved;
    }

    private void rescore(Issue issue, Category category, Instant now) {
        double score = priorityCalculator.score(issue, category, now);
        issue.setPriorityScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
        slaService.applyPriority(issue, category, priorityCalculator.band(score));
    }

    private static Map<String, Object> snapshot(Issue i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issueId", String.valueOf(i.getId()));
        m.put("publicRef", i.getPublicRef());
        m.put("lat", i.getCentroid().getY());
        m.put("lng", i.getCentroid().getX());
        m.put("extentM", i.getMaxMemberDistM());
        m.put("reportCount", i.getReportCount());
        m.put("distinctReporters", i.getDistinctReporterCount());
        return m;
    }

    private void record(ModerationActionType type, UUID issueId, UUID relatedId, Actor actor,
                        Map<String, Object> detail, String note, Instant now) {
        actions.save(ModerationAction.of(type, issueId, relatedId, actor.id(), actor.role().name(),
                detail, note == null || note.isBlank() ? null : note.strip(), now));
        // Delivered after commit by DashboardStream: a split can create an
        // issue that is already overdue, and a merge moves reporter counts.
        events.publishEvent(new DashboardChanged("moderation:" + type.name()));
    }

    private Issue lock(UUID id) {
        return issues.findByIdForUpdate(id).orElseThrow(() -> new IssueNotFoundException(id));
    }

    private Issue load(UUID id) {
        return issues.findById(id).orElseThrow(() -> new IssueNotFoundException(id));
    }

    private Category category(String code) {
        return categories.findById(code).orElseThrow();
    }

    /** Both sides of a split, as they would be. */
    public record SplitPlan(ClusterRecomputer.ClusterGeometry remaining,
                            ClusterRecomputer.ClusterGeometry created) {
    }

    public record SplitResult(Issue original, Issue created) {
    }
}
