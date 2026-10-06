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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 4.3 over HTTP: who may vote, what a vote does, and who is told.
 *
 * <p>Issues reach PENDING_VERIFICATION by real transitions -- acknowledge,
 * assign, start, submit -- never by writing the status, for the reason
 * {@code Fixtures} gives.
 */
@AutoConfigureMockMvc
class VerificationLoopIT extends IntegrationTestBase {

    private static final String PROOF = "https://example.test/after.jpg";
    private static final String NOTE = "Pothole filled, compacted and levelled with the road";

    @Autowired private MockMvc mvc;
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

    // ------------------------------------------------------------------
    // what a vote does
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a sole reporter's FIXED resolves the issue, as SYSTEM, with verification")
    void soleReporterConfirms() throws Exception {
        AppUser alice = citizen();
        Issue issue = pending(alice);

        vote(alice, issue, "FIXED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.outcome").value("RESOLVED"))
                .andExpect(jsonPath("$.confirmations").value(1))
                .andExpect(jsonPath("$.required").value(1));

        Issue after = reload(issue);
        assertThat(after.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(after.isResolvedWithoutVerification()).isFalse();
        assertThat(after.getResolvedAt()).isEqualTo(clock.instant());
        assertThat(jdbc.queryForObject("""
                SELECT actor_role FROM issue_status_history
                WHERE issue_id = ? AND to_status = 'RESOLVED'
                """, String.class, issue.getId()))
                .as("the citizen votes, but the system is what resolves")
                .isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("one NOT_FIXED reopens at once: escalation +1, reopen count +1, the clock resumes")
    void rejectionReopens() throws Exception {
        AppUser alice = citizen();
        Issue issue = pending(alice);
        clock.advanceHours(5);

        vote(alice, issue, "NOT_FIXED", "The hole is still there by the bus stop")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REOPENED"))
                .andExpect(jsonPath("$.outcome").value("REOPENED"));

        Issue after = reload(issue);
        assertThat(after.getStatus()).isEqualTo(IssueStatus.REOPENED);
        assertThat(after.getEscalationLevel()).isEqualTo(1);
        assertThat(after.getReopenCount()).isEqualTo(1);
        assertThat(after.getClockPausedAt()).as("the SLA clock runs again").isNull();
        assertThat(after.getPausedSeconds()).as("the five hours waiting are credited")
                .isEqualTo(5 * 3600);
        assertThat(jdbc.queryForObject(
                "SELECT comment FROM verifications WHERE issue_id = ?", String.class, issue.getId()))
                .isEqualTo("The hole is still there by the bus stop");
    }

    @Test
    @DisplayName("three reporters need two confirmations: the first leaves it pending")
    void quorumOfTwo() throws Exception {
        AppUser alice = citizen();
        AppUser bob = citizen();
        AppUser carol = citizen();
        Issue issue = pending(alice, bob, carol);

        vote(alice, issue, "FIXED")
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.outcome").value("STILL_OPEN"))
                .andExpect(jsonPath("$.required").value(2));

        vote(bob, issue, "FIXED").andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    @DisplayName("a tie reopens: rejections are checked before the quorum")
    void tieReopens() throws Exception {
        AppUser alice = citizen();
        AppUser bob = citizen();
        AppUser carol = citizen();
        Issue issue = pending(alice, bob, carol);

        vote(alice, issue, "FIXED").andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
        vote(bob, issue, "NOT_FIXED").andExpect(jsonPath("$.status").value("REOPENED"));
    }

    // ------------------------------------------------------------------
    // who may vote
    // ------------------------------------------------------------------

    @Test
    @DisplayName("anonymous is 401; a citizen who did not report, and staff, are 403")
    void onlyReportingCitizensVote() throws Exception {
        AppUser alice = citizen();
        AppUser stranger = citizen();
        Issue issue = pending(alice);
        // A crew member who also reported it: the case DD-061 closes.
        fixtures.report(issue.getId(), crew.getId());

        mvc.perform(post("/api/v1/issues/{id}/verify", issue.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"FIXED\"}"))
                .andExpect(status().isUnauthorized());
        vote(stranger, issue, "FIXED").andExpect(status().isForbidden());
        vote(crew, issue, "FIXED").andExpect(status().isForbidden());

        assertThat(reload(issue).getStatus()).isEqualTo(IssueStatus.PENDING_VERIFICATION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verifications", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a second vote is 409 carrying the first verdict, and writes nothing")
    void oneVoteEach() throws Exception {
        AppUser alice = citizen();
        AppUser bob = citizen();
        AppUser carol = citizen();
        Issue issue = pending(alice, bob, carol);

        vote(alice, issue, "FIXED").andExpect(status().isOk());
        vote(alice, issue, "NOT_FIXED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://civictrack.example/problems/already-verified"))
                .andExpect(jsonPath("$.verdict").value("FIXED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verifications", Integer.class)).isEqualTo(1);
        assertThat(reload(issue).getStatus()).isEqualTo(IssueStatus.PENDING_VERIFICATION);
    }

    @Test
    @DisplayName("voting on an issue that is not waiting for verification is 409 naming its status")
    void notOpen() throws Exception {
        AppUser alice = citizen();
        Issue issue = fixtures.issue("POTHOLE", clock.instant(), clock.instant().plusSeconds(86_400));
        fixtures.report(issue.getId(), alice.getId());

        vote(alice, issue, "FIXED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    @DisplayName("simultaneous votes serialise on the issue: the third FIXED always settles it")
    void concurrentVotesSerialise() throws Exception {
        // Repeated, with six voters at once, because a race that loses one run
        // in ten is a race a single two-thread run will usually miss. That is
        // not hypothetical: the first version of this test raced two votes,
        // and it stayed green with the row lock removed.
        for (int attempt = 0; attempt < 5; attempt++) {
            List<AppUser> six = java.util.stream.Stream.generate(this::citizen).limit(6).toList();
            Issue issue = pending(six.toArray(AppUser[]::new));

            ExecutorService pool = Executors.newFixedThreadPool(six.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = six.stream()
                    .<Callable<Integer>>map(u -> () -> {
                        go.await();
                        return vote(u, issue, "FIXED").andReturn().getResponse().getStatus();
                    })
                    .map(pool::submit)
                    .toList();
            go.countDown();
            List<Integer> codes = new java.util.ArrayList<>();
            for (Future<Integer> r : results) {
                codes.add(r.get());
            }
            pool.shutdown();

            // Three votes reach quorum and settle it; whoever arrives after that
            // is told verification has closed. Without the lock, votes that
            // each miss the others' leave it pending with every vote counted,
            // or two of them both resolve it and one fails on the version.
            assertThat(codes).as("attempt %d", attempt).containsOnly(200, 409);
            assertThat(codes.stream().filter(c -> c == 200).count()).as("attempt %d", attempt)
                    .isGreaterThanOrEqualTo(3);
            assertThat(reload(issue).getStatus()).as("attempt %d", attempt)
                    .isEqualTo(IssueStatus.RESOLVED);
        }
    }

    // ------------------------------------------------------------------
    // rounds (DD-059)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a resubmitted fix is a new round: earlier voters may vote again and old votes do not count")
    void secondRound() throws Exception {
        AppUser alice = citizen();
        AppUser bob = citizen();
        AppUser carol = citizen();
        Issue issue = pending(alice, bob, carol);

        vote(alice, issue, "NOT_FIXED").andExpect(jsonPath("$.status").value("REOPENED"));

        lifecycle.start(issue.getId(), crew.toActor(), "Back on site");
        lifecycle.submitForVerification(issue.getId(), crew.toActor(), PROOF, NOTE);
        assertThat(reload(issue).getVerificationRound()).isEqualTo(2);

        // Round one's NOT_FIXED is not held against round two: one FIXED of two
        // required leaves it pending, where counting both rounds would have tied
        // and reopened it.
        vote(alice, issue, "FIXED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.rejections").value(0));
        vote(bob, issue, "FIXED").andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    // ------------------------------------------------------------------
    // the screen's reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the verify view states eligibility, the tally and the silence deadline; awaiting lists it")
    void viewAndAwaiting() throws Exception {
        AppUser alice = citizen();
        AppUser stranger = citizen();
        Issue issue = pending(alice);

        mvc.perform(get("/api/v1/me/verifications").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].issue.id").value(issue.getId().toString()));

        mvc.perform(get("/api/v1/me/verifications/{id}", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.myVerdict").doesNotExist())
                .andExpect(jsonPath("$.issue.resolutionPhotoUrl").value(PROOF))
                .andExpect(jsonPath("$.beforePhotoUrl").exists())
                .andExpect(jsonPath("$.silenceDeadline").value(clock.instant().plusSeconds(72 * 3600).toString()));

        mvc.perform(get("/api/v1/me/verifications/{id}", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));

        vote(alice, issue, "FIXED");

        // Returning after the vote shows what was said, not an error.
        mvc.perform(get("/api/v1/me/verifications/{id}", issue.getId())
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(jsonPath("$.myVerdict").value("FIXED"))
                .andExpect(jsonPath("$.issue.status").value("RESOLVED"))
                .andExpect(jsonPath("$.silenceDeadline").doesNotExist());
        mvc.perform(get("/api/v1/me/verifications").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------
    // notifications
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a submitted fix asks each registered citizen reporter once -- not anonymous devices, not staff")
    void verifyRequestAudience() throws Exception {
        AppUser alice = citizen();
        AppUser bob = citizen();
        Issue issue = issueReportedBy(alice, bob);
        fixtures.report(issue.getId(), alice.getId());   // alice twice: still one message
        fixtures.report(issue.getId(), null);            // anonymous: nobody to tell
        fixtures.report(issue.getId(), supervisor.getId()); // staff: cannot vote, not asked
        submit(issue);

        assertThat(recipients("VERIFY_REQUESTED"))
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    @Test
    @DisplayName("a rejected fix tells the reporters and the crew it was assigned to")
    void reopenTellsTheCrew() throws Exception {
        AppUser alice = citizen();
        Issue issue = pending(alice);

        vote(alice, issue, "NOT_FIXED");

        assertThat(recipients("REOPENED")).containsExactlyInAnyOrder(alice.getId(), crew.getId());
    }

    @Test
    @DisplayName("notifications are the caller's own; mark-read is scoped and the unread count follows")
    void notificationApi() throws Exception {
        AppUser alice = citizen();
        AppUser bob = citizen();
        Issue issue = pending(alice);
        pending(bob);

        mvc.perform(get("/api/v1/me/notifications").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].type").value("VERIFY_REQUESTED"))
                .andExpect(jsonPath("$.items[0].issueId").value(issue.getId().toString()));

        long bobsId = jdbc.queryForObject(
                "SELECT id FROM notifications WHERE user_id = ?", Long.class, bob.getId());
        // Alice naming Bob's notification changes nothing.
        mvc.perform(post("/api/v1/me/notifications/read")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + bobsId + "]}"))
                .andExpect(jsonPath("$.unread").value(1));
        mvc.perform(get("/api/v1/me/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(bob)))
                .andExpect(jsonPath("$.unread").value(1));

        mvc.perform(post("/api/v1/me/notifications/read")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));

        mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------

    private AppUser citizen() {
        return fixtures.user(Role.CITIZEN, null, null);
    }

    private Issue issueReportedBy(AppUser... reporters) {
        Issue issue = fixtures.issue("POTHOLE", clock.instant(), clock.instant().plusSeconds(3 * 86_400));
        for (AppUser r : reporters) {
            fixtures.report(issue.getId(), r.getId());
        }
        return issue;
    }

    private Issue pending(AppUser... reporters) {
        Issue issue = issueReportedBy(reporters);
        submit(issue);
        return reload(issue);
    }

    private void submit(Issue issue) {
        lifecycle.acknowledge(issue.getId(), supervisor.toActor(), "Seen by the roads desk");
        lifecycle.assign(issue.getId(), supervisor.toActor(), crew.getId(), "Assigned to crew");
        lifecycle.start(issue.getId(), crew.toActor(), "Crew on site");
        lifecycle.submitForVerification(issue.getId(), crew.toActor(), PROOF, NOTE);
    }

    private ResultActions vote(AppUser who, Issue issue, String verdict) throws Exception {
        return vote(who, issue, verdict, null);
    }

    private ResultActions vote(AppUser who, Issue issue, String verdict, String reason) throws Exception {
        String body = reason == null
                ? "{\"verdict\":\"" + verdict + "\"}"
                : "{\"verdict\":\"" + verdict + "\",\"reason\":\"" + reason + "\"}";
        return mvc.perform(post("/api/v1/issues/{id}/verify", issue.getId())
                .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<UUID> recipients(String type) {
        return jdbc.queryForList("SELECT user_id FROM notifications WHERE type = ?", UUID.class, type);
    }

    private Issue reload(Issue issue) {
        return issues.findById(issue.getId()).orElseThrow();
    }
}
