package com.civictrack.issue.policy;

import com.civictrack.issue.Issue;
import com.civictrack.user.Actor;
import com.civictrack.user.Role;

import java.time.Duration;
import java.util.Objects;

/**
 * The named guards of the transition table.
 *
 * <p>An enum rather than a set of lambdas, so that every guard has a stable
 * name that appears in the API's problem response, in the audit trail and in
 * the test that enumerates the table. "Which rule stopped me?" should be
 * answerable without reading the source.
 */
public enum Guards implements Guard {

    /**
     * The actor works for the department that owns the issue.
     *
     * <p>Administrators pass everywhere; a ward officer -- a supervisor with a
     * ward and no department -- passes on issues in their ward, which is the
     * whole reason they sit at level 3 of the escalation ladder. Without that
     * clause the officer the ladder escalates to would be unable to act on what
     * had just been handed to them.
     */
    SAME_DEPARTMENT {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            if (actor.isAdmin() || actor.isSystem()) {
                return true;
            }
            if (actor.departmentId() != null
                    && actor.departmentId().equals(issue.getDepartmentId())) {
                return true;
            }
            return actor.role() == Role.SUPERVISOR
                    && actor.wardId() != null
                    && actor.wardId().equals(issue.getWardId());
        }

        @Override
        public String explanation() {
            return "the issue belongs to another department or ward";
        }
    },

    /**
     * Staff may only start or finish work assigned to them.
     *
     * <p>Supervisors and administrators pass without being the assignee. That
     * is not a loophole: a supervisor covering for an absent crew member is
     * ordinary municipal practice, and the alternative -- reassigning the
     * ticket to themselves first -- produces a worse audit trail, not a better
     * one, because it overwrites who the work was actually given to. The
     * history row records who really acted either way.
     */
    IS_ASSIGNEE {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            if (actor.isSystem() || actor.isAdmin() || actor.role() == Role.SUPERVISOR) {
                return true;
            }
            return issue.getAssignedTo() != null && issue.getAssignedTo().equals(actor.id());
        }

        @Override
        public String explanation() {
            return "this issue is assigned to somebody else";
        }
    },

    /** Work cannot be handed to somebody in a different department. */
    ASSIGNEE_IN_SAME_DEPARTMENT {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            return ctx.assigneeId() != null
                    && Objects.equals(ctx.assigneeDepartmentId(), issue.getDepartmentId());
        }

        @Override
        public String explanation() {
            return "the assignee must belong to the department that owns this issue";
        }
    },

    /**
     * A proof photo is required to claim a fix.
     *
     * <p>This is the guard the pitch rests on. Without evidence attached, a
     * "resolved" status is a claim; with it, it is a claim a citizen can
     * contradict by looking at the photo. Note that it gates the move to
     * PENDING_VERIFICATION, not to RESOLVED -- staff have no path to RESOLVED
     * at all.
     */
    PROOF_PHOTO_PRESENT {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            return ctx.proofPhotoUrl() != null && !ctx.proofPhotoUrl().isBlank();
        }

        @Override
        public String explanation() {
            return "a proof photo is required before an issue can go to verification";
        }
    },

    /**
     * Twenty characters of explanation.
     *
     * <p>Low, on purpose. It is not a quality bar -- it cannot be -- it is a
     * friction bar: it rules out "done", "fixed" and an empty string, which are
     * what a field with no minimum actually receives.
     */
    NOTE_MIN_20 {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            return ctx.note() != null && ctx.note().strip().length() >= 20;
        }

        @Override
        public String explanation() {
            return "a note of at least 20 characters is required";
        }
    },

    /** Rejecting somebody's report requires saying why; every reporter is told. */
    REASON_MIN_20 {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            return NOTE_MIN_20.permits(issue, actor, ctx);
        }

        @Override
        public String explanation() {
            return "a rejection reason of at least 20 characters is required";
        }
    },

    /** Citizens confirmed the fix, or nobody objected within the timeout. */
    QUORUM_MET {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            return ctx.tally().quorumMet();
        }

        @Override
        public String explanation() {
            return "the verification quorum has not been reached";
        }
    },

    /** Citizens said it is not fixed. */
    REJECTIONS_PREVAIL {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            return ctx.tally().rejectionsPrevail();
        }

        @Override
        public String explanation() {
            return "no citizen has rejected this fix";
        }
    },

    /**
     * A resolved issue reopens only on a genuine recurrence inside the
     * category's reopen window. The clustering engine establishes that before
     * calling; the guard exists so that the rule is visible in the table rather
     * than implicit in one caller.
     */
    RECURRENCE_IN_WINDOW {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            return ctx.recurrenceWithinWindow();
        }

        @Override
        public String explanation() {
            return "reopening requires a new report inside the category's reopen window";
        }
    },

    /**
     * Seven quiet days after resolution before an issue closes for good.
     *
     * <p>Closing is what ends the reopen path, so the window is the citizen's
     * last opportunity to say the problem came back. Closing immediately on
     * resolution would make the reopen rate -- a published number -- structurally
     * impossible to move.
     */
    SETTLED_7_DAYS {
        @Override
        public boolean permits(Issue issue, Actor actor, TransitionContext ctx) {
            if (issue.getResolvedAt() == null) {
                return false;
            }
            Duration settled = Duration.between(issue.getResolvedAt(), ctx.now());
            return settled.compareTo(ctx.autoCloseAfter()) >= 0;
        }

        @Override
        public String explanation() {
            return "an issue cannot be closed until it has been resolved for seven days";
        }
    };

    @Override
    public String key() {
        return name();
    }
}
