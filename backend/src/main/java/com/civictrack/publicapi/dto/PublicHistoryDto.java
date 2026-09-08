package com.civictrack.publicapi.dto;

import com.civictrack.issue.IssueStatusHistory;

import java.time.Instant;

/**
 * One status transition, as the public timeline shows it.
 *
 * <p>{@code actorRole} and never {@code actorId}. The entity carries both, and
 * dropping only the name while shipping the id would satisfy the letter of
 * "role, never name" and none of its purpose: a stable per-employee identifier
 * across every issue they have ever touched is a work record, and correlating
 * it with anything else that leaks a name reconstructs the identity outright.
 */
public record PublicHistoryDto(
        String fromStatus,
        String toStatus,
        String actorRole,
        String note,
        Instant createdAt
) {
    public static PublicHistoryDto from(IssueStatusHistory h) {
        return new PublicHistoryDto(
                h.getFromStatus() == null ? null : h.getFromStatus().name(),
                h.getToStatus().name(),
                h.getActorRole(),
                h.getNote(),
                h.getCreatedAt());
    }
}
