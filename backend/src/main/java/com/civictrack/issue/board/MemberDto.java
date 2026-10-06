package com.civictrack.issue.board;

import java.util.UUID;

/**
 * Someone an issue can be assigned to, with how much they already hold, so a
 * supervisor assigning work can see who is already loaded.
 */
public record MemberDto(UUID id, String fullName, String role, String wardName, int openAssigned) {
}
