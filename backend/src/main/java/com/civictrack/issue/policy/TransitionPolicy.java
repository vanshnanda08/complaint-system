package com.civictrack.issue.policy;

import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueStatus;
import com.civictrack.user.Actor;
import com.civictrack.user.Role;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.civictrack.issue.IssueStatus.ACKNOWLEDGED;
import static com.civictrack.issue.IssueStatus.ASSIGNED;
import static com.civictrack.issue.IssueStatus.CLOSED;
import static com.civictrack.issue.IssueStatus.IN_PROGRESS;
import static com.civictrack.issue.IssueStatus.NEW;
import static com.civictrack.issue.IssueStatus.PENDING_VERIFICATION;
import static com.civictrack.issue.IssueStatus.REJECTED;
import static com.civictrack.issue.IssueStatus.REOPENED;
import static com.civictrack.issue.IssueStatus.RESOLVED;
import static com.civictrack.user.Role.ADMIN;
import static com.civictrack.user.Role.STAFF;
import static com.civictrack.user.Role.SUPERVISOR;
import static com.civictrack.user.Role.SYSTEM;

/**
 * The transition table, declared once.
 *
 * <p>The alternative -- an {@code if} in each endpoint that knows which
 * statuses it accepts -- spreads the state machine across a dozen methods,
 * where no reader can see the whole of it and where the interesting question
 * ("can staff close their own ticket?") has to be answered by auditing every
 * one of them. Here it is a map with one entry per edge, and the answer is
 * visible in the constructor.
 *
 * <p><b>The structural rule.</b> No edge into {@link IssueStatus#RESOLVED} or
 * {@link IssueStatus#CLOSED} admits {@link Role#STAFF}. Staff reach
 * PENDING_VERIFICATION and stop; the system resolves, after citizens weigh in,
 * and an administrator or the auto-close job closes. That is the claim the
 * whole project makes about accountability, so it is enforced in two places
 * that fail loudly rather than in a comment: this class refuses to construct if
 * the table ever violates it, and {@code TransitionPolicyTest} attempts a STAFF
 * move to RESOLVED from all nine states and requires all nine to be refused.
 */
@Component
public class TransitionPolicy {

    /** Targets that no staff member may ever reach directly. */
    public static final Set<IssueStatus> STAFF_MAY_NEVER_REACH = EnumSet.of(RESOLVED, CLOSED);

    private final Map<Transition, Rule> rules = new HashMap<>();

    public TransitionPolicy() {
        // -- intake -----------------------------------------------------
        allow(NEW, ACKNOWLEDGED, of(STAFF, SUPERVISOR, ADMIN), Guards.SAME_DEPARTMENT);
        allow(ACKNOWLEDGED, ASSIGNED, of(SUPERVISOR, ADMIN),
                Guards.SAME_DEPARTMENT, Guards.ASSIGNEE_IN_SAME_DEPARTMENT);

        // -- work -------------------------------------------------------
        allow(ASSIGNED, IN_PROGRESS, of(STAFF, SUPERVISOR, ADMIN),
                Guards.SAME_DEPARTMENT, Guards.IS_ASSIGNEE);
        allow(REOPENED, IN_PROGRESS, of(STAFF, SUPERVISOR, ADMIN), Guards.SAME_DEPARTMENT);
        allow(IN_PROGRESS, PENDING_VERIFICATION, of(STAFF, SUPERVISOR, ADMIN),
                Guards.SAME_DEPARTMENT, Guards.IS_ASSIGNEE,
                Guards.PROOF_PHOTO_PRESENT, Guards.NOTE_MIN_20);

        // -- the citizen's half of the loop; SYSTEM only ----------------
        allow(PENDING_VERIFICATION, RESOLVED, of(SYSTEM), Guards.QUORUM_MET);
        allow(PENDING_VERIFICATION, REOPENED, of(SYSTEM), Guards.REJECTIONS_PREVAIL);
        allow(RESOLVED, REOPENED, of(SYSTEM), Guards.RECURRENCE_IN_WINDOW);
        // SUPERVISOR closes too, as of the role merge.
        //
        // The objection to this is real and was raised: a department head who
        // can close their own department's breaches makes the overdue figure
        // self-reported. What answers it is that SETTLED_7_DAYS is the guard
        // actually doing the protective work -- an issue cannot be closed
        // until it has sat RESOLVED for seven days, and that window is what
        // gives a citizen time to object. Once it has passed with nobody
        // objecting, who presses the button matters far less.
        //
        // The second answer is that the close is not silent: it writes an
        // issue_status_history row naming the actor, on a page that needs no
        // account to read. This project's position throughout is that
        // publishing a weakness beats prohibiting it -- it publishes its own
        // resolved-without-verification rate rather than burying it. Blocking
        // the close by role while a supervisor could already REJECT anything
        // they liked was the inconsistent half.
        //
        // What does NOT move: STAFF_MAY_NEVER_REACH still contains RESOLVED
        // and CLOSED. A crew member marking their own work finished is the
        // person doing the job certifying it, and no waiting period fixes that.
        allow(RESOLVED, CLOSED, of(SYSTEM, ADMIN, SUPERVISOR), Guards.SETTLED_7_DAYS);

        // -- rejection, from any open state -----------------------------
        for (IssueStatus from : List.of(NEW, ACKNOWLEDGED, ASSIGNED, IN_PROGRESS,
                PENDING_VERIFICATION, REOPENED)) {
            allow(from, REJECTED, of(SUPERVISOR, ADMIN),
                    Guards.SAME_DEPARTMENT, Guards.REASON_MIN_20);
        }

        assertStaffCannotResolveOrClose();
    }

    /**
     * Throws unless the transition is legal, permitted for this role, and
     * every guard on it passes -- in that order, because the order determines
     * which of three different answers the caller gets.
     */
    public void check(Issue issue, IssueStatus to, Actor actor, TransitionContext ctx) {
        IssueStatus from = issue.getStatus();
        Rule rule = rules.get(new Transition(from, to));
        if (rule == null) {
            throw new IllegalTransitionException(from, to);
        }
        if (!rule.actors().contains(actor.role())) {
            throw new ForbiddenTransitionException(actor.role(), from, to);
        }
        for (Guard guard : rule.guards()) {
            if (!guard.permits(issue, actor, ctx)) {
                throw new TransitionGuardException(guard);
            }
        }
    }

    /** True if any role at all may make this move. Used by the table's own test. */
    public boolean isDefined(IssueStatus from, IssueStatus to) {
        return rules.containsKey(new Transition(from, to));
    }

    public Set<Role> actorsFor(IssueStatus from, IssueStatus to) {
        Rule rule = rules.get(new Transition(from, to));
        return rule == null ? Set.of() : rule.actors();
    }

    public List<Guard> guardsFor(IssueStatus from, IssueStatus to) {
        Rule rule = rules.get(new Transition(from, to));
        return rule == null ? List.of() : rule.guards();
    }

    private void allow(IssueStatus from, IssueStatus to, Set<Role> actors, Guard... guards) {
        rules.put(new Transition(from, to), new Rule(actors, List.of(guards)));
    }

    private static Set<Role> of(Role... roles) {
        return Set.of(roles);
    }

    /**
     * Fails startup if the table is ever edited to let staff resolve or close.
     * A rule this central should not be able to be broken by a one-word change
     * that nothing notices until a demo.
     */
    private void assertStaffCannotResolveOrClose() {
        rules.forEach((transition, rule) -> {
            if (STAFF_MAY_NEVER_REACH.contains(transition.to())
                    && rule.actors().contains(STAFF)) {
                throw new IllegalStateException(
                        "Transition table violates the structural rule: STAFF may not reach "
                        + transition.to() + " (found on " + transition + "). Staff reach "
                        + "PENDING_VERIFICATION and stop -- the system closes the loop.");
            }
        });
    }

    record Rule(Set<Role> actors, List<Guard> guards) {
    }
}
