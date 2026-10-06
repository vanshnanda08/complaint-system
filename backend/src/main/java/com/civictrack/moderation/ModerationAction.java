package com.civictrack.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One entry in the permanent moderation log (V6, DD-063). Append-only: there
 * is no setter, and the repository has no delete.
 */
@Entity
@Table(name = "moderation_actions")
@Getter
public class ModerationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private ModerationActionType action;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "related_issue_id")
    private UUID relatedIssueId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "actor_role", nullable = false, length = 20)
    private String actorRole;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static ModerationAction of(ModerationActionType action, UUID issueId, UUID relatedIssueId,
                               UUID actorId, String actorRole, Map<String, Object> detail,
                               String note, Instant now) {
        ModerationAction a = new ModerationAction();
        a.action = action;
        a.issueId = issueId;
        a.relatedIssueId = relatedIssueId;
        a.actorId = actorId;
        a.actorRole = actorRole;
        a.detail = detail;
        a.note = note;
        a.createdAt = now;
        return a;
    }
}
