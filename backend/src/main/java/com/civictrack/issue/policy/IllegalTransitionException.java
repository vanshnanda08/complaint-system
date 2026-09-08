package com.civictrack.issue.policy;

import com.civictrack.issue.IssueStatus;
import lombok.Getter;

/** No edge exists between these two states. Answered with 409. */
@Getter
public class IllegalTransitionException extends RuntimeException {

    private final IssueStatus from;
    private final IssueStatus to;

    public IllegalTransitionException(IssueStatus from, IssueStatus to) {
        super("An issue cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }
}
