package com.civictrack.issue.policy;

import com.civictrack.issue.Issue;
import com.civictrack.user.Actor;

/**
 * A precondition on a transition, over and above the actor's role.
 *
 * <p>Roles answer "what kind of user may do this kind of thing". Guards answer
 * "is this particular move legitimate right now". Keeping them separate is what
 * lets the transition table be read as a table: every row is a role set plus a
 * list of named guards, and neither is buried inside the other.
 */
public interface Guard {

    /** A stable machine-readable name, returned to the client on failure. */
    String key();

    /** True when the transition may proceed. */
    boolean permits(Issue issue, Actor actor, TransitionContext ctx);

    /** What the caller has to change to make it pass. */
    String explanation();
}
