package com.civictrack.moderation.dto;

import java.util.UUID;

/**
 * What an action changed, by reference. For a split, {@code relatedIssueId} is
 * the new issue; for a merge, the issue merged away. The client refetches the
 * issues themselves -- they have changed in more ways than this could carry.
 */
public record ModerationResultDto(UUID issueId, String publicRef,
                                  UUID relatedIssueId, String relatedPublicRef) {
}
