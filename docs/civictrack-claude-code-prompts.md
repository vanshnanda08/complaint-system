# CivicTrack — Build Phases and Manual Verification

**This file is the authoritative phase numbering for the project.** Where the
blueprint's §14 build order or the report's §17 timeline disagree with it, this
file wins, and both of those now say so.

---

## A note on this file's history, because it matters

Three places in this repository cited `docs/civictrack-claude-code-prompts.md`
as the authoritative build order — `SecurityConfig`, DD-015, and
`civictrack-app-blueprint.md` §Routes — and **the file did not exist**. It was
created during phase 3, when reconciling the numbering, and the citations now
resolve.

What it contains is the phase structure as it is actually being delivered,
together with the manual verification steps printed at the end of each phase
(standing rule 5). It is not a reconstruction of anybody's prompts and does not
claim to be one; the per-phase deliverable lists below describe what was built,
which is a question the repository can answer.

---

## Phase numbering

| Phase | Deliverable | Blueprint §14 equivalent | Status |
|---|---|---|---|
| **1** | Flyway schema, entities, PostGIS wired, `GeoFactory`, ward canary, health check, `POST /reports` creating one issue per report | 1 | done |
| **2** | Clustering engine: candidate query, accuracy-weighted centroid, adaptive radius, extent cap, post-lock re-read, advisory locking, concurrency test | 2 | done |
| **3** | Issue lifecycle and escalation: state machine, `TransitionPolicy`, status history, staff queue, SLA clock, escalation ladder, ShedLock sweep, priority ageing, **auth and RBAC** | 3 + 4 + 5 | done |
| **4** | Cloudinary, photo validation, dHash, proof-photo upload, device throttling | 6 | |
| **5** | Verification: quorum, timeout sweep, reopen on rejection, unverified-closure flagging, notification centre | 7 | |
| **6** | Next.js: report flow, map, issue detail, staff queue, cluster inspector | 8 | |
| **7** | Public dashboard, SSE | 9 | |
| **8** | Split/merge moderation tool | 10 | |
| **9** | Seed corpus, demo profile, deploy, rehearse | 11 | |

Phase 3 collapses three of the blueprint's phases into one, which is why
everything after it shifts down by two. The blueprint's ordering *rationale* —
why clustering precedes the state machine, why auth is late — is still worth
reading; only its numbers are superseded. The report's §17 timeline is a
different axis altogether: calendar weeks, not build order.

---

## Phase 1 — manual verification

```bash
docker compose up -d
cd backend && mvn spring-boot:run
curl -s localhost:8080/actuator/health          # {"status":"UP"}
```

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_issues_cluster_candidates';
-- the definition must contain (centroid)::geography, not bare centroid
```

- Post one report at (30.9300, 75.8200) and confirm a `CT-YYYY-NNNNNN` reference
  comes back.
- Post one at (0, 0) and confirm 422 "outside service area" rather than 500.

## Phase 2 — manual verification

- Post two reports 15 m apart in the same category: the second returns
  `"clusterDecision": "MERGED"` with a `distanceToClusterM` around 15 and
  `reportCount` 2.
- Post two reports 15 m apart in *different* categories: two separate issues.
  Category is a hard precondition, not a similarity score.
- Post a report with `accuracyM: 200`: 422 carrying the threshold and a remedy,
  not a bare 400.
- `SELECT code, low_conf_action FROM categories WHERE low_conf_action = 'SPLIT_FLAG';`
  returns exactly `OPEN_MANHOLE`, `STRAY_ANIMAL`, `WATER_LEAK` (DD-002).

## Phase 3 — manual verification

**Authentication and the 401/403 distinction.** Note that this is the phase in
which the shift happens: before it, `/api/v1/issues` answered 403 to an
anonymous caller because there was no authentication entry point to say
otherwise. Installing the resource server installs
`BearerTokenAuthenticationEntryPoint`, and the honest answer — "you have not
said who you are" — is now what comes back.

```bash
curl -si localhost:8080/api/v1/staff/queue | head -1
# HTTP/1.1 401  (and a WWW-Authenticate: Bearer header)

curl -si localhost:8080/api/v1/staff/queue -H 'Authorization: Bearer nonsense' | head -1
# HTTP/1.1 401  -- a malformed token is still "who are you?", not "you may not"

# register a citizen, then call a staff endpoint with their token
curl -si localhost:8080/api/v1/staff/queue -H "Authorization: Bearer $CITIZEN" | head -1
# HTTP/1.1 403  -- we know who you are, and you may not
```

- `curl -si localhost:8080/actuator/metrics` → 401; with a citizen token → 403;
  with an administrator token → 200 (DD-015).
- `curl -si localhost:8080/actuator/health` → 200 anonymously. A load balancer
  cannot hold a token.
- Post a report with **no** Authorization header: still 201. Reporting is open
  (DD-017). Post one **with** a citizen token and confirm `reports.reporter_id`
  is set for that row.

**The state machine.**

- Acknowledge an issue as a staff member from another department: 403, and the
  issue is unchanged.
- Call `/start` on a `NEW` issue: 409 naming `from: NEW`, `to: IN_PROGRESS`.
- Call `/submit-for-verification` with an empty `proofPhotoUrl`: refused. With a
  photo and a note under 20 characters: refused, naming the guard.
- There is no `/resolve` endpoint. Confirm that by looking: `IssueController`
  has `submit-for-verification` and `close`, and `close` is `hasRole('ADMIN')`.

```sql
-- every status change left an audit row, including the SYSTEM ones
SELECT from_status, to_status, actor_role, created_at
FROM issue_status_history ORDER BY created_at;
```

**Escalation.** Easiest with the demo profile:

```bash
SPRING_PROFILES_ACTIVE=demo,seed mvn spring-boot:run
```

- ~3-minute SLAs and a 20-second sweep. Watch an issue breach, then check:

```sql
SELECT i.public_ref, i.escalation_level, i.due_at, u.full_name AS owner
FROM issues i LEFT JOIN users u ON u.id = i.assigned_to
ORDER BY i.escalation_level DESC;

SELECT issue_id, level, reason, breached_by_seconds FROM escalation_events ORDER BY id;
```

- The level-3 event's `to_user_id` must be a **ward officer**
  (`SELECT officer_user_id FROM wards`), not a department head. That is DD-005,
  and it is the rung the specification's tree walk could not express.
- Level stops at 4. There is no level 5, and `chk_escalation_level` will refuse
  one if code ever tries.
- Run the sweep twice in a row (wait for two ticks with nothing else happening):
  the second produces no new `escalation_events` row.

**Priority ageing (DD-004).** With the demo profile, note an untouched issue's
`priority_score`, wait for a few sweeps, and confirm it has risen with no new
report attached to it:

```sql
SELECT public_ref, priority, priority_score, report_count, first_reported_at
FROM issues WHERE report_count = 1 ORDER BY priority_score DESC;
```

**The seeded org chart.** Confirm it exists and cannot be logged into:

```sql
SELECT email, role, password_hash IS NULL AS cannot_log_in FROM users
WHERE email LIKE '%@civictrack.example' ORDER BY role, email;
```

Every one of those rows must show `cannot_log_in = true` outside the demo
profile (DD-019).
