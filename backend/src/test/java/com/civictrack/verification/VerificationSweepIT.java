package com.civictrack.verification;

import com.civictrack.IntegrationTestBase;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueLifecycleService;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The timeout and auto-close sweep, driven through
 * {@link VerificationSweepService} with no ShedLock in front of it, so that
 * "running it twice is running it once" is a claim about the database layers
 * and not about the lock.
 */
@AutoConfigureMockMvc
class VerificationSweepIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private VerificationSweepService sweep;
    @Autowired private VerificationService verification;
    @Autowired private IssueRepository issues;
    @Autowired private IssueLifecycleService lifecycle;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private AppUser crew;
    private AppUser supervisor;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        clock.set(Instant.parse("2026-03-01T09:00:00Z"));
        UUID roads = fixtures.departmentId("ROADS");
        crew = fixtures.user(Role.STAFF, roads, null);
        supervisor = fixtures.user(Role.SUPERVISOR, roads, null);
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    @Test
    @DisplayName("72 silent hours resolve an issue and flag it resolved without verification")
    void silenceIsConsent() {
        Issue issue = pending(fixtures.user(Role.CITIZEN, null, null));

        clock.advance(Duration.ofHours(71).plusMinutes(59));
        assertThat(sweep.sweep().resolvedWithoutVerification()).as("one minute early").isZero();
        assertThat(reload(issue).getStatus()).isEqualTo(IssueStatus.PENDING_VERIFICATION);

        clock.advance(Duration.ofMinutes(1));
        assertThat(sweep.sweep().resolvedWithoutVerification()).isEqualTo(1);

        Issue after = reload(issue);
        assertThat(after.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(after.isResolvedWithoutVerification()).isTrue();
        assertThat(notificationTypes()).contains("RESOLVED");
    }

    @Test
    @DisplayName("an issue reported only anonymously always resolves this way -- the DD-006 hole")
    void anonymousOnlyIssueResolvesUnverified() {
        Issue issue = fixtures.issue("POTHOLE", clock.instant(), clock.instant().plusSeconds(3 * 86_400));
        fixtures.report(issue.getId(), null);
        submit(issue);

        clock.advanceHours(72);
        sweep.sweep();

        assertThat(reload(issue).isResolvedWithoutVerification()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class))
                .as("there was nobody to ask and nobody to tell")
                .isZero();
    }

    @Test
    @DisplayName("a contested vote short of quorum is settled by majority at the timeout (DD-060)")
    void contestedVoteSettlesAtTimeout() {
        // Six reporters: three confirmations needed.
        List<AppUser> six = java.util.stream.Stream.generate(() -> fixtures.user(Role.CITIZEN, null, null))
                .limit(6).toList();
        Issue issue = pending(six.toArray(AppUser[]::new));
        verification.vote(issue.getId(), six.get(0).toActor(), Verdict.FIXED, null);
        verification.vote(issue.getId(), six.get(1).toActor(), Verdict.FIXED, null);
        verification.vote(issue.getId(), six.get(2).toActor(), Verdict.NOT_FIXED, "Partly patched only");
        assertThat(reload(issue).getStatus()).isEqualTo(IssueStatus.PENDING_VERIFICATION);

        clock.advanceHours(72);
        assertThat(sweep.sweep().resolved()).isEqualTo(1);

        Issue after = reload(issue);
        assertThat(after.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(after.isResolvedWithoutVerification())
                .as("three citizens voted; this was verified, just not unanimously")
                .isFalse();
    }

    @Test
    @DisplayName("a second sweep, and two concurrent sweeps, settle each issue exactly once")
    void idempotent() throws Exception {
        Issue a = pending(fixtures.user(Role.CITIZEN, null, null));
        Issue b = pending(fixtures.user(Role.CITIZEN, null, null));
        clock.advanceHours(72);

        CompletableFuture<VerificationSweepService.SweepResult> first = CompletableFuture.supplyAsync(sweep::sweep);
        CompletableFuture<VerificationSweepService.SweepResult> second = CompletableFuture.supplyAsync(sweep::sweep);
        int settled = first.get().resolvedWithoutVerification() + second.get().resolvedWithoutVerification();
        assertThat(settled).isEqualTo(2);
        assertThat(sweep.sweep().resolvedWithoutVerification()).isZero();

        for (Issue i : List.of(a, b)) {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM issue_status_history
                    WHERE issue_id = ? AND to_status = 'RESOLVED'
                    """, Integer.class, i.getId())).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE type = 'RESOLVED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a resolved issue closes after seven quiet days, and not before")
    void autoClose() {
        AppUser alice = fixtures.user(Role.CITIZEN, null, null);
        Issue issue = pending(alice);
        verification.vote(issue.getId(), alice.toActor(), Verdict.FIXED, null);

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        assertThat(sweep.sweep().closed()).isZero();

        clock.advance(Duration.ofMinutes(1));
        assertThat(sweep.sweep().closed()).isEqualTo(1);
        Issue after = reload(issue);
        assertThat(after.getStatus()).isEqualTo(IssueStatus.CLOSED);
        assertThat(after.getClosedAt()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("the dashboard publishes both rates per department, null where there is no denominator")
    void departmentRates() throws Exception {
        AppUser alice = fixtures.user(Role.CITIZEN, null, null);
        AppUser bob = fixtures.user(Role.CITIZEN, null, null);

        Issue verified = pending(alice);
        verification.vote(verified.getId(), alice.toActor(), Verdict.FIXED, null);

        Issue rejected = pending(bob);
        verification.vote(rejected.getId(), bob.toActor(), Verdict.NOT_FIXED, null);

        pending(fixtures.user(Role.CITIZEN, null, null));
        clock.advanceHours(72);
        sweep.sweep();

        // Roads: two resolved (one by vote, one by silence), three fixes
        // claimed, one of which citizens rejected.
        mvc.perform(get("/api/v1/dashboard/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.departmentName == 'Roads and Infrastructure')].resolved").value(2))
                .andExpect(jsonPath("$[?(@.departmentName == 'Roads and Infrastructure')].resolvedWithoutVerification").value(1))
                .andExpect(jsonPath("$[?(@.departmentName == 'Roads and Infrastructure')].unverifiedRate").value(0.5))
                .andExpect(jsonPath("$[?(@.departmentName == 'Roads and Infrastructure')].fixesClaimed").value(3))
                .andExpect(jsonPath("$[?(@.departmentName == 'Roads and Infrastructure')].reopened").value(1))
                .andExpect(jsonPath("$[?(@.departmentName == 'Sanitation and Waste')].unverifiedRate")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    // ------------------------------------------------------------------

    private Issue pending(AppUser... reporters) {
        Issue issue = fixtures.issue("POTHOLE", clock.instant(), clock.instant().plusSeconds(3 * 86_400));
        for (AppUser r : reporters) {
            fixtures.report(issue.getId(), r.getId());
        }
        submit(issue);
        return reload(issue);
    }

    private void submit(Issue issue) {
        lifecycle.acknowledge(issue.getId(), supervisor.toActor(), "Seen by the roads desk");
        lifecycle.assign(issue.getId(), supervisor.toActor(), crew.getId(), "Assigned to crew");
        lifecycle.start(issue.getId(), crew.toActor(), "Crew on site");
        lifecycle.submitForVerification(issue.getId(), crew.toActor(),
                "https://example.test/after.jpg", "Pothole filled, compacted and levelled with the road");
    }

    private List<String> notificationTypes() {
        return jdbc.queryForList("SELECT type FROM notifications", String.class);
    }

    private Issue reload(Issue issue) {
        return issues.findById(issue.getId()).orElseThrow();
    }
}
