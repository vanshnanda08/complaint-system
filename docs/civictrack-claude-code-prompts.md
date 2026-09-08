1. What the 2.0 multiplies, and what a defensible sweep needs
What it multiplies. The cap is max_extent_multiplier × merge_radius_m, so for POTHOLE (25 m) it is 50 m. It bounds issues.max_member_dist_m — the geodesic distance from the centroid to the furthest member report. That is a cluster radius, not a diameter, so a POTHOLE cluster may legitimately span 100 m end to end while satisfying a 50 m cap. Worth being precise about, because "50 m cap" and "50 m wide" differ by a factor of two and the sweep plot axis has to say which.

At the boundary. Merging moves the centroid, which changes every existing member's distance, so an exact post-merge extent is an O(n) rescan — the thing the running sums exist to avoid. Instead I compute an O(1) upper bound. If the centroid shifts by δ, then by the triangle inequality no existing member can be further than old_max + δ from the new centroid, and the arriving report sits at d_new. So:


projected_extent ≤ max(old_max_member_dist_m + δ, d_new)
If that exceeds the cap the merge is refused: a new issue is created, cluster_decision = SPLIT_EXTENT_CAPPED, needs_review = true. The bound is conservative — it can overestimate, so the cap can refuse a merge that an exact recomputation would have allowed. That errs toward splitting and flagging for a human, which is the safe direction, but it means a measured "cap bound here" rate is a slight overcount and you should say so rather than let a reviewer find it.

What a sweep needs. Input: a labelled corpus whose ground truth contains linear defects specifically — chaining only occurs when reports repeatedly arrive within R_eff of a moving centroid — with point defects as a control arm, generated across a range of report densities and inter-report spacings. Because single-pass assignment is order-dependent, each corpus must be run under several arrival shuffles and the spread reported, not just a mean.

Measure, per multiplier in roughly 1.25 → 4.0 plus a disabled (∞) arm: clustering precision/recall/F1 against ground truth; the distribution of final max_member_dist_m; the rate of SPLIT_EXTENT_CAPPED; and fragmentation (issues per ground-truth defect).

The result that justifies moving off 2.0: F1 peaking materially away from 2.0. The result that justifies deleting the feature is the ∞ arm showing no cluster ever exceeding 2 × R_cat at realistic densities — the cap never binds, so it is machinery defending against a hypothetical. A third outcome is live: the cap helping on linear corpora while fragmenting point corpora, which argues for per-category values, and the schema already stores it per category.

One trap to avoid: do not measure "merges refused" as if lower extent were the goal. Refusing every merge drives extent to zero and F1 to the floor. F1 against ground truth is the only metric that can move this number.

2. Fixture check
Confirmed, and it matters more than the ward grid alone:

Point	→ ward boundary	Verdict
(30.900965, 75.857277) demo	107 m	Unusable as a fixture
(30.9300, 75.8200)	3215 m	Ward-interior
No phase-2 test uses the demo coordinate. I did not have to change an existing test — phase 1's WardContainmentIT already used (30.9300, 75.8200). GeoFactoryTest does reference the demo coordinate, but only to assert x/y ordering with no ward lookup, so it is unaffected.

I did tighten my intended fixture while checking. My first choice, (30.9310, 75.8210), is ward-interior but lands exactly on a ST_SnapToGrid(0.002) midpoint — the worst spot for advisory-lock cell determinism. (30.9300, 75.8200) is a cell centre, ~95 m from the nearest cell edge in longitude and ~111 m in latitude. That is the phase-2 canonical fixture.


8-week plan. Month 1 = phases 0-5. Month 2 = phases 6-9.

**How to use**

1. Create the repo, copy `civictrack-java-blueprint.md` and
   `civictrack-project-report.md` into `docs/`, then run `claude`.
2. Paste Prompt 0 first, then one phase at a time, in order.
3. Do not start a phase with a red test suite from the previous one.

**Deviations from the original blueprint.** This pack deliberately changes six
things. Each is marked `[CHANGE]` where it appears, and the reasoning goes in
`docs/DESIGN-DECISIONS.md`, which Prompt 0 tells Claude Code to write. Keep
that file. It is where your report's design-rationale section comes from.

---

## Prompt 0 — Kickoff and ground rules

```
Read docs/civictrack-java-blueprint.md and docs/civictrack-project-report.md
in full before writing any code. They are the specification for this project.

Context: 2-month university semester project. I demo one month of progress at
the 4-week mark. You are building backend core plus a real, thin frontend.

Stack (fixed, do not substitute):
- Java 21, Spring Boot 3.5, Maven
- PostgreSQL 16 + PostGIS 3.4, schema owned by Flyway, Hibernate ddl-auto: validate
- hibernate-spatial + JTS
- Spring Security with spring-boot-starter-oauth2-resource-server for JWT.
  NOT jjwt and NOT a hand-written filter. Spring Security 6 gives us JwtDecoder
  and JwtAuthenticationConverter out of the box; hand-rolled auth filters are
  where security bugs live.
- Actuator + Micrometer. We need p50/p95/p99 ingest latency for the week-7
  evaluation, so instrument from day one rather than building a timing
  harness later.
- Testcontainers (postgis/postgis:16-3.4). Never H2.
- Next.js App Router + React + Tailwind + Leaflet
- Cloudinary for images

Deliberately NOT using MapStruct. We have ~15 DTOs. Java 21 records with
static from(Entity) factories do the job with no annotation-processor
ordering problems. Lombok stays, for entities only.

Non-negotiable configuration, set these in phase 1 and do not change them:
- spring.jpa.open-in-view=false. It defaults to TRUE, which holds a DB
  connection for the whole request. With Hikari capped at 8 on free-tier
  hosting, the 20-thread concurrency test in phase 2 will exhaust the pool
  and present as a phantom locking bug.
- Dockerfile JVM flags: -XX:MaxRAMPercentage=75. Default heap sizing on a
  512MB container causes random OOM kills.
- Enable Spring Boot CDS (Class Data Sharing) to cut JVM startup time. Cold
  start is our largest demo risk.
- Use Spring 6's built-in ProblemDetail for RFC 7807 responses. No library.
- You may enable spring.threads.virtual.enabled, but do not describe it as a
  performance win anywhere in docs or comments. Hikari max 8 is the
  bottleneck, not the thread model.

Monorepo: /backend and /frontend. Backend packages by FEATURE not layer:
com.civictrack.{report, clustering, issue, sla, verification, user, ward,
category, dashboard, seed, common}

Standing rules for the whole project:
1. Every tunable number lives in the categories table, not in code.
2. Every distance uses ST_Distance(...::geography). Never planar.
3. Exactly ONE class constructs a JTS Point from lat/lng: GeoFactory.
   Everything else calls it. This prevents the lat/lng swap bug.
4. Tests alongside features, not after.
5. At the end of each phase, print what I should verify manually.

The specification in docs/ has six known defects that we are fixing. They are
listed below and marked [CHANGE] in later prompts. Create
docs/DESIGN-DECISIONS.md now and record each one as: the defect, why it
matters, the fix we chose, the alternative we rejected. Append to this file
whenever we make a non-obvious call.

  [CHANGE 1] Unbounded cluster drift. Leader clustering with a moving
  centroid can chain along a road: each merge is locally valid but the
  cluster walks far from its origin. Fix: bound cluster extent.

  [CHANGE 2] The low-confidence band always merges. Wrong for safety-critical
  categories where a hidden duplicate is a hazard. Fix: make the band's
  action per-category configuration.

  [CHANGE 3] FOR UPDATE with ORDER BY + LIMIT locks a row whose distance may
  have changed between ordering and locking. Fix: re-verify after locking.

  [CHANGE 4] Priority includes an age term but is only recomputed on merge,
  so single-report issues never age into higher priority. Fix: recompute in
  the scheduled sweep.

  [CHANGE 5] The escalation ladder is described as a parent_department_id
  tree walk, but level 3 is a ward officer, which is not in that tree. Also
  reopen increments escalation_level with no cap. Fix: explicit ordered
  resolver, capped at 4.

  [CHANGE 6] An issue reported only by anonymous users can never be verified,
  so it always auto-resolves at the 72h timeout. Not fixable without opening
  an abuse vector. Fix: measure it and publish it.

Start by scaffolding: Maven project, docker-compose with postgis:16-3.4,
Flyway V1 baseline, /actuator/health. Confirm the app boots and connects to
PostGIS before anything else.
```

---

## Phase 1 — Schema and spatial round-trip (Week 1)

```
Phase 1: schema and spatial round-trip.

Implement Flyway V1__baseline.sql per section 2 of the blueprint. Eleven
tables: users, departments, wards, categories, reports, issues,
issue_status_history, escalation_events, verifications, notifications,
shedlock.

Critical details:
- CREATE EXTENSION postgis AND CREATE EXTENSION btree_gist.
- reports.location and issues.centroid are geometry(Point, 4326).
- wards.boundary is geometry(MultiPolygon, 4326).
- escalation_events: UNIQUE(issue_id, level). That constraint IS the
  idempotency guarantee. Never drop it.
- verifications: UNIQUE(issue_id, citizen_id).
- issues carries running sums: sum_w, sum_wx, sum_wy (double precision),
  report_count, distinct_reporter_count.

[CHANGE 1] Add to issues:
  max_member_dist_m double precision not null default 0
Distance from the centroid to the furthest member report. Bounds cluster
extent, maintained incrementally.

[CHANGE 2] Add to categories:
  low_conf_action varchar(16) not null default 'MERGE_FLAG'
    check (low_conf_action in ('MERGE_FLAG','SPLIT_FLAG'))
  max_extent_multiplier numeric not null default 2.0

Indexing: rather than a bare GiST on the geography cast, use a composite so
the category and ward predicates are served by the same index:
  CREATE INDEX idx_issues_cluster ON issues
    USING GIST (category_code, ward_id, (centroid::geography))
    WHERE status NOT IN ('CLOSED','REJECTED');
btree_gist is what makes the scalar columns usable inside a GiST index.
Also a partial B-tree on (status, due_at) for the SLA sweep.

V2 seeds the 10 categories with merge radii and SLA hours from the blueprint
table. Set low_conf_action = 'SPLIT_FLAG' for OPEN_MANHOLE and WATER_LEAK
(safety and property damage: a hidden duplicate is worse than a duplicate
ticket). All others MERGE_FLAG.

V3 seeds 4-6 approximate Ludhiana ward polygons, a department tree
(Sanitation, Roads, Water, Streetlighting under a Municipal Corporation
parent), and one user per role. Comment that ward boundaries are approximate
because real municipal data is not publicly available.

Then JPA entities with hibernate-spatial, and GeoFactory.

Then POST /api/reports that ALWAYS creates a new issue. No clustering yet.
Resolve ward via ST_Contains, save report, create issue, return a ticket ref.

Tests:
- A known Ludhiana coordinate falls inside the expected ward. This is the
  lat/lng swap canary and it must exist before anything else.
- A Point saved and re-read returns identical coordinates.
- EXPLAIN assertion that the composite GiST index is used.

No clustering, no auth, no frontend in this phase.
```

---

## Phase 2 — Clustering engine + seed generator (Week 2)

```
Phase 2: the incremental geo-clustering engine. This is the technical core
and the part I am examined on. Follow section 3 of the blueprint, with the
changes below.

ClusteringService, one transaction:

1. Reject if gps_accuracy_m > 150. Return 400 asking for a manual pin.
2. Advisory lock:
   SELECT pg_advisory_xact_lock(hashtext(:categoryCode || ':' ||
     ST_AsText(ST_SnapToGrid(ST_SetSRID(ST_MakePoint(:lng,:lat),4326), 0.002))))
3. Candidate query, section 3.7: same category_code, same ward_id, status in
   the open set OR RESOLVED within the reopen window, ST_DWithin on the
   geography cast, ORDER BY distance, LIMIT 5, FOR UPDATE OF i.
   searchRadiusM = 1.5 * R_cat + 120.

   [CHANGE 3] Postgres evaluates ORDER BY before acquiring row locks, so a
   concurrent update in an adjacent spatial cell can move a centroid between
   ordering and locking. After the locks are held, RE-READ each candidate's
   centroid and sum_w and recompute the distance from the fresh values. Base
   the band decision only on the re-read values. Comment this; it is not
   obvious to a later reader.

4. For the nearest candidate:
     w_new       = 1 / max(accuracy_new, 3)^2
     sigma_issue = 1 / sqrt(sum_w)
     R_eff       = R_cat + 0.5 * min(accuracy_new, 60) + 0.5 * sigma_issue

5. [CHANGE 1] Extent guard, evaluated BEFORE the band decision. Compute the
   centroid that would result from the merge, then:
     projected_extent = max(max_member_dist_m + centroid_shift, d_new)
   If projected_extent > max_extent_multiplier * R_cat, do not merge. Create
   a new issue with needs_review = true and cluster_decision =
   'NEW_ISSUE_EXTENT_CAP'.

   Why: leader clustering with a moving centroid can chain along a linear
   defect. Each merge is locally valid and the cluster walks hundreds of
   metres from where it started. The cap makes cluster extent a bounded,
   stated property rather than an emergent one.

6. Bands on the re-read geodesic distance d:
     d <= R_eff              -> MERGED
     R_eff < d <= 1.5*R_eff  -> low-confidence band, see CHANGE 2
     d > 1.5*R_eff           -> NEW_ISSUE

   [CHANGE 2] In the low-confidence band, branch on category.low_conf_action:
     MERGE_FLAG -> merge, needs_review = true, decision 'MERGED_LOW_CONF'
     SPLIT_FLAG -> new issue, needs_review = true, decision 'NEW_LOW_CONF'
   Safety-critical categories take SPLIT_FLAG because a hidden duplicate open
   manhole is a hazard, whereas a duplicate ticket is only noise. Either way
   the result lands in the supervisor review queue.

7. On merge: update sum_w, sum_wx, sum_wy in O(1). New centroid =
   (sum_wx/sum_w, sum_wy/sum_w). Update max_member_dist_m. Never rescan
   member reports.
8. Increment report_count and distinct_reporter_count. Recompute priority.
   Tighten due_at if priority rose; never extend it.
9. Persist the full clustering audit on the report row: cluster_decision,
   cluster_distance_m, effective_radius_used, matched_issue_id,
   projected_extent_m.
10. Reopen-on-recurrence: nearest candidate RESOLVED and within the reopen
    window (14 days, 3 for STRAY_ANIMAL) -> attach, status REOPENED,
    escalation_level = min(4, max(1, level+1))  [CHANGE 5: the cap],
    SLA halved.

Priority score:
  severity_weight + 12*log2(1 + distinct_reporters) + 0.15*age_hours
  + 20*escalation_level + 15*reopen_count
Bands: <25 LOW, 25-49 MEDIUM, 50-79 HIGH, >=80 CRITICAL.
Count DISTINCT reporters, never raw report count.

API response must tell the citizen plainly: new issue or merged, ticket ref,
current report count, cluster decision, distance to cluster. That feedback is
the product, not a debug field.

Required tests, all green before moving on:
- concurrentReportsProduceOneIssue: 20 threads, CountDownLatch, identical
  reports. Assert exactly ONE issue with report_count 20. Highest-value test
  in the project. Expect it to fail against the first implementation.
- Parameterised version for N in {2,5,10,20,50}.
- 15 m apart, same category -> merge. 200 m apart -> no merge.
- Same coordinate, different category -> two issues.
- Opposite sides of a ward boundary -> never merge.
- A report in the 1.0-1.5 R_eff band with MERGE_FLAG merges and is flagged.
- The same geometry with a SPLIT_FLAG category creates a second issue.
- driftIsBounded: feed 30 reports each 20 m further east than the last.
  Assert cluster extent never exceeds the cap and that more than one issue
  results.
- Centroid after N merges equals an independently computed weighted mean.
- A 60 m-accuracy report moves the centroid far less than a 5 m one.
- EXPLAIN assertion: the candidate query uses an index scan.

ALSO IN THIS PHASE — the seed data generator. Do not defer it.

A CommandLineRunner behind a 'seed' profile generating a realistic corpus:
~2000 reports over the last 90 days across the seeded wards, mixing genuine
clusters (2-15 reports around a point with plausible GPS scatter),
singletons, and near-miss pairs sitting in the low-confidence band. Include
resolved, breached and reopened issues so the dashboard looks alive.

Deterministic from a seed value, with corpus size as a parameter, because I
need 1k / 10k / 50k runs for the latency evaluation and the same corpus every
time for the clustering F1 evaluation.

Also emit a ground-truth label file: for each generated report, the ID of the
real-world defect it was generated from. That file is what I measure
clustering precision, recall and F1 against in week 7. Generating labels now
costs nothing. Hand-labelling later costs two days.
```

---

## Phase 3 — State machine, SLA, escalation, auth (Week 3)

```
Phase 3: issue lifecycle and escalation. Sections 4 and 5 of the blueprint.

Nine states: NEW, ACKNOWLEDGED, ASSIGNED, IN_PROGRESS, PENDING_VERIFICATION,
RESOLVED, REOPENED, CLOSED, REJECTED.

Implement the transition table declaratively as a TransitionPolicy map, not
scattered if-statements. Every transition checks role AND guard. Write an
immutable issue_status_history row on every change.

The structural rule: NO transition to RESOLVED or CLOSED accepts a STAFF
actor. Staff reach PENDING_VERIFICATION only, and only with a proof photo and
a note of 20+ characters. Write a test that iterates every state, attempts a
STAFF transition to RESOLVED, and expects rejection every time. That test is
the enforcement, not a code review convention.

Escalation is NOT a state. It is escalation_level:int on the issue.

SLA:
  due_at = first_reported_at + sla_hours(category, priority)
  effective_deadline = due_at + paused_seconds
  breached = now() > effective_deadline AND status.clockRunning()
Clock PAUSES in PENDING_VERIFICATION. Priority upgrades recompute and shorten
the deadline. Downgrades never extend it.

[CHANGE 5] The escalation ladder is NOT a pure parent_department_id walk.
Level 3 is a ward officer, who lives in the wards table. Implement it as an
ordered list of resolver strategies:
  level 1 -> department.head_user_id
  level 2 -> parent department's head_user_id (walk parent_department_id once)
  level 3 -> ward officer, looked up from issues.ward_id
  level 4 -> admin / commissioner. TERMINAL.
Cap escalation_level at 4 everywhere it is incremented, including
reopen-on-recurrence. Level 4 issues appear in a "chronic breach" list rather
than escalating further.

Escalation job: @Scheduled every 5 minutes under ShedLock. Select breached
issues FOR UPDATE SKIP LOCKED in batches. Per issue: resolve next owner,
insert escalation_events row, advance escalation_level with a conditional
compare-and-swap. Re-arm due_at = now() + remaining/2, floored at 2h.

[CHANGE 4] The same sweep must also recompute priority for ALL open issues,
not only breached ones. Priority includes an age term, so an issue that never
receives a second report freezes at its creation-time score and never rises
in the queue. That is backwards: neglected issues are exactly the ones that
should climb. Recompute score and band, tighten due_at if the band rose,
batch it into as few UPDATEs as possible.

Auth in this phase: JWT, 15-minute access tokens, 30-day refresh tokens,
BCrypt cost 12, four roles. Two authorisation layers: @PreAuthorize for
"can this kind of user do this kind of thing", plus a guard bean for "is this
specific user allowed to touch this specific issue" checking department and
ward scope. Skip password reset and refresh token rotation; they are cut.

Required tests:
- Running the escalation job twice produces exactly one escalation_events row.
- Two concurrent job executions produce one escalation event.
- The clock does not advance during PENDING_VERIFICATION.
- Escalation stops at level 4 and does not overflow.
- An issue at level 3 resolves to the ward officer, not a department head.
- A sanitation STAFF user cannot act on a roads issue.
- An untouched issue's priority rises over simulated time.
- Every illegal transition returns 409 or 403.

Add application-demo.yml: 3-minute SLAs, 20-second sweep, so escalation is
visible live during a presentation.
```


> Amended from `civictrack-claude-code-prompts.md`. The original spec is
> unchanged below; five additions marked **[ADDED]** come from what phase 2
> actually produced. Paste the whole thing.

```
Phase 3: issue lifecycle and escalation. Sections 4 and 5 of the blueprint.

Nine states: NEW, ACKNOWLEDGED, ASSIGNED, IN_PROGRESS, PENDING_VERIFICATION,
RESOLVED, REOPENED, CLOSED, REJECTED.

Implement the transition table declaratively as a TransitionPolicy map, not
scattered if-statements. Every transition checks role AND guard. Write an
immutable issue_status_history row on every change.

[ADDED] Phase 2 already built a minimal IssueStatusService as the single
writer, with Issue.setStatus package-private so the compiler enforces it.
Extend that service. Do NOT create a second write path alongside it, and do
not widen setStatus visibility. If the existing shape genuinely cannot carry
the TransitionPolicy guards, say why before changing it rather than working
around it.

The structural rule: NO transition to RESOLVED or CLOSED accepts a STAFF
actor. Staff reach PENDING_VERIFICATION only, and only with a proof photo and
a note of 20+ characters. Write a test that iterates every state, attempts a
STAFF transition to RESOLVED, and expects rejection every time. That test is
the enforcement, not a code review convention.

Escalation is NOT a state. It is escalation_level:int on the issue.

SLA:
  due_at = first_reported_at + sla_hours(category, priority)
  effective_deadline = due_at + paused_seconds
  breached = now() > effective_deadline AND status.clockRunning()
Clock PAUSES in PENDING_VERIFICATION. Priority upgrades recompute and shorten
the deadline. Downgrades never extend it.

[ADDED] Inject a java.time.Clock bean rather than calling Instant.now()
anywhere in the SLA, escalation, or priority code. Tests need to advance time
deterministically. application-demo.yml is for the live demo and is not a
substitute — a test that depends on wall-clock sleeps is slow and flaky, and
"priority rises over simulated time" is untestable without a controllable
clock.

[CHANGE 5] The escalation ladder is NOT a pure parent_department_id walk.
Level 3 is a ward officer, who lives in the wards table. Implement it as an
ordered list of resolver strategies:
  level 1 -> department.head_user_id
  level 2 -> parent department's head_user_id (walk parent_department_id once)
  level 3 -> ward officer, looked up from issues.ward_id
  level 4 -> admin / commissioner. TERMINAL.
Cap escalation_level at 4 everywhere it is incremented, including
reopen-on-recurrence. Level 4 issues appear in a "chronic breach" list rather
than escalating further.

Escalation job: @Scheduled every 5 minutes under ShedLock. Select breached
issues FOR UPDATE SKIP LOCKED in batches. Per issue: resolve next owner,
insert escalation_events row, advance escalation_level with a conditional
compare-and-swap. Re-arm due_at = now() + remaining/2, floored at 2h.

[CHANGE 4] The same sweep must also recompute priority for ALL open issues,
not only breached ones. Priority includes an age term, so an issue that never
receives a second report freezes at its creation-time score and never rises
in the queue. That is backwards: neglected issues are exactly the ones that
should climb. Recompute score and band, tighten due_at if the band rose,
batch it into as few UPDATEs as possible.

Auth in this phase: JWT, 15-minute access tokens, 30-day refresh tokens,
BCrypt cost 12, four roles. Two authorisation layers: @PreAuthorize for
"can this kind of user do this kind of thing", plus a guard bean for "is this
specific user allowed to touch this specific issue" checking department and
ward scope. Skip password reset and refresh token rotation; they are cut.

[ADDED] Three things auth has to settle that phase 2 deliberately left open.
Decide each explicitly and record it in DESIGN-DECISIONS.md; do not let the
implementation pick by default:

  a) POST /api/v1/reports is currently permitAll with reporterId hard-wired
     to null. Does citizen reporting now REQUIRE a login, or stay open to
     anonymous submission? A civic platform that forces registration before
     someone can report a pothole will collect fewer reports. If it stays
     open, say how an authenticated reporter's identity gets attached when
     one is present. The DTO already takes reporterId from the principal
     rather than the body — keep that property whatever you decide.

  b) distinct_reporter_count is currently derived from deviceId because
     there were no users. Once accounts exist, what counts as a distinct
     reporter? This feeds priority scoring, so getting it wrong quietly
     skews the queue.

  c) DD-015 recorded the actuator exposure mismatch as open pending auth:
     application.yml exposes health,info,metrics,prometheus while
     SecurityConfig permits only health, health/** and info. Auth exists
     now, so resolve it. Prefer requiring ADMIN over widening the anonymous
     whitelist.

[ADDED] /api/v1/issues currently returns 403 to anonymous callers. Once the
resource server supplies an entry point it should return 401. Update the
verification steps in docs/civictrack-claude-code-prompts.md and any scaffold
notes that still say this happens "in phase 5" — auth is phase 3; phase 5 is
deployment. Several notes carry the stale number.

Required tests:
- Running the escalation job twice produces exactly one escalation_events row.
- Two concurrent job executions produce one escalation event.
- The clock does not advance during PENDING_VERIFICATION.
- Escalation stops at level 4 and does not overflow.
- An issue at level 3 resolves to the ward officer, not a department head.
- A sanitation STAFF user cannot act on a roads issue.
- An untouched issue's priority rises over simulated time.
- Every illegal transition returns 409 or 403.
- [ADDED] An anonymous caller hitting a protected endpoint gets 401, not 403.

[ADDED] For each test above, verify it actually fails when the behaviour it
guards is removed or inverted. Do not assume — break it, watch it go red,
restore it. Phase 2 produced two tests that passed while measuring nothing:
driftIsBounded asserted the extent cap held in a scenario that never invoked
the cap, and the DD-010 query-plan guard asserted a string that appeared in
every plan including the regression. Both looked like coverage and were not.
Report which tests you verified this way and what you saw fail.
```


---

## Phase 4 — Frontend core + cluster inspector (Week 4a)

```
Phase 4: Next.js frontend. Clean and functional. Tailwind. Mobile-first, must
work at 360px because the demo happens on phones. Status is never conveyed by
colour alone (WCAG AA).

Routes for this phase, and only these:
  /                      landing
  /report                3-step report flow
  /report/success/[ref]  ticket confirmation with the merge result
  /map                   Leaflet, status-coloured markers, filters
  /issues                filterable list
  /issues/[id]           detail: photos, timeline, status history
  /issues/[id]/cluster   THE CLUSTER INSPECTOR (see below)
  /login  /register
  /me/reports
  /staff/queue           department queue by priority then deadline
  /staff/issues/[id]     acknowledge, start, resolve-with-proof
  /dashboard             basic public metrics

Report flow:
- Step 1 photo, camera capture on mobile, client-side downscale to 1600px and
  compress to ~300KB before upload. Venue wifi will be bad.
- Step 2 auto-geolocation showing the accuracy radius. If accuracy > 150m,
  force manual pin placement on a Leaflet map.
- Step 3 category picker with icons, optional description and landmark.
Whole flow completable in under 20 seconds. Time it and tell me the number.

The success page matters: show the ticket reference and, on a merge, say
plainly "you are the Nth person to report this" with the distance to the
existing cluster.

CLUSTER INSPECTOR — read-only in this phase, and do not skip it. This screen
is the most persuasive artifact in the project because it makes the
contribution visible in one glance. Leaflet showing:
- the issue centroid as a distinct marker
- every member report as its own pin
- a translucent circle per report sized to its gps_accuracy_m
- a circle for the current effective merge radius
- a side panel listing each report's cluster decision, distance, and the
  radius in force when it was decided
Split and merge interactions come in phase 7. Read-only is enough now.

TanStack Query for fetching, React Hook Form + Zod for the report form. No
<form> tags posting to the server; use event handlers.
```


### Phase 4 as delivered

The prompt above is the original brief. What was executed was an amended version
of it, in five stages, and the difference matters to anybody reading this later:

**Stage 0 — the public read API, which the brief did not mention.** The frontend
needs ten endpoints that did not exist. `GET /api/v1/public/issues`, `/bbox`,
`/{id}`, `/{id}/reports`, `/{id}/history`, `/categories`, `/wards`,
`/dashboard/summary`, `/me/reports`, and — added during the phase because
`/report/success/[ref]` cannot work without it —
`GET /api/v1/public/issues/by-ref/{publicRef}`. Public DTOs are separate records
from the staff ones (DD-023), a `Department` entity was created, and the staff
queue gained the two tabs blueprint §3.14 specifies.

**Stages 1–4 — tokens and styleguide, shared components, the twelve screens,
then state and auth.** As the brief describes, with these corrections found
against the running code:

- The endpoint is `submit-for-verification`, not `resolve`. The blueprint's §5
  is stale.
- The cluster decisions are `SPLIT_LOW_CONF` and `SPLIT_EXTENT_CAPPED`, not the
  blueprint's `NEW_LOW_CONF` / `NEW_ISSUE_EXTENT_CAP`, and there is a sixth,
  `MANUAL`.
- There are nine statuses and eight colour tokens; three share `--st-active` and
  are separated by glyph and word (DD-032 neighbours).
- The 120 KB JS budget was unreachable — the framework baseline alone exceeds it
  (DD-031). What that budget was protecting is intact and measured: Leaflet is
  0 KB on the composer's happy path.

**Decisions recorded:** DD-023 through DD-038.

### Manual verification — phase 4

Standing rule 5. With `docker compose up -d`, the backend on the `demo` profile
and the frontend on `:3000`:

1. **`/`** — the hero shows a real overdue count. View source: the number is in
   the initial HTML, not filled in after hydration.
2. **`/report`** — allow location. Take a photo; the caption states the
   compressed size and says it is exactly what gets sent. Submit. The count on
   the result screen animates once, and only once.
3. **Reload the result screen.** It must still render — that is the `by-ref`
   endpoint, and its absence is why the screen would otherwise break on a
   refresh or a shared link.
4. **`/report` with location denied** in browser settings — the automatic fix is
   discarded, a map appears, and a pin must be placed by hand.
5. **`/issues/{id}/cluster`** on an issue with several reports — five layers:
   report pins, an accuracy circle each, the centroid crosshair, the dashed
   merge radius, the dotted extent cap. Then open a single-report issue: it must
   read "One report. Merge radius 25 m, no cluster uncertainty yet", not look
   broken.
6. **Sign in** as `crew.roads@civictrack.example` and open an issue from
   `/staff/queue`. Exactly one action is offered. Acknowledge it, and note that
   the next screen offers **nothing** and says it is waiting for a supervisor to
   assign — that is the transition table, not a bug (DD-035).
7. **DevTools → Application.** `civictrack_refresh` is httpOnly. localStorage
   and sessionStorage contain no token.
8. **Screenshot `/issues` and `/staff/queue` in greyscale.** If two statuses
   become indistinguishable, the encoding is wrong.
9. **Resize to 360 px** on every screen. No horizontal scroll.
10. **Tab through `/report`.** Every control takes focus with a visible ring;
    the category grid is one tab stop, navigated with arrow keys.

Automated equivalents of 5, 6 and the API contract live in `tools/`.

---

## Phase 5 — Deploy (Week 4b), month-1 milestone

```
Phase 5: public deployment. Nothing lands with an examiner like handing them
a URL and letting them report something from their own phone.

- Multi-stage Dockerfile with CDS enabled, backend on Render free tier as a
  Docker web service.

- DATABASE: Supabase (or Neon), NOT Render Postgres. Render's free Postgres
  expires 30 days after creation and is deleted after a 14-day grace period.
  This project runs for 8 weeks, so a Render database would die mid-project
  and take the seed corpus and ground-truth labels with it. Supabase keeps
  free Postgres indefinitely, ships PostGIS preinstalled, and only pauses
  after a week of inactivity, which our keep-alive ping prevents.

  CRITICAL: connect to the DIRECT endpoint (port 5432), never the transaction
  pooler (6543). Two reasons, both fatal to this project specifically:
  Hibernate uses server-side prepared statements, which break under
  PgBouncer/Supavisor transaction pooling; and our entire clustering
  concurrency design rests on pg_advisory_xact_lock and FOR UPDATE row locks.
  Hikari is already our pool. Getting this wrong produces intermittent
  failures that look exactly like clustering bugs. Put this warning in a
  comment next to the datasource config.

  Run CREATE EXTENSION postgis and CREATE EXTENSION btree_gist there.
  Add a scripted pg_dump backup to the repo, run weekly. Free tiers die.

- Frontend on Vercel, auto-deploy from main. Note that Leaflet must be loaded
  with dynamic(() => import(...), { ssr: false }) because it touches window
  at module scope.
- GitHub Actions: Maven build + full suite including Testcontainers on every
  push, deploy hook on merge to main. Cache the postgis image layer, and use
  a single static reused container across test classes rather than one per
  class; otherwise CI time balloons.
- Cloudinary upload with transformation. Photo upload happens BEFORE the
  transaction opens, so add a nightly job deleting Cloudinary assets with no
  corresponding report row. Otherwise failed submissions leak storage.
- springdoc-openapi, public Swagger UI.
- Hikari pool max 8 (free tier connection cap), leak detection on.
- Keep-alive ping every 10 minutes via cron-job.org.
- Run the seed generator against the deployed database.

Generate docs/ARCHITECTURE.md with component, ER and state machine diagrams
as Mermaid so I can export vector versions for the report.

Print at the end: both public URLs, credentials for all four roles, and a
manual verification checklist.

MONTH 1 IS DONE HERE. Before I present, give me a one-page summary of what
works, what is stubbed, and what is scheduled for month 2, so I can be
straight with my reviewer about the boundary.
```

---

## Phase 6 — Verification loop (Week 5)

```
Phase 6: citizen verification. Section 4.3.

  reporters     = distinct reporters on the issue
  required      = min(3, max(1, ceil(reporters / 2)))
  rejections >= confirmations and rejections >= 1 -> REOPENED, level+1 (cap 4)
  confirmations >= required                       -> RESOLVED
  72h elapsed and rejections == 0                 -> RESOLVED (silence=consent)
Auto-close 7 days after RESOLVED. One vote per citizen per issue, enforced by
the unique constraint. Only reporters may vote. Anonymous reporters cannot.

[CHANGE 6] That last rule creates a hole: an issue reported only by anonymous
users has no eligible voters, so it always auto-resolves at the 72h timeout
with zero verification. Letting anonymous devices vote would open an obvious
abuse vector, so we are not fixing it. We are measuring it.

Add resolved_without_verification:boolean to issues, set it when an issue
resolves via timeout with zero votes cast, and expose "% resolved without
citizen verification" per department on the public dashboard alongside the
reopen rate. Record the reasoning in docs/DESIGN-DECISIONS.md. A named and
measured limitation is a stronger position in a viva than an unnoticed one.

Also: verification prompt to all eligible reporters, in-app notification
centre, and the timeout sweep job (same ShedLock pattern, must be idempotent).
Frontend: /me/verify/[id] and /me/notifications.
```

---

## Phase 7 — Moderation and dashboard (Week 6)

```
Phase 7: supervisor tools and the full public dashboard.

Split and merge, extending the phase 4 cluster inspector to be interactive:
lasso a subset of report pins and split into a new issue, or merge two issues.
Both trigger FULL centroid recomputation from member reports, not incremental
update, and both recompute max_member_dist_m. Both are logged. No report is
ever deleted; reports move between issues and keep their identity.

Low-confidence review queue at /supervisor/review, showing everything with
needs_review = true from any cause: MERGED_LOW_CONF, NEW_LOW_CONF and
NEW_ISSUE_EXTENT_CAP. Supervisor can confirm, split, merge or recategorise.
Recategorisation forces the issue out of its cluster into a new one.

Public dashboard, all without authentication:
- median resolution time by ward and department (PERCENTILE_CONT)
- SLA compliance percentage with trend
- open backlog age histogram
- currently breaching list, live
- reopen rate per department
- % resolved without citizen verification per department  [CHANGE 6]
- reported vs resolved daily trend
- top clusters by distinct reporter count

SSE for live updates. Broadcast AFTER transaction commit, never inside it.
Send a heartbeat comment every 25 seconds; Render's proxy will close an idle
SSE connection otherwise and the live demo dies silently.
```

---

## Phase 8 — Evaluation (Week 7)

```
Phase 8: the measurements that turn this from a working system into a paper.
The seed generator from phase 2 already emits ground-truth labels, so this
should be scripts, not new infrastructure.

1. Clustering quality. Run the pipeline over the labelled corpus. Compute
   precision, recall and F1 against ground truth. Then sweep merge radius
   from 10 to 100 m in 5 m steps and plot F1 against radius, per category.
   This turns the 25 m and 50 m defaults from assertions into empirically
   justified choices, which is exactly the question an examiner asks.

2. Ingest latency versus scale. Measure p50/p95/p99 end-to-end ingest at 1k,
   10k and 50k existing issues. Use k6 to drive the load and the Micrometer
   timer on the ingest endpoint as the server-side measurement; report both,
   since the gap between them is network and photo upload. Do not hand-roll a
   Java timing harness. Expect near-flat latency, demonstrating the GiST
   index keeps the candidate query sublinear. If it is not flat, EXPLAIN at
   50k and find out why before I present it.

3. Concurrency correctness. N simultaneous identical reports for N in
   {2,5,10,20,50}, exactly one issue every time. Already covered by tests;
   here it becomes a reported result.

4. Extent cap effectiveness [CHANGE 1]. Compare F1 with the cap enabled and
   disabled on a corpus containing linear defects. This justifies the change
   with a number rather than an argument, and is worth its own paragraph.

Output every result as CSV plus a matplotlib PDF, vector not PNG, sized for a
two-column IEEE layout.
```

---

## Phase 9 — Anti-abuse and polish (Week 8)

```
Phase 9: hardening and demo prep.

- Bucket4j rate limiting: 1 report per device per category per 5 min within
  50 m; 20 per account per day; login throttling.
- dHash perceptual hashing on report photos and on resolution proof photos.
  The second matters more: the same hash across two resolutions means a staff
  member reused an "after" photo. Flag to supervisor.
- EXIF capture-time vs submit-time gap raises a soft flag.
- Profanity filter on descriptions; supervisor can redact while keeping the
  report.
- RFC 7807 problem responses everywhere, no stack traces.
- MIME-sniff uploads from actual bytes, not the declared content type.

Then seed the demo scenario: demo profile, 3-minute SLAs, 20-second sweep,
one pre-seeded issue at 2:30 remaining on its clock. Follow the runbook in
section 15 of the blueprint.
```

---

## Cut list

Not building these. Say so explicitly rather than leaving gaps: email
notifications, refresh token rotation, password reset, reverse geocoding,
EXIF GPS fallback, heatmap layer, choropleth ward map, business-hours-aware
SLA, and the entire admin configuration UI. Categories, wards and departments
are configured through Flyway migrations. A CRUD screen for a ten-row table
impresses nobody.

## Month-1 review talking points

1. Lead with the schema decision: a report is evidence and immutable, an
   issue is work and mutable. Everything else is downstream.
2. Show the concurrency test passing. Twenty reports, one issue.
3. Open the cluster inspector. Three pins, three accuracy circles, one
   centroid.
4. Show the transition table. No path to RESOLVED accepts a STAFF actor.
5. Have the DBSCAN answer loaded, then volunteer the order-dependence
   limitation before anyone asks, and point at the extent cap and the review
   queue as the designed mitigations.
6. Hand them the URL.
