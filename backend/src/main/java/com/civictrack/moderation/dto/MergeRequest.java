package com.civictrack.moderation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Merge {@code sourceIssueId} into the issue in the path. The source is closed out; the path issue keeps everything. */
public record MergeRequest(@NotNull UUID sourceIssueId, @Size(max = 500) String note) {
}
