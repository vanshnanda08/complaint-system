package com.civictrack.issue;

import java.util.Set;

public enum IssueStatus {
    NEW, ACKNOWLEDGED, ASSIGNED, IN_PROGRESS, PENDING_VERIFICATION,
    RESOLVED, REOPENED, CLOSED, REJECTED;

    /** Statuses a new report may merge into without reopening anything. */
    public static final Set<IssueStatus> OPEN_FOR_CLUSTERING = Set.of(
            NEW, ACKNOWLEDGED, ASSIGNED, IN_PROGRESS, PENDING_VERIFICATION, REOPENED);

    public boolean isTerminal() {
        return this == CLOSED || this == REJECTED;
    }

    public boolean isOpen() {
        return !isTerminal() && this != RESOLVED;
    }

    /**
     * The SLA clock runs in every open state except PENDING_VERIFICATION,
     * where the department is waiting on citizens and should not be charged
     * for the delay.
     */
    public boolean clockRunning() {
        return isOpen() && this != PENDING_VERIFICATION;
    }
}
