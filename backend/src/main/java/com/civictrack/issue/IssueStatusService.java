package com.civictrack.issue;

import com.civictrack.issue.policy.TransitionContext;
import com.civictrack.issue.policy.TransitionPolicy;
import com.civictrack.sla.SlaProperties;
import com.civictrack.user.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The single writer of {@link Issue#setStatus}. Standing rule 5.
 *
 * <p>{@code Issue}'s status setter is package-private and this class lives in
 * the same package, so the rule is enforced by the compiler rather than by
 * review discipline. Every transition therefore lands in
 * {@code issue_status_history} by construction -- there is no code path that
 * can change a status without leaving an audit row.
 *
 * <p>Phase 2 built the mechanism: clock accounting and the history write. Phase
 * 3 adds the {@link TransitionPolicy} check and the side effects, in this same
 * method, so nothing that already called it had to change shape. That was the
 * point of building it this way round -- the alternative, a second "richer"
 * write path introduced alongside the old one, would have ended the single
 * writer guarantee on the day it was introduced.
 *
 * <p>Three things happen here in a fixed order, and the order matters:
 * the policy is consulted first, so a refused transition mutates nothing; then
 * the clock is accounted for, using the <em>old</em> status to decide whether
 * time was running; then the status changes and its side effects apply.
 */
@Service
@RequiredArgsConstructor
public class IssueStatusService {

    private final TransitionPolicy policy;
    private final IssueStatusHistoryRepository historyRepo;
    private final SlaProperties slaProps;
    private final Clock clock;

    /**
     * Moves an issue, or throws.
     *
     * @throws com.civictrack.issue.policy.IllegalTransitionException  no such edge
     * @throws com.civictrack.issue.policy.ForbiddenTransitionException this role may not
     * @throws com.civictrack.issue.policy.TransitionGuardException     a precondition failed
     */
    @Transactional
    public Issue transition(Issue issue, IssueStatus to, Actor actor, TransitionContext ctx) {
        policy.check(issue, to, actor, ctx);

        IssueStatus from = issue.getStatus();
        Instant now = ctx.now();

        applyClockAccounting(issue, from, to, now);

        issue.setStatus(to);
        issue.setUpdatedAt(now);
        applySideEffects(issue, from, to, actor, ctx, now);

        historyRepo.save(IssueStatusHistory.of(issue, from, to,
                actor.role().name(), actor.id(), ctx.note()));
        return issue;
    }

    /** Convenience for the engines, which act as SYSTEM and read the clock here. */
    @Transactional
    public Issue systemTransition(Issue issue, IssueStatus to, TransitionContext ctx) {
        return transition(issue, to, Actor.system(), ctx);
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * The SLA clock pauses while the department is waiting on citizens, so
     * PENDING_VERIFICATION time is not charged to it; the accumulated pause is
     * added back when evaluating a breach.
     *
     * <p>Read from {@code from} rather than from the issue, because the status
     * has not been written yet -- and it must not be, or this method would have
     * no way to know whether the clock was running a moment ago.
     */
    private void applyClockAccounting(Issue issue, IssueStatus from, IssueStatus to, Instant now) {
        if (from.clockRunning() && !to.clockRunning()) {
            issue.setClockPausedAt(now);
        } else if (!from.clockRunning() && to.clockRunning() && issue.getClockPausedAt() != null) {
            issue.setPausedSeconds(issue.getPausedSeconds()
                    + Duration.between(issue.getClockPausedAt(), now).toSeconds());
            issue.setClockPausedAt(null);
        }
    }

    private void applySideEffects(Issue issue, IssueStatus from, IssueStatus to,
                                  Actor actor, TransitionContext ctx, Instant now) {
        switch (to) {
            case ACKNOWLEDGED -> issue.setAcknowledgedAt(now);

            case ASSIGNED -> {
                issue.setAssignedTo(ctx.assigneeId());
                issue.setAssignedAt(now);
            }

            case PENDING_VERIFICATION -> {
                // Evidence is attached to the issue at the moment the claim is
                // made, not at the moment it is accepted, so a later resolution
                // cannot be justified by a photo uploaded afterwards.
                issue.setResolutionPhotoUrl(ctx.proofPhotoUrl());
                issue.setResolutionNote(ctx.note());
                issue.setResolvedBy(actor.id());
            }

            case RESOLVED -> {
                issue.setResolvedAt(now);
                issue.setResolvedWithoutVerification(ctx.tally().resolvedWithoutVerification());
            }

            case REOPENED -> {
                issue.setReopenCount(issue.getReopenCount() + 1);
                issue.setResolvedAt(null);
                // DD-005: every increment is capped, from the recurrence path as
                // much as from the SLA sweep. An uncapped increment climbs past
                // the terminal level, where the ladder resolves to nobody and
                // the issue silently loses its owner -- the opposite of what
                // escalation is for. Reopening also escalates by one, because a
                // fix that did not hold is evidence the current owner needs
                // help, not more time.
                issue.setEscalationLevel(Math.min(slaProps.maxEscalationLevel(),
                        issue.getEscalationLevel() + 1));
            }

            case CLOSED -> issue.setClosedAt(now);

            case REJECTED -> {
                issue.setRejectedReason(ctx.note());
                issue.setClosedAt(now);
            }

            default -> {
                // NEW and IN_PROGRESS carry no side effect of their own: the
                // work being started is recorded by the history row, and adding
                // a redundant timestamp column for it would be a second source
                // of truth for the same fact.
            }
        }
    }
}
