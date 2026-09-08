package com.civictrack.sla;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.sla.escalation.EscalationEvent;
import com.civictrack.sla.escalation.EscalationEventRepository;
import com.civictrack.sla.escalation.EscalationService;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-005: the ladder is an ordered list of resolvers, not a tree walk.
 *
 * <p>The specification described escalation as a walk up
 * {@code departments.parent_department_id}. Level 3 is a ward officer, who is
 * not in the department tree at all, so the walk had no defined behaviour
 * there -- it would have had to invent something, and whatever it invented
 * would have diverged from the published ladder. These tests pin each rung to
 * the source the ladder actually reads.
 */
class EscalationLadderIT extends IntegrationTestBase {

    @Autowired private EscalationService escalationService;
    @Autowired private EscalationEventRepository escalations;
    @Autowired private IssueRepository issues;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;

    private Instant now;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        now = Instant.parse("2026-03-01T09:00:00Z");
        clock.set(now);
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    @Test
    @DisplayName("level 1 hands the issue to the head of the owning department")
    void levelOneIsTheDepartmentHead() {
        Issue issue = escalateFromLevel(0);

        assertThat(ownerAtLevel(issue.getId(), 1))
                .isEqualTo(fixtures.departmentHeadId("ROADS"));
    }

    @Test
    @DisplayName("level 2 walks one hop up parent_department_id")
    void levelTwoIsTheParentDepartmentHead() {
        Issue issue = escalateFromLevel(1);

        assertThat(ownerAtLevel(issue.getId(), 2))
                .as("ROADS sits under PUBLIC_WORKS; this is the one rung the tree walk got right")
                .isEqualTo(fixtures.departmentHeadId("PUBLIC_WORKS"));
    }

    @Test
    @DisplayName("level 3 resolves to the ward officer, not to another department head")
    void levelThreeIsTheWardOfficer() {
        Issue issue = escalateFromLevel(2);

        UUID owner = ownerAtLevel(issue.getId(), 3);
        assertThat(owner)
                .as("this is the rung that broke the tree walk: the ward officer is not a node "
                    + "in the department hierarchy at all")
                .isEqualTo(fixtures.wardOfficerId(1));

        // Stated as a negative too, because "resolved to somebody" is not the
        // claim -- "resolved to somebody outside the department chain" is.
        assertThat(owner)
                .isNotEqualTo(fixtures.departmentHeadId("ROADS"))
                .isNotEqualTo(fixtures.departmentHeadId("PUBLIC_WORKS"));
    }

    @Test
    @DisplayName("level 4 resolves to the administrator and is terminal")
    void levelFourIsTheAdministrator() {
        Issue issue = escalateFromLevel(3);

        assertThat(ownerAtLevel(issue.getId(), 4))
                .isEqualTo(fixtures.adminId());
        assertThat(issues.findById(issue.getId()).orElseThrow().getEscalationLevel()).isEqualTo(4);
    }

    @Test
    @DisplayName("escalation reassigns the issue to the new owner")
    void escalationMovesOwnership() {
        Issue issue = escalateFromLevel(2);

        assertThat(issues.findById(issue.getId()).orElseThrow().getAssignedTo())
                .as("escalation that does not change who is accountable is only a counter")
                .isEqualTo(fixtures.wardOfficerId(1));
    }

    private Issue escalateFromLevel(int startingLevel) {
        Issue issue = fixtures.breachedIssue("POTHOLE", now);
        issue.setEscalationLevel(startingLevel);
        issues.saveAndFlush(issue);
        escalationService.escalateOne(issue.getId(), now);
        return issue;
    }

    private UUID ownerAtLevel(UUID issueId, int level) {
        return escalations.findByIssueIdOrderByLevelAsc(issueId).stream()
                .filter(e -> e.getLevel() == level)
                .map(EscalationEvent::getToUserId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No escalation event at level " + level));
    }
}
