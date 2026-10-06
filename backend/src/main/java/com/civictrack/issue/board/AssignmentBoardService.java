package com.civictrack.issue.board;

import com.civictrack.issue.IssueLifecycleService;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.QueueTab;
import com.civictrack.issue.dto.QueueRowDto;
import com.civictrack.user.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reads behind the assignment board (blueprint 3.16, carried into phase 8
 * from week 6).
 *
 * <p>Read-only, deliberately. Assigning and acknowledging are transitions, and
 * transitions have exactly one entry point -- the existing
 * {@code /issues/{id}/assign} and {@code /acknowledge} endpoints, which run the
 * transition table and its guards. A board-specific "assign" would be a second
 * way to change status, which standing rule 5 exists to prevent.
 */
@Service
@RequiredArgsConstructor
public class AssignmentBoardService {

    /** Statuses in which an assignee is actually holding the work. */
    private static final String HELD = "('ASSIGNED','IN_PROGRESS','REOPENED')";

    private final IssueLifecycleService lifecycle;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * The caller's queue, scoped by their token exactly as the staff queue is,
     * with reporter and assignee names attached in two queries for the whole
     * page rather than two per row.
     */
    @Transactional(readOnly = true)
    public List<BoardRowDto> board(Actor actor, QueueTab tab, int limit, int offset) {
        List<IssueRepository.QueueRow> rows = lifecycle.queue(actor, tab, limit, offset);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(IssueRepository.QueueRow::getId).toList();

        Map<UUID, List<BoardRowDto.Reporter>> named = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT r.issue_id, u.id, u.full_name
                FROM reports r JOIN users u ON u.id = r.reporter_id
                WHERE r.issue_id IN (:ids)
                ORDER BY u.full_name
                """, new MapSqlParameterSource("ids", ids), rs -> {
            named.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                    .add(new BoardRowDto.Reporter(rs.getObject(2, UUID.class), rs.getString(3)));
        });

        // Anonymous reporters by device, as distinct_reporter_count counts them.
        Map<UUID, Integer> anonymous = new HashMap<>();
        jdbc.query("""
                SELECT r.issue_id, COUNT(DISTINCT r.device_id)
                FROM reports r
                WHERE r.issue_id IN (:ids) AND r.reporter_id IS NULL
                GROUP BY r.issue_id
                """, new MapSqlParameterSource("ids", ids),
                rs -> { anonymous.put(rs.getObject(1, UUID.class), rs.getInt(2)); });

        List<UUID> assignees = rows.stream().map(IssueRepository.QueueRow::getAssignedTo)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> assigneeNames = new HashMap<>();
        if (!assignees.isEmpty()) {
            jdbc.query("SELECT id, full_name FROM users WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", assignees),
                    rs -> { assigneeNames.put(rs.getObject(1, UUID.class), rs.getString(2)); });
        }

        return rows.stream().map(r -> new BoardRowDto(
                QueueRowDto.from(r),
                named.getOrDefault(r.getId(), List.of()),
                anonymous.getOrDefault(r.getId(), 0),
                r.getAssignedTo() == null ? null : assigneeNames.get(r.getAssignedTo())))
                .toList();
    }

    /**
     * Active staff and supervisors of one department: the only people the
     * ASSIGNEE_IN_SAME_DEPARTMENT guard will accept for that department's
     * issues, so the picker cannot offer a choice the transition would refuse.
     */
    @Transactional(readOnly = true)
    public List<MemberDto> members(UUID departmentId) {
        return jdbc.query("""
                SELECT u.id, u.full_name, u.role, w.name AS ward_name,
                       (SELECT COUNT(*) FROM issues i
                         WHERE i.assigned_to = u.id AND i.status IN %s) AS open_assigned
                FROM users u
                LEFT JOIN wards w ON w.id = u.ward_id
                WHERE u.department_id = :d AND u.active AND u.role IN ('STAFF','SUPERVISOR')
                ORDER BY u.role DESC, u.full_name
                """.formatted(HELD), new MapSqlParameterSource("d", departmentId),
                (rs, n) -> new MemberDto(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("role"), rs.getString("ward_name"), rs.getInt("open_assigned")));
    }
}
