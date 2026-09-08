package com.civictrack.user;

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
 * A person with a login, or a person the escalation ladder can hand an issue
 * to.
 *
 * <p>Named {@code AppUser} rather than {@code User} because {@code user} is a
 * reserved word in PostgreSQL and because {@code java.security.Principal}
 * implementations called {@code User} exist in Spring Security; the table is
 * still {@code users}.
 *
 * <p>{@code passwordHash} is nullable, and that is load-bearing. The staff,
 * ward-officer and administrator accounts seeded in V4 exist so that escalation
 * has somewhere to route to on a fresh database, and they are seeded
 * <em>without</em> a password. An account with no hash cannot be logged into --
 * {@link com.civictrack.user.auth.AuthService} refuses before it reaches the
 * encoder -- so the migration ships an org chart rather than a set of
 * credentials.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "email", length = 180, unique = true)
    private String email;

    @Column(name = "phone", length = 20, unique = true)
    private String phone;

    /** Null means "this account cannot authenticate". See the class javadoc. */
    @Column(name = "password_hash", length = 120)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    /** Null for citizens and for ward officers, who are scoped by ward instead. */
    @Column(name = "department_id")
    private UUID departmentId;

    /** Set for ward officers, and for crew whose work is confined to one ward. */
    @Column(name = "ward_id")
    private UUID wardId;

    @Column(name = "reputation", nullable = false)
    private int reputation = 100;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Actor toActor() {
        return new Actor(id, role, departmentId, wardId);
    }
}
