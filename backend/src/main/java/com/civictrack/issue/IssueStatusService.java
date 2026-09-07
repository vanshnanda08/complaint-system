package com.civictrack.issue;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The single writer of {@link Issue#setStatus}. Standing rule 5.
 *
 * <p>{@code Issue}'s status setter is package-private and this class lives in
 * the same package, so the rule is enforced by the compiler rather than by
 * review discipline. Every transition therefore lands in
 * {@code issue_status_history} by construction -- there is no code path that
 * can change a status without leaving an audit row.
 *
 * <p>Phase 2 scope. This is the mechanism only: the clock accounting and the
 * history write. The declarative {@code TransitionPolicy} -- who may perform
 * which transition, and under what guards -- arrives in phase 3 and plugs into
 * this same method, so callers do not change. Until then the only caller is the
 * clustering engine's reopen-on-recurrence path, which is a SYSTEM action.
 */
@Service
@RequiredArgsConstructor
public class IssueStatusService {

    public static final String ACTOR_SYSTEM = "SYSTEM";

    private final IssueStatusHistoryRepository historyRepo;

    @Transactional
    public void transition(Issue issue, IssueStatus to, String actorRole, UUID actorId, String note) {
        IssueStatus from = issue.getStatus();
        if (from == to) {
            return;
        }

        // Clock accounting. The SLA clock pauses while the department is
        // waiting on citizens, so PENDING_VERIFICATION time is not charged to
        // it; the accumulated pause is added back when evaluating a breach.
        if (from.clockRunning() && !to.clockRunning()) {
            issue.setClockPausedAt(Instant.now());
        } else if (!from.clockRunning() && to.clockRunning() && issue.getClockPausedAt() != null) {
            issue.setPausedSeconds(issue.getPausedSeconds()
                    + Duration.between(issue.getClockPausedAt(), Instant.now()).toSeconds());
            issue.setClockPausedAt(null);
        }

        issue.setStatus(to);
        issue.setUpdatedAt(Instant.now());

        historyRepo.save(IssueStatusHistory.of(issue, from, to, actorRole, actorId, note));
    }
}
