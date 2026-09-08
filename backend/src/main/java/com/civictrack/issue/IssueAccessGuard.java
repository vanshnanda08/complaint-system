package com.civictrack.issue;

import com.civictrack.user.Actor;
import com.civictrack.user.Role;
import com.civictrack.user.auth.Actors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The second authorisation layer: is <em>this</em> user allowed to touch
 * <em>this</em> issue?
 *
 * <p>{@code @PreAuthorize("hasRole('STAFF')")} answers "can this kind of user
 * do this kind of thing", and it is not enough on its own: a staff member in
 * Sanitation satisfies it perfectly while trying to act on a Roads ticket.
 * Scope is a property of the pair, so it needs a bean that can see both.
 *
 * <p><b>What this deliberately does not check.</b> Whether the user is the
 * assignee. The blueprint's sketch folded that into the same method, which
 * makes the guard unusable for acknowledgement -- an issue nobody has been
 * assigned yet would be untouchable by everybody. Assignment is a
 * <em>per-transition</em> rule, so it lives in the transition table as the
 * {@code IS_ASSIGNEE} guard, where it applies to starting and finishing work
 * and to nothing else.
 */
@Component("issueGuard")
@RequiredArgsConstructor
public class IssueAccessGuard {

    private final IssueRepository issues;

    @Transactional(readOnly = true)
    public boolean canAct(UUID issueId, Authentication authentication) {
        Actor actor = Actors.from(authentication);
        if (actor == null) {
            return false;
        }
        if (actor.isAdmin()) {
            return true;
        }
        Issue issue = issues.findById(issueId).orElse(null);
        if (issue == null) {
            // Answering "no" for a missing issue rather than throwing turns a
            // 404 into a 403, which is the right trade: an unauthorised caller
            // must not be able to use the difference to discover which issue
            // ids exist. The 404 is still produced for callers who pass this.
            return false;
        }
        return inScope(actor, issue);
    }

    /**
     * Department and ward scope.
     *
     * <p>Ward officers are supervisors with a ward and no department -- they
     * are level 3 of the escalation ladder, so an issue can be handed to them
     * across departmental lines and they must be able to act on it. Crew with a
     * ward set are confined to it; crew with none serve the whole city, which
     * is how a small municipal department with two plumbers actually works.
     */
    private boolean inScope(Actor actor, Issue issue) {
        boolean sameDepartment = actor.departmentId() != null
                && actor.departmentId().equals(issue.getDepartmentId());
        boolean sameWard = actor.wardId() != null && actor.wardId().equals(issue.getWardId());

        return switch (actor.role()) {
            case SUPERVISOR -> sameDepartment || sameWard;
            case STAFF -> sameDepartment && (actor.wardId() == null || sameWard);
            case ADMIN -> true;
            case CITIZEN, SYSTEM -> false;
        };
    }

    /** A citizen may verify only an issue they themselves reported. */
    @Transactional(readOnly = true)
    public boolean canVerify(UUID issueId, Authentication authentication) {
        Actor actor = Actors.from(authentication);
        return actor != null && actor.role() == Role.CITIZEN
                && issues.existsReportBy(issueId, actor.id());
    }
}
