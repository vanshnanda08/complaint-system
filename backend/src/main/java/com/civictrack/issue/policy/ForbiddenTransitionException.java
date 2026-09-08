package com.civictrack.issue.policy;

import com.civictrack.issue.IssueStatus;
import com.civictrack.user.Role;
import lombok.Getter;

/**
 * The edge exists but this role may not walk it. Answered with 403.
 *
 * <p>Distinct from {@link IllegalTransitionException} on purpose: "that move
 * does not exist" and "that move exists but is not yours" are different facts,
 * and a staff member who tries to resolve their own ticket should be told the
 * second one.
 */
@Getter
public class ForbiddenTransitionException extends RuntimeException {

    private final Role role;
    private final IssueStatus from;
    private final IssueStatus to;

    public ForbiddenTransitionException(Role role, IssueStatus from, IssueStatus to) {
        super(role + " may not move an issue from " + from + " to " + to);
        this.role = role;
        this.from = from;
        this.to = to;
    }
}
