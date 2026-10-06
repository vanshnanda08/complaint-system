package com.civictrack.stream;

/**
 * Something the public dashboard shows may have changed, for a reason other
 * than a status transition (which has its own event, {@code IssueTransitioned}).
 * A split creating an issue that is already overdue; an SLA sweep, after which
 * the set of breaching issues is different simply because time passed.
 */
public record DashboardChanged(String reason) {
}
