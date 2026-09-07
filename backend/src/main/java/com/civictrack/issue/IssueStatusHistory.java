package com.civictrack.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One immutable row per status transition. Never updated, never deleted. */
@Entity
@Table(name = "issue_status_history")
@Getter
@Setter
public class IssueStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private IssueStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private IssueStatus toStatus;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role", nullable = false, length = 20)
    private String actorRole;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    static IssueStatusHistory of(Issue issue, IssueStatus from, IssueStatus to,
                                 String actorRole, UUID actorId, String note) {
        IssueStatusHistory h = new IssueStatusHistory();
        h.issueId = issue.getId();
        h.fromStatus = from;
        h.toStatus = to;
        h.actorRole = actorRole;
        h.actorId = actorId;
        h.note = note;
        return h;
    }
}
