package com.civictrack.support;

import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.common.geo.GeoFactory;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import com.civictrack.user.UserRepository;
import com.civictrack.user.auth.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builders for the rows the lifecycle and escalation tests need.
 *
 * <p>Issues are created at NEW and moved by real transitions wherever a test
 * needs another state, because {@code Issue.setStatus} is package-private and
 * this class is deliberately not in that package. That constraint is standing
 * rule 5 doing its job on the test suite as well as on the application: a
 * fixture that could write a status directly would let a test set up a state
 * the state machine cannot actually reach.
 */
@Component
@RequiredArgsConstructor
public class Fixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final IssueRepository issues;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final TokenService tokens;
    private final JdbcTemplate jdbc;

    // ---- reference data ------------------------------------------------

    public UUID departmentId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM departments WHERE code = ?", UUID.class, code);
    }

    public UUID wardId(int wardNumber) {
        return jdbc.queryForObject(
                "SELECT id FROM wards WHERE ward_number = ?", UUID.class, wardNumber);
    }

    public UUID wardOfficerId(int wardNumber) {
        return jdbc.queryForObject(
                "SELECT officer_user_id FROM wards WHERE ward_number = ?", UUID.class, wardNumber);
    }

    public UUID adminId() {
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE role = 'ADMIN' ORDER BY created_at, id LIMIT 1",
                UUID.class);
    }

    public UUID departmentHeadId(String code) {
        return jdbc.queryForObject(
                "SELECT head_user_id FROM departments WHERE code = ?", UUID.class, code);
    }

    // ---- users ---------------------------------------------------------

    @Transactional
    public AppUser user(Role role, UUID departmentId, UUID wardId) {
        AppUser user = new AppUser();
        int n = SEQ.incrementAndGet();
        user.setEmail("test-" + role.name().toLowerCase() + "-" + n + "-"
                      + UUID.randomUUID() + "@civictrack.test");
        user.setFullName("Test " + role.name() + " " + n);
        user.setRole(role);
        user.setDepartmentId(departmentId);
        user.setWardId(wardId);
        return users.save(user);
    }

    public String accessToken(AppUser user) {
        return tokens.issue(user).accessToken();
    }

    public String refreshToken(AppUser user) {
        return tokens.issue(user).refreshToken();
    }

    public String bearer(AppUser user) {
        return "Bearer " + accessToken(user);
    }

    // ---- issues --------------------------------------------------------

    /** A NEW issue in ward 1, owned by the category's department. */
    @Transactional
    public Issue issue(String categoryCode, Instant firstReportedAt, Instant dueAt) {
        Category category = categories.findById(categoryCode).orElseThrow();
        Issue issue = new Issue();
        issue.setPublicRef(issues.nextPublicRef());
        issue.setCategoryCode(categoryCode);
        issue.setWardId(wardId(1));
        issue.setDepartmentId(category.getDepartmentId());
        issue.setCentroid(GeoFactory.point(30.9300, 75.8200));
        issue.setSumW(1.0 / 64);
        issue.setSumWx(75.8200 / 64);
        issue.setSumWy(30.9300 / 64);
        issue.setReportCount(1);
        issue.setDistinctReporterCount(1);
        issue.setFirstReportedAt(firstReportedAt);
        issue.setLastReportedAt(firstReportedAt);
        issue.setDueAt(dueAt);
        return issues.save(issue);
    }

    /** An issue whose deadline is already in the past. */
    @Transactional
    public Issue breachedIssue(String categoryCode, Instant now) {
        return issue(categoryCode, now.minusSeconds(48 * 3600), now.minusSeconds(3600));
    }

    /** Deletes everything an issue-scoped test could have written. */
    public void clearIssues() {
        jdbc.update("DELETE FROM escalation_events");
        jdbc.update("DELETE FROM verifications");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM issue_status_history");
        jdbc.update("DELETE FROM reports");
        jdbc.update("DELETE FROM issues");
    }
}
