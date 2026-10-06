package com.civictrack.moderation;

import com.civictrack.IntegrationTestBase;
import com.civictrack.clustering.ClusterOutcome;
import com.civictrack.clustering.ClusterProperties;
import com.civictrack.clustering.ClusteringService;
import com.civictrack.clustering.GeoFixtures;
import com.civictrack.clustering.IngestReportCommand;
import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.support.Fixtures;
import com.civictrack.support.MutableClock;
import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Supervisor moderation, through the HTTP API, against issues built by real
 * ingest -- so the geometry being split and merged is geometry the clustering
 * engine actually produced, not a hand-written fixture that might be
 * self-consistent in a way real clusters are not.
 *
 * <p>The geometric assertions are made against an independent computation:
 * the weighted mean in Java from the stored report rows, and the extent from
 * PostGIS directly. Asserting against {@code ClusterRecomputer} would only show
 * that it agrees with itself.
 */
@AutoConfigureMockMvc
class ModerationIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ClusteringService clustering;
    @Autowired private IssueRepository issues;
    @Autowired private ClusterProperties clusterProps;
    @Autowired private Fixtures fixtures;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private AppUser roadsHead;
    private AppUser waterHead;

    @BeforeEach
    void setUp() {
        fixtures.clearIssues();
        // Real time, because report.created_at is stamped by the database's
        // clock and the split derives a new issue's start from it.
        clock.set(Instant.now());
        roadsHead = fixtures.user(Role.SUPERVISOR, fixtures.departmentId("ROADS"), null);
        waterHead = fixtures.user(Role.SUPERVISOR, fixtures.departmentId("WATER"), null);
    }

    // ------------------------------------------------------------------
    // split
    // ------------------------------------------------------------------

    @Test
    @DisplayName("split moves the chosen reports to a new issue and recomputes both from their members")
    void splitRecomputesBothSides() throws Exception {
        UUID issueId = fourReportCluster();
        List<UUID> members = reportIds(issueId);
        Instant firstBefore = reload(issueId).getFirstReportedAt();
        List<UUID> moving = members.subList(2, 4);

        String body = split(roadsHead, issueId, moving)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value(issueId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID createdId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.relatedIssueId"));

        // Nothing deleted: four reports before, four after, same ids.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports", Integer.class)).isEqualTo(4);
        assertThat(reportIds(createdId)).containsExactlyInAnyOrderElementsOf(moving);
        assertThat(reportIds(issueId)).containsExactlyInAnyOrderElementsOf(members.subList(0, 2));

        for (UUID id : List.of(issueId, createdId)) {
            Issue i = reload(id);
            assertThat(i.getReportCount()).isEqualTo(2);
            assertThat(i.getDistinctReporterCount()).isEqualTo(2);
            assertCentroidIsWeightedMeanOfMembers(id);
            assertExtentIsExact(id);
        }

        // The original's clock never starts later than it did.
        assertThat(reload(issueId).getFirstReportedAt()).isEqualTo(firstBefore);
        Issue created = reload(createdId);
        assertThat(created.getStatus()).isEqualTo(IssueStatus.NEW);
        assertThat(created.getCategoryCode()).isEqualTo("POTHOLE");
        assertThat(created.getPublicRef()).isNotEqualTo(reload(issueId).getPublicRef());

        // Moved reports say a person put them there; the log keeps what the
        // engine originally decided, for the evaluation.
        assertThat(jdbc.queryForList("SELECT cluster_decision FROM reports WHERE issue_id = ?",
                String.class, createdId)).containsOnly("MANUAL");
        String detail = jdbc.queryForObject(
                "SELECT detail::text FROM moderation_actions WHERE action = 'SPLIT' AND issue_id = ? AND related_issue_id = ?",
                String.class, issueId, createdId);
        assertThat(detail).contains("originalDecision").contains(moving.get(0).toString());
    }

    @Test
    @DisplayName("split refuses to take every report, a report from elsewhere, or an issue awaiting verification")
    void splitRefusals() throws Exception {
        UUID issueId = fourReportCluster();
        List<UUID> members = reportIds(issueId);

        split(roadsHead, issueId, members)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://civictrack.example/problems/moderation-refused"));

        split(roadsHead, issueId, List.of(members.get(0), UUID.randomUUID()))
                .andExpect(status().isConflict());

        jdbc.update("UPDATE issues SET status = 'PENDING_VERIFICATION' WHERE id = ?", issueId);
        split(roadsHead, issueId, List.of(members.get(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("verify")));

        // Refused means refused: nothing moved, nothing logged.
        assertThat(reportIds(issueId)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moderation_actions", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a preview reports both sides and writes nothing")
    void previewWritesNothing() throws Exception {
        UUID issueId = fourReportCluster();
        List<UUID> members = reportIds(issueId);
        long versionBefore = reload(issueId).getVersion();

        mvc.perform(post("/api/v1/supervisor/issues/{id}/split/preview", issueId)
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportIdsJson(members.subList(0, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining.reportCount").value(3))
                .andExpect(jsonPath("$.created.reportCount").value(1))
                .andExpect(jsonPath("$.created.extentM").value(0.0));

        assertThat(reload(issueId).getVersion()).isEqualTo(versionBefore);
        assertThat(reportIds(issueId)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moderation_actions", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------
    // merge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("merge moves every report, closes the source as merged, and tells its reporters where they went")
    void mergeFoldsSourceIntoTarget() throws Exception {
        AppUser citizen = fixtures.user(Role.CITIZEN, null, null);
        UUID target = ingest("POTHOLE", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();
        ingest("POTHOLE", GeoFixtures.latOffsetM(6), GeoFixtures.LNG, 12, null);
        // 200 m away: its own issue, the kind of near-duplicate a person merges.
        UUID source = ingest("POTHOLE", GeoFixtures.latOffsetM(200), GeoFixtures.LNG, 5, citizen.getId()).issueId();
        assertThat(source).isNotEqualTo(target);
        jdbc.update("UPDATE issues SET escalation_level = 2 WHERE id = ?", source);
        String targetRef = reload(target).getPublicRef();

        merge(roadsHead, target, source).andExpect(status().isOk());

        assertThat(reportIds(source)).isEmpty();
        assertThat(reportIds(target)).hasSize(3);
        assertCentroidIsWeightedMeanOfMembers(target);
        assertExtentIsExact(target);

        Issue t = reload(target);
        assertThat(t.getReportCount()).isEqualTo(3);
        assertThat(t.getEscalationLevel()).as("a merge must not shed an escalation").isEqualTo(2);

        Issue s = reload(source);
        assertThat(s.getStatus()).isEqualTo(IssueStatus.REJECTED);
        assertThat(s.getMergedIntoId()).isEqualTo(target);
        assertThat(s.getReportCount()).isZero();

        // The old reference still resolves, and says where its reports went.
        mvc.perform(get("/api/v1/public/issues/{id}", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergedIntoId").value(target.toString()))
                .andExpect(jsonPath("$.mergedIntoRef").value(targetRef));

        // Its reporter was told -- in words about joining, not about rejection.
        Map<String, Object> n = jdbc.queryForMap(
                "SELECT type, title, body FROM notifications WHERE user_id = ?", citizen.getId());
        assertThat(n.get("type")).isEqualTo("REJECTED");
        assertThat((String) n.get("title")).startsWith("Joined with another report");
        assertThat((String) n.get("body")).contains(targetRef);
    }

    @Test
    @DisplayName("merge is refused across categories, into itself, and from another department's issue")
    void mergeRefusals() throws Exception {
        UUID pothole = ingest("POTHOLE", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();
        UUID otherPothole = ingest("POTHOLE", GeoFixtures.latOffsetM(200), GeoFixtures.LNG, 8, null).issueId();
        UUID signage = ingest("SIGNAGE", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();
        UUID leak = ingest("WATER_LEAK", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();

        merge(roadsHead, pothole, signage)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Recategorise")));
        merge(roadsHead, pothole, pothole).andExpect(status().isConflict());
        // The source is out of scope: a Roads head cannot take a Water issue's reports.
        merge(roadsHead, pothole, leak).andExpect(status().isForbidden());
        // Nor can a Water head act on two Roads issues.
        merge(waterHead, pothole, otherPothole).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moderation_actions", Integer.class)).isZero();
        assertThat(reload(signage).getStatus()).isEqualTo(IssueStatus.NEW);
    }

    // ------------------------------------------------------------------
    // confirm, recategorise, the queue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("confirm clears the review flag once, and refuses an issue nobody flagged")
    void confirm() throws Exception {
        UUID id = ingest("POTHOLE", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();
        jdbc.update("UPDATE issues SET needs_review = TRUE, review_reason = 'LOW_CONF_MERGE', "
                    + "cluster_confidence = 'LOW' WHERE id = ?", id);

        postJson(roadsHead, "/api/v1/supervisor/issues/{id}/confirm", id, "{}").andExpect(status().isOk());
        Issue i = reload(id);
        assertThat(i.isNeedsReview()).isFalse();
        assertThat(i.getReviewReason()).isNull();

        postJson(roadsHead, "/api/v1/supervisor/issues/{id}/confirm", id, "{}").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moderation_actions WHERE action = 'CONFIRM'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("recategorise moves department, tightens a deadline but never extends one, and stops at assignment")
    void recategorise() throws Exception {
        UUID id = ingest("POTHOLE", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();
        Instant dueBefore = reload(id).getDueAt();

        // POTHOLE (72h) to SIGNAGE (168h): same department, longer allowance.
        recategorise(roadsHead, id, "SIGNAGE").andExpect(status().isOk());
        Issue signage = reload(id);
        assertThat(signage.getCategoryCode()).isEqualTo("SIGNAGE");
        assertThat(signage.getDueAt()).as("a longer SLA must not buy time").isEqualTo(dueBefore);

        // SIGNAGE to WATER_LEAK (24h): another department, shorter allowance.
        recategorise(roadsHead, id, "WATER_LEAK").andExpect(status().isOk());
        Issue leak = reload(id);
        assertThat(leak.getDepartmentId()).isEqualTo(fixtures.departmentId("WATER"));
        assertThat(leak.getDueAt()).isBefore(dueBefore);

        jdbc.update("UPDATE issues SET status = 'ASSIGNED' WHERE id = ?", id);
        recategorise(waterHead, id, "DRAINAGE_BLOCK").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the review queue shows the caller's flagged issues only, with every member position")
    void reviewQueueIsScoped() throws Exception {
        UUID roads = fourReportCluster();
        UUID water = ingest("WATER_LEAK", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();
        ingest("POTHOLE", GeoFixtures.latOffsetM(200), GeoFixtures.LNG, 8, null); // not flagged
        jdbc.update("UPDATE issues SET needs_review = TRUE, review_reason = 'EXTENT_CAP' WHERE id IN (?, ?)",
                roads, water);

        mvc.perform(get("/api/v1/supervisor/review").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(roadsHead)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(roads.toString()))
                .andExpect(jsonPath("$.items[0].reviewReason").value("EXTENT_CAP"))
                .andExpect(jsonPath("$.items[0].points.length()").value(4))
                .andExpect(jsonPath("$.items[0].points[0].reporterId").doesNotExist());

        AppUser citizen = fixtures.user(Role.CITIZEN, null, null);
        mvc.perform(get("/api/v1/supervisor/review").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(citizen)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Four potholes within 13 m of each other, which ingest merges into one issue. */
    private UUID fourReportCluster() {
        UUID id = ingest("POTHOLE", GeoFixtures.LAT, GeoFixtures.LNG, 8, null).issueId();
        assertThat(ingest("POTHOLE", GeoFixtures.latOffsetM(6), GeoFixtures.LNG, 5, null).issueId()).isEqualTo(id);
        assertThat(ingest("POTHOLE", GeoFixtures.LAT, GeoFixtures.lngOffsetM(10), 20, null).issueId()).isEqualTo(id);
        assertThat(ingest("POTHOLE", GeoFixtures.latOffsetM(8), GeoFixtures.lngOffsetM(8), 10, null).issueId())
                .isEqualTo(id);
        return id;
    }

    private ClusterOutcome ingest(String category, double lat, double lng, double accuracy, UUID reporterId) {
        return clustering.ingest(new IngestReportCommand(category, lat, lng, accuracy, false,
                "test report", "Test Road", null, "https://example.test/p.jpg", null,
                reporterId, reporterId == null ? "device-" + UUID.randomUUID() : null));
    }

    /** The weighted mean, computed here from the stored rows, not by the code under test. */
    private void assertCentroidIsWeightedMeanOfMembers(UUID issueId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ST_Y(location) AS lat, ST_X(location) AS lng, gps_accuracy_m AS acc FROM reports WHERE issue_id = ?",
                issueId);
        double sw = 0, sx = 0, sy = 0;
        for (Map<String, Object> r : rows) {
            double acc = Math.max(((Number) r.get("acc")).doubleValue(), clusterProps.minAccuracyM());
            double w = 1.0 / (acc * acc);
            sw += w;
            sx += w * ((Number) r.get("lng")).doubleValue();
            sy += w * ((Number) r.get("lat")).doubleValue();
        }
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT ST_Y(centroid) AS lat, ST_X(centroid) AS lng, sum_w FROM issues WHERE id = ?", issueId);
        assertThat(((Number) c.get("lat")).doubleValue()).isCloseTo(sy / sw, within(1e-9));
        assertThat(((Number) c.get("lng")).doubleValue()).isCloseTo(sx / sw, within(1e-9));
        assertThat(((Number) c.get("sum_w")).doubleValue()).isCloseTo(sw, within(1e-12));
    }

    /** The stored extent is the exact one -- not DD-001's upper bound, which ingest stores. */
    private void assertExtentIsExact(UUID issueId) {
        Double exact = jdbc.queryForObject("""
                SELECT COALESCE(MAX(ST_Distance(r.location::geography, i.centroid::geography)), 0)
                FROM reports r JOIN issues i ON i.id = r.issue_id WHERE i.id = ?
                """, Double.class, issueId);
        assertThat(reload(issueId).getMaxMemberDistM()).isCloseTo(exact, within(0.01));
    }

    private List<UUID> reportIds(UUID issueId) {
        return jdbc.queryForList("SELECT id FROM reports WHERE issue_id = ? ORDER BY created_at, id",
                UUID.class, issueId);
    }

    private Issue reload(UUID id) {
        return issues.findById(id).orElseThrow();
    }

    private ResultActions split(AppUser who, UUID issueId, List<UUID> ids) throws Exception {
        return postJson(who, "/api/v1/supervisor/issues/{id}/split", issueId, reportIdsJson(ids));
    }

    private ResultActions merge(AppUser who, UUID target, UUID source) throws Exception {
        return postJson(who, "/api/v1/supervisor/issues/{id}/merge", target,
                "{\"sourceIssueId\":\"" + source + "\"}");
    }

    private ResultActions recategorise(AppUser who, UUID id, String code) throws Exception {
        return postJson(who, "/api/v1/supervisor/issues/{id}/recategorise", id,
                "{\"categoryCode\":\"" + code + "\"}");
    }

    private ResultActions postJson(AppUser who, String path, UUID id, String json) throws Exception {
        return mvc.perform(post(path, id)
                .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String reportIdsJson(List<UUID> ids) {
        return ids.stream().map(u -> "\"" + u + "\"")
                .collect(Collectors.joining(",", "{\"reportIds\":[", "]}"));
    }
}
