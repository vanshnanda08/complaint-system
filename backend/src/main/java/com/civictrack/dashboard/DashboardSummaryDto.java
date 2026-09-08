package com.civictrack.dashboard;

import java.time.Instant;

/**
 * The four numbers the landing hero and the dashboard's live tiles are built
 * from.
 *
 * <p>{@code overdueCount} is nullable, and that is the contract rather than an
 * oversight. Blueprint 3.1 puts a live overdue count in the landing hero and
 * requires the page to fall back to the total resolved when the count is
 * unavailable, so the response has to be able to say "I could not compute this
 * one" without failing the whole document. A 500 here would blank the front
 * page of the site over one aggregate query.
 */
public record DashboardSummaryDto(
        Long overdueCount,
        long totalResolved,
        NamedCount topOverdueWard,
        NamedCount topOverdueDepartment,
        Instant generatedAt
) {
    public record NamedCount(String name, long count) {
    }
}
