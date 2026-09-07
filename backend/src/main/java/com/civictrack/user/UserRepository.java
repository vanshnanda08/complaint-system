package com.civictrack.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    /**
     * The level-4 rung of the escalation ladder (DD-005).
     *
     * <p>Ordered by creation so the resolution is deterministic: an issue that
     * escalates to the commissioner must land on the same desk every time, and
     * an arbitrary pick would make the chronic-breach list unstable between
     * sweeps.
     */
    @Query("""
            SELECT u FROM AppUser u
            WHERE u.role = com.civictrack.user.Role.ADMIN AND u.active = true
            ORDER BY u.createdAt ASC, u.id ASC
            """)
    List<AppUser> findAdmins();

    @Query(value = "SELECT head_user_id FROM departments WHERE id = :id", nativeQuery = true)
    Optional<UUID> findDepartmentHead(@Param("id") UUID departmentId);

    /**
     * The head of the parent of this department -- level 2 of the ladder. One
     * hop only: the tree is two deep by design, and a loop over an unbounded
     * ancestry would have no defined stopping point when it ran out of parents.
     */
    @Query(value = """
            SELECT parent.head_user_id
            FROM departments child
            JOIN departments parent ON parent.id = child.parent_department_id
            WHERE child.id = :id
            """, nativeQuery = true)
    Optional<UUID> findParentDepartmentHead(@Param("id") UUID departmentId);

    @Query(value = "SELECT officer_user_id FROM wards WHERE id = :id", nativeQuery = true)
    Optional<UUID> findWardOfficer(@Param("id") UUID wardId);
}
