package com.civictrack.dashboard;

import java.time.Instant;
import java.util.UUID;

/** One issue on the live "currently breaching" list. Public: ticket, place, owner department, how late. */
public record BreachingIssueDto(UUID id, String publicRef, String categoryName, String wardName,
                                String departmentName, String status, Instant effectiveDeadline,
                                long overdueSeconds, int escalationLevel, int distinctReporterCount) {
}
