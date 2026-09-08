package com.civictrack.issue.policy;

import com.civictrack.issue.IssueStatus;

/** One edge of the state machine. The key of the transition table. */
public record Transition(IssueStatus from, IssueStatus to) {

    @Override
    public String toString() {
        return from + " -> " + to;
    }
}
