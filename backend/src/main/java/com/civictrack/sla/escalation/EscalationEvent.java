package com.civictrack.sla.escalation;

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

/**
 * One rung climbed, recorded immutably.
 *
 * <p>{@code UNIQUE (issue_id, level)} on this table is the second of the three
 * idempotency layers: even if ShedLock failed and the compare-and-swap raced,
 * the database physically refuses to record level 2 twice. The sweep catches
 * the resulting integrity violation and moves on, which is why running the job
 * twice is indistinguishable from running it once.
 */
@Entity
@Table(name = "escalation_events")
@Getter
@Setter
public class EscalationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "from_user_id")
    private UUID fromUserId;

    @Column(name = "to_user_id")
    private UUID toUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 60)
    private EscalationReason reason;

    @Column(name = "breached_by_seconds")
    private Long breachedBySeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    static EscalationEvent of(UUID issueId, int level, UUID fromUserId, UUID toUserId,
                              EscalationReason reason, long breachedBySeconds, Instant now) {
        EscalationEvent e = new EscalationEvent();
        e.issueId = issueId;
        e.level = level;
        e.fromUserId = fromUserId;
        e.toUserId = toUserId;
        e.reason = reason;
        e.breachedBySeconds = breachedBySeconds;
        e.createdAt = now;
        return e;
    }
}
