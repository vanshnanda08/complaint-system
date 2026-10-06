package com.civictrack.issue;

import com.civictrack.user.Actor;

import java.time.Instant;

/**
 * Published by {@link IssueStatusService#transition} after every status change,
 * inside the transaction that made it.
 *
 * <p>An event rather than a direct call, so the single writer of status does
 * not grow a dependency on every feature that reacts to one. Notifications are
 * the first listener; the phase 7 live stream will be the second, and it must
 * not be wired the same way -- broadcasting from inside the transaction would
 * announce changes that can still roll back, so that one belongs on
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 *
 * <p>Carries the managed entity rather than its id, because the listener runs
 * in the same persistence context and would otherwise reload what the
 * publisher is already holding.
 */
public record IssueTransitioned(Issue issue, IssueStatus from, IssueStatus to,
                                Actor actor, String note, Instant at) {
}
