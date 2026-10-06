package com.civictrack.verification;

import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueNotFoundException;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.issue.IssueStatusService;
import com.civictrack.issue.policy.TransitionContext;
import com.civictrack.issue.policy.VerificationTally;
import com.civictrack.user.Actor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The citizen's half of the loop: casting a vote, and settling an issue once
 * the votes -- or the silence -- decide it.
 *
 * <p>Both paths end in the same {@link #settle} so that a vote and the sweep
 * cannot disagree about what a tally means. The order inside it is the order
 * of section 4.3, and it matters: rejections are checked before the quorum, so
 * a tie reopens rather than resolves.
 *
 * <p><b>Every path takes the issue's row lock first.</b> Two citizens voting
 * on the same fix at the same moment would otherwise each count the other's
 * vote as absent, each see the tally short of quorum, and neither settle it --
 * or both settle it, and the second transition fails on a status the first
 * already changed. Under the lock the votes serialise and the second voter's
 * tally includes the first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationService {

    private final IssueRepository issues;
    private final VerificationRepository verifications;
    private final VerificationQuorumService quorum;
    private final VerificationProperties props;
    private final IssueStatusService statusService;
    private final Clock clock;

    /**
     * Records one citizen's verdict on the current fix and settles the issue
     * if that vote decides it.
     *
     * <p>Eligibility -- a citizen, and one of this issue's reporters -- is
     * checked by {@code @issueGuard.canVerify} on the endpoint, before this
     * runs. It is not repeated here as a second opinion that could drift from
     * the first.
     */
    @Transactional
    public VoteResult vote(UUID issueId, Actor actor, Verdict verdict, String reason) {
        Instant now = clock.instant();
        // The row lock, before anything is read. See the class comment: a
        // plain findById here let six simultaneous voters each tally without
        // the others, and VerificationLoopIT.concurrentVotesSerialise caught it.
        Issue issue = issues.findByIdForUpdate(issueId)
                .orElseThrow(() -> new IssueNotFoundException(issueId));

        int round = issue.getVerificationRound();
        // Checked before the status, so somebody re-tapping after their own
        // vote settled the issue is told what they already said rather than
        // that voting has closed.
        verifications.findByIssueIdAndVerificationRoundAndCitizenId(issueId, round, actor.id())
                .ifPresent(existing -> {
                    throw new AlreadyVerifiedException(issueId, existing.getVerdict());
                });

        if (issue.getStatus() != IssueStatus.PENDING_VERIFICATION) {
            throw new VerificationNotOpenException(issueId, issue.getStatus());
        }

        String comment = verdict == Verdict.NOT_FIXED ? blankToNull(reason) : null;
        try {
            verifications.saveAndFlush(Verification.of(issueId, round, actor.id(), verdict, comment, now));
        } catch (DataIntegrityViolationException duplicate) {
            // The row lock above makes this unreachable for one citizen's two
            // requests; the unique constraint is the layer that holds if it
            // ever is reached. Same answer either way.
            throw new AlreadyVerifiedException(issueId, verdict);
        }

        SettleOutcome outcome = settle(issue, now);
        return new VoteResult(verdict, issue.getStatus(), outcome, quorum.tally(issue, now));
    }

    /**
     * Settles one issue whose verification window has closed. Its own
     * transaction, so one bad row cannot abort the sweep's batch and a
     * rollback undoes exactly one settlement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettleOutcome settleTimedOut(UUID issueId) {
        Instant now = clock.instant();
        Issue issue = issues.findByIdForUpdate(issueId).orElse(null);
        // Re-checked under the lock. The worklist was read without one, and a
        // vote or a second sweep may have settled this issue since.
        if (issue == null || issue.getStatus() != IssueStatus.PENDING_VERIFICATION) {
            return SettleOutcome.ALREADY_SETTLED;
        }
        return settle(issue, now);
    }

    /**
     * Closes one issue that has stayed resolved for the full window. The guard
     * on the transition, SETTLED_7_DAYS, re-checks the age itself.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoClose(UUID issueId) {
        Instant now = clock.instant();
        Issue issue = issues.findByIdForUpdate(issueId).orElse(null);
        if (issue == null || issue.getStatus() != IssueStatus.RESOLVED) {
            return false;
        }
        statusService.systemTransition(issue, IssueStatus.CLOSED,
                TransitionContext.at(now)
                        .autoCloseAfter(Duration.ofDays(props.autoCloseDays()))
                        .note("Closed automatically: no recurrence reported within "
                              + props.autoCloseDays() + " days of resolution")
                        .build());
        return true;
    }

    /**
     * Applies section 4.3 to the current tally. The caller holds the row lock.
     */
    private SettleOutcome settle(Issue issue, Instant now) {
        VerificationTally tally = quorum.tally(issue, now);

        if (tally.rejectionsPrevail()) {
            statusService.systemTransition(issue, IssueStatus.REOPENED, TransitionContext.at(now)
                    .tally(tally)
                    .note(tally.rejections() == 1
                            ? "A citizen who reported this said it is not fixed"
                            : tally.rejections() + " citizens who reported this said it is not fixed")
                    .build());
            log.info("Issue {} reopened by citizens ({} not fixed, {} fixed)",
                    issue.getId(), tally.rejections(), tally.confirmations());
            return SettleOutcome.REOPENED;
        }

        if (tally.quorumMet()) {
            String note = tally.resolvedWithoutVerification()
                    ? "Resolved after " + props.timeoutHours()
                      + " hours with no citizen response (resolved without verification)"
                    : tally.confirmations() >= tally.required()
                            ? "Confirmed fixed by " + tally.confirmations() + " of the people who reported it"
                            : "Resolved at the end of the verification window, "
                              + tally.confirmations() + " fixed to " + tally.rejections() + " not fixed";
            statusService.systemTransition(issue, IssueStatus.RESOLVED,
                    TransitionContext.at(now).tally(tally).note(note).build());
            log.info("Issue {} resolved ({} fixed, {} not fixed, timeout {})", issue.getId(),
                    tally.confirmations(), tally.rejections(), tally.timeoutElapsed());
            return tally.resolvedWithoutVerification()
                    ? SettleOutcome.RESOLVED_WITHOUT_VERIFICATION
                    : SettleOutcome.RESOLVED;
        }

        return SettleOutcome.STILL_OPEN;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    public enum SettleOutcome {
        STILL_OPEN, RESOLVED, RESOLVED_WITHOUT_VERIFICATION, REOPENED, ALREADY_SETTLED
    }

    public record VoteResult(Verdict verdict, IssueStatus status, SettleOutcome outcome,
                             VerificationTally tally) {
    }
}
