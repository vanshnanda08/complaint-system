package com.civictrack.verification.dto;

import com.civictrack.publicapi.dto.PublicIssueDto;

import java.time.Instant;

/** One fix waiting on the caller's answer. */
public record AwaitingVerdictDto(PublicIssueDto issue, Instant silenceDeadline) {
}
