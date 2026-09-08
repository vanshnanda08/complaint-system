package com.civictrack.issue;

import com.civictrack.issue.policy.TransitionContext;
import com.civictrack.user.Actor;
import com.civictrack.user.AppUser;
import com.civictrack.user.UserRepository;
import com.civictrack.verification.VerificationProperties;
import com.civictrack.verification.VerificationQuorumService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The staff-facing verbs, each of which is one transition.
 *
 * <p>This layer exists to assemble a {@link TransitionContext} -- read the
 * clock, look up the proposed assignee, tally the votes -- and then hand off to
 * {@link IssueStatusService}. It holds no rules of its own. That matters: if
 * "who may reject an issue" were decided here, the state machine would be
 * declarative in one file and imperative in another, and the second one would
 * win whenever they disagreed.
 */
@Service
@RequiredArgsConstructor
public class IssueLifecycleService {

    private final IssueRepository issues;
    private final UserRepository users;
    private final IssueStatusService statusService;
    private final VerificationQuorumService quorum;
    private final VerificationProperties verificationProps;
    private final Clock clock;

    @Transactional
    public Issue acknowledge(UUID issueId, Actor actor, String note) {
        Issue issue = load(issueId);
        return statusService.transition(issue, IssueStatus.ACKNOWLEDGED, actor,
                context(issue).note(note).build());
    }

    @Transactional
    public Issue assign(UUID issueId, Actor actor, UUID assigneeId, String note) {
        Issue issue = load(issueId);
        AppUser assignee = users.findById(assigneeId).orElse(null);
        // A missing assignee is passed through with a null department rather
        // than throwing here, so that "no such user" and "user in the wrong
        // department" produce the same guard failure. Both are the same mistake
        // from the caller's point of view, and distinguishing them would let a
        // supervisor probe for which user ids exist.
        return statusService.transition(issue, IssueStatus.ASSIGNED, actor,
                context(issue)
                        .assignee(assigneeId, assignee == null ? null : assignee.getDepartmentId())
                        .note(note)
                        .build());
    }

    @Transactional
    public Issue start(UUID issueId, Actor actor, String note) {
        Issue issue = load(issueId);
        IssueStatus to = IssueStatus.IN_PROGRESS;
        return statusService.transition(issue, to, actor, context(issue).note(note).build());
    }

    /**
     * Staff claim a fix. The issue reaches PENDING_VERIFICATION and stops
     * there -- there is no staff path to RESOLVED at all, which is the whole
     * accountability claim of the project expressed as a missing table row.
     */
    @Transactional
    public Issue submitForVerification(UUID issueId, Actor actor, String proofPhotoUrl, String note) {
        Issue issue = load(issueId);
        return statusService.transition(issue, IssueStatus.PENDING_VERIFICATION, actor,
                context(issue).proofPhotoUrl(proofPhotoUrl).note(note).build());
    }

    @Transactional
    public Issue reject(UUID issueId, Actor actor, String reason) {
        Issue issue = load(issueId);
        return statusService.transition(issue, IssueStatus.REJECTED, actor,
                context(issue).note(reason).build());
    }

    @Transactional
    public Issue close(UUID issueId, Actor actor, String note) {
        Issue issue = load(issueId);
        return statusService.transition(issue, IssueStatus.CLOSED, actor,
                context(issue).note(note).build());
    }

    /**
     * One slice of the caller's own queue.
     *
     * <p>The department and ward scope come from the actor's token; the tab is
     * a view preference on top of that scope and can never widen it. That
     * ordering matters: {@code tab=all} means "everything in my scope", not
     * "everything".
     */
    @Transactional(readOnly = true)
    public List<IssueRepository.QueueRow> queue(Actor actor, QueueTab tab, int limit, int offset) {
        // Administrators see the whole city; everybody else sees their own
        // scope, applied in SQL rather than by filtering afterwards so that
        // paging returns a full page of things the caller may actually act on.
        UUID departmentId = actor.isAdmin() ? null : actor.departmentId();
        UUID wardId = actor.isAdmin() ? null : actor.wardId();
        UUID assignedTo = tab == QueueTab.MINE ? actor.id() : null;
        boolean unassignedOnly = tab == QueueTab.UNASSIGNED;
        return issues.findQueueRows(departmentId, wardId, assignedTo, unassignedOnly,
                                    limit, offset);
    }

    @Transactional(readOnly = true)
    public Issue get(UUID issueId) {
        return load(issueId);
    }

    private Issue load(UUID issueId) {
        return issues.findById(issueId).orElseThrow(() -> new IssueNotFoundException(issueId));
    }

    private TransitionContext.Builder context(Issue issue) {
        Instant now = clock.instant();
        return TransitionContext.at(now)
                .tally(quorum.tally(issue, now))
                .autoCloseAfter(Duration.ofDays(verificationProps.autoCloseDays()));
    }
}
