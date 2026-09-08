package com.civictrack.department;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A node in the two-level org chart the escalation ladder walks (DD-005).
 *
 * <p>The table has existed since V1 and is populated by V2 and V4, but until
 * now nothing in Java mapped it: the ladder reads {@code head_user_id} and
 * {@code parent_department_id} through native projections on
 * {@link com.civictrack.user.UserRepository}, which is all escalation ever
 * needed. The public read surface needs something escalation never did -- a
 * department's <em>name</em>, to print on an issue -- and that is what this
 * entity exists for.
 *
 * <p>{@code parentDepartmentId} is mapped as a raw id rather than as a
 * {@code @ManyToOne} to itself, for the same reason the rest of the project
 * maps foreign keys as ids: a self-referential association turns every
 * department load into a walk up the tree and reintroduces exactly the
 * LazyInitializationException class of bug that {@link com.civictrack.issue.Issue}
 * documents avoiding.
 */
@Entity
@Table(name = "departments")
@Getter
@Setter
public class Department {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "parent_department_id")
    private UUID parentDepartmentId;

    @Column(name = "head_user_id")
    private UUID headUserId;
}
