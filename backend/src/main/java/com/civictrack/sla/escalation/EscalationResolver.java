package com.civictrack.sla.escalation;

import com.civictrack.issue.Issue;

import java.util.Optional;
import java.util.UUID;

/**
 * Who owns an issue at one rung of the ladder.
 *
 * <p>DD-005: the specification described escalation as a walk up
 * {@code departments.parent_department_id}, but level 3 is a ward officer, who
 * is not a node in the department tree at all -- the walk has no defined
 * behaviour there. An ordered list of four explicit strategies replaces it. The
 * ladder is four steps long; a list of four is not worse than a loop that has
 * to special-case one of them.
 */
public interface EscalationResolver {

    /** The rung this resolver answers for, 1 through 4. */
    int level();

    /** The user to hand the issue to, or empty if this rung is unstaffed. */
    Optional<UUID> resolve(Issue issue);

    /** A short description for logs and for the escalation timeline. */
    String describe();
}
