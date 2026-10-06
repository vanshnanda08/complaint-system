package com.civictrack.moderation.dto;

import com.civictrack.moderation.ModerationAction;
import com.civictrack.moderation.ModerationActionType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One moderation log entry. The actor by role, as on the public timeline; the staff API needs no more. */
public record ModerationEntryDto(long id, ModerationActionType action, UUID issueId, UUID relatedIssueId,
                                 String actorRole, String note, Instant createdAt,
                                 Map<String, Object> detail) {

    public static ModerationEntryDto from(ModerationAction a) {
        return new ModerationEntryDto(a.getId(), a.getAction(), a.getIssueId(), a.getRelatedIssueId(),
                a.getActorRole(), a.getNote(), a.getCreatedAt(), a.getDetail());
    }
}
