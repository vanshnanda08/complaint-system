package com.civictrack.verification;

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
 * One citizen's vote on one fix.
 *
 * <p>{@code UNIQUE (issue_id, citizen_id)} in the schema is what makes it one
 * vote each. Anonymous reporters have no row here at all, which is the hole
 * DD-006 measures rather than claims to have closed.
 */
@Entity
@Table(name = "verifications")
@Getter
@Setter
public class Verification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "citizen_id", nullable = false)
    private UUID citizenId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 12)
    private Verdict verdict;

    @Column(name = "comment")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
