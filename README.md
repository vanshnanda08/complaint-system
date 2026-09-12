# CivicTrack

Municipal issue reporting with bounded incremental geo-clustering, SLA-driven
escalation, and public accountability.

Citizens report a civic problem with a photo in under twenty seconds. The
system merges spatially and categorically similar reports into a single
prioritised work item in real time, so twenty people reporting one pothole
produce one urgent ticket rather than twenty low-priority ones. Every issue
carries a deadline that escalates automatically when breached, and no
department can mark its own work resolved — citizens verify. A public
dashboard publishes the resulting numbers without a login.

## Project status

**Month-one milestone: complete.** All week 1–4 deliverables are built, tested
and deployed. Weeks 5–8 (verification, moderation, evaluation, write-up) are
next.

| | |
|---|---|
| Frontend | https://civic-track-six.vercel.app |
| API | https://civictrack-api.onrender.com |
| API docs (Swagger) | https://civictrack-api.onrender.com/swagger-ui/index.html |
| Backend tests | **172** passing, against real PostGIS via Testcontainers |
| Frontend tests | **58** passing, plus lint, typecheck and production build in CI |
| CI | Green on `main` (GitHub Actions) |
| Deployed corpus | 110 issues from 250 reports, across eight of the nine statuses |

> The API runs on Render's free tier and sleeps after 15 minutes idle. The
> first request after a quiet spell takes up to about a minute; the application
> itself starts in ~3.5 s.

### Done

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Flyway schema, PostGIS wired, health check, `GeoFactory`, ward-containment canary | ✅ done |
| 2 | Clustering engine: candidate query, accuracy-weighted centroid, adaptive radius, extent cap, advisory lock, post-lock re-read, concurrency test; seed corpus with ground-truth labels | ✅ done |
| 3 | State machine and `TransitionPolicy`, status history, staff queue, SLA clock, escalation ladder, ShedLock sweep, priority ageing, JWT auth and RBAC | ✅ done |
| 4 | Public read API; Next.js frontend: report composer, map, issue detail, **cluster inspector**, staff queue and work view, public dashboard, auth with httpOnly refresh cookie | ✅ done |
| 5 | Deployment: Docker image with CDS, Supabase, Render, Vercel, GitHub Actions CI, keep-alive, backup with a tested restore, Swagger UI, Cloudinary photo upload and nightly orphan sweep | ✅ done |

### Next (month 2)

| Phase | Deliverable |
|---|---|
| 6 | Citizen verification: quorum, timeout sweep, auto-close, reopen on rejection, notifications, `/me/verify` |
| 7 | Supervisor tools (assignment board, review queue, split/merge), full dashboard aggregates, SSE live updates |
| 8 | Evaluation: clustering precision/recall/F1 against the ground-truth labels, merge-radius sweep, latency vs. scale, extent-cap ablation |
| 9 | Rate limiting, anti-abuse, polish |

**Known limitation:** an acknowledged issue cannot yet progress through the UI,
because the next step (assignment) is a supervisor action arriving in phase 7.
The work view says so rather than offering a button the server would refuse.
The full lifecycle already works through the API and is covered by tests.

## Features

- **Geo-clustering.** Reports in the same category merge into one issue under
  an adaptive radius `R_cat + ½·accuracy + ½·σ`, where σ = `1/√Σw` is the
  cluster's positional uncertainty. Well-evidenced clusters tighten
  automatically. A per-category extent cap stops clusters drifting along linear
  defects, and ambiguous matches merge or split according to the category
  (an open manhole never hides behind another).
- **Concurrency-safe.** A transaction-scoped advisory lock on
  (category, ~200 m cell) plus a two-phase candidate lookup: twenty simultaneous
  reports of one defect produce exactly one issue.
- **Accountable lifecycle.** Nine statuses. No staff-reachable transition leads
  to `RESOLVED` or `CLOSED`; staff submit for citizen verification and stop.
- **Priority.** Category severity + `12·log2(1 + distinct reporters)` + age
  (excluding paused time) + escalation level + reopen count.
- **SLA and escalation.** Deadlines from category hours scaled by priority; the
  clock pauses during verification; breaches escalate department head → parent
  department head → ward officer → administrator, idempotent under concurrent
  sweeps.
- **Anonymous reporting.** No account needed to report; a signed-in reporter's
  identity is taken from the verified token, never from the request body.
- **Accessible status display.** Every status is encoded by colour, shape and
  word, never colour alone. Light and dark themes.

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Data JPA, Hibernate Spatial, Spring Security OAuth2 Resource Server (JWT), Flyway, ShedLock, springdoc-openapi |
| Database | PostgreSQL + PostGIS (Supabase in production) |
| Frontend | Next.js 16 App Router, React 19, TypeScript, Tailwind CSS v4, TanStack Query, React Hook Form + Zod, Leaflet |
| Testing | JUnit 5, Testcontainers (`postgis/postgis`), Vitest, Playwright harnesses in `tools/` |
| Infrastructure | Docker, Render, Vercel, Cloudinary, GitHub Actions |

## Repository layout

```
backend/     Spring Boot 3.5 on Java 21
frontend/    Next.js 16 App Router, Tailwind v4
tools/       Harnesses that check the running server (contract, transitions, UI, runtime errors)
scripts/     Database backup, restore and remote seeding
```

The backend is packaged **by feature, not by layer**:
`com.civictrack.{report, clustering, issue, sla, verification, user, department,
ward, category, dashboard, publicapi, me, media, seed, config, common}`. Related
code lives together, so the clustering engine is one directory rather than a
service, a repository and a DTO scattered across three.

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| JDK | 21 | `brew install openjdk@21` |
| Maven | 3.9+ | `brew install maven` |
| Node.js | 22 | `brew install node@22` |
| Container runtime | any Docker-compatible | `brew install colima docker docker-compose` |

There is no substitute for the container runtime: the integration tests run
against a real `postgis/postgis:17-3.4`, and H2 has no PostGIS. That tag
tracks the deployed database's Postgres major (Supabase, PostgreSQL 17);
PostGIS is one minor ahead because no `17-3.3` image is published. See
`IntegrationTestBase` for why that gap is acceptable and what it does not cover.

If you use Colima rather than Docker Desktop, start it once per boot and link
the compose plugin once, ever:

```bash
colima start --cpu 2 --memory 4 --disk 20
mkdir -p ~/.docker/cli-plugins
ln -sfn /opt/homebrew/opt/docker-compose/bin/docker-compose ~/.docker/cli-plugins/docker-compose
```

Colima puts its Docker socket under `~/.colima` instead of
`/var/run/docker.sock`, which Testcontainers cannot find on its own. A Maven
profile in `backend/pom.xml` detects that socket and sets `DOCKER_HOST` for the
test JVM automatically — nothing to export, and it stays inert on CI and on
Docker Desktop.

## Running it locally

```bash
docker compose up -d                     # PostGIS on 5432

cd backend && mvn spring-boot:run \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--civictrack.demo.staff-password=demo1234 \
                               --civictrack.cors.allowed-origins=http://localhost:3000"

cd frontend && npm install && npm run dev     # http://localhost:3000
```

Flyway applies the schema on first boot. Verify:

```bash
curl -s localhost:8080/actuator/health    # {"status":"UP", ...}
```

The `demo` profile is what enables login for the seeded staff accounts: they
ship from the migration **without** a password hash, deliberately, because a
migration is the wrong place to put credentials (V4). It also compresses SLAs to
about three minutes and runs the sweep every twenty seconds, so a breach and an
escalation happen while a slide is still on screen — which means nearly every
seeded issue reads as overdue. Run without the profile for realistic deadlines,
at the cost of not being able to log in.

Seeded logins, all with the password passed above:

| Role | Email |
|---|---|
| ADMIN | `commissioner@civictrack.example` |
| SUPERVISOR | `head.roads@civictrack.example` (and one per department, plus four ward officers) |
| STAFF | `crew.roads@civictrack.example`, `crew.water@`, `crew.sanitation@` |

There is no seeded citizen login — citizens in the corpus have no password.
Register in the app to exercise `/me/reports`. Reporting itself needs no
account at all.

## Testing

```bash
cd backend  && mvn clean test    # 172 integration and unit tests, Testcontainers
cd frontend && npm run test      # 58 unit tests
cd frontend && npm run lint      # includes the Leaflet import boundary rule
```

Surefire is configured to run `*IT` alongside `*Test`, so `mvn test` really
does run everything. In this project the integration tests *are* the important
tests — spatial behaviour cannot be verified any other way — and a green build
that quietly skipped them would be worse than no build at all.

**Always `mvn clean test`, never incremental.** A stale `target/` once produced
two false diagnoses of a working query. Likewise, restart `next start` after
`npm run build` — a stale server serves old chunks and the failure looks like an
application bug.

Every test that claims to guard a behaviour was verified by breaking that
behaviour and watching the test go red, then restoring it.

`tools/` holds four harnesses that check what unit tests structurally cannot,
because they need the running server: the API contract against the declared
TypeScript types, the transition table, the action rendered at each lifecycle
state, and a runtime-error sweep of every route. See `tools/README.md`; each one
exists because it caught a bug a passing suite had endorsed.

## Configuration that is not optional

Four settings are load-bearing. Each is a defect if changed, and two of them
present as bugs somewhere else entirely.

| Setting | Where | Why |
|---|---|---|
| `spring.jpa.open-in-view=false` | `application.yml` | Defaults to **true**, holding a DB connection for the whole request. With Hikari at 8, the concurrency test exhausts the pool and it presents as a phantom locking bug in the clustering engine |
| `ddl-auto: validate` | `application.yml` | Flyway owns the schema. Hibernate cannot generate a composite `btree_gist` index over a cast expression |
| `-XX:MaxRAMPercentage=75` | `Dockerfile` | Default JVM heap sizing in a 512 MB container causes intermittent OOM kills |
| A session-stable connection on port 5432, never a transaction pooler | `docker-compose.yml`, deploy env | `pg_advisory_xact_lock` and `FOR UPDATE` need a stable session; Hibernate's server-side prepared statements are incompatible with transaction pooling. Hikari is already the pool |

## Standing rules

1. **Every tunable number lives in the `categories` table, not in code.**
   Merge radii, SLA hours, severity weights, extent multipliers, reopen
   windows, band policies.
2. **Every distance uses `ST_Distance(...::geography)`.** Never planar, never
   computed in Java.
3. **Exactly one class constructs a JTS `Point` from lat/lng: `GeoFactory`.**
   Everything else calls it. This is what prevents the lat/lng swap.
4. **Tests are written alongside features, not after.**
5. **Status changes go through exactly one method**, `IssueStatusService.transition`.
6. **Time comes from an injected `java.time.Clock`**, never `Instant.now()`, so
   SLA and escalation behaviour is testable without sleeping.

## Deployment

Backend on Render (Docker, free tier), database on Supabase, frontend on
Vercel. GitHub Actions runs the full backend suite and the frontend lint,
typecheck, tests and build on every push; a push to `main` that passes both
triggers the Render deploy hook, and Vercel deploys the frontend from its own
Git integration. A scheduled keep-alive workflow pings the API every ten
minutes.

The deployed corpus is the seeded 250 reports across ~110 issues. It was 2,000
reports and ~800 issues originally; the smaller corpus is deliberate — 800
issues buried the handful that actually illustrate a breach, an escalation and
a merge.

Two things about that stack that are not obvious and have both bitten once:

**Connect through Supabase's SESSION POOLER, not the direct endpoint.** The
direct host `db.<ref>.supabase.co` is IPv6-only and Render's free tier has no
IPv6 route, so it fails with "Network is unreachable". Use
`aws-0-<region>.pooler.supabase.com:5432` with user `postgres.<project-ref>`.
Not port 6543 — that is transaction mode and breaks Hibernate's prepared
statements. The port is not the tell; the hostname is.

**Cold start.** The Docker image trains a Class Data Sharing archive at build
time and asserts it loads with `-Xshare:on`, bringing application startup from
20–40 s to ~3.5 s. The remaining delay after idle is Render starting the
container.

Photos are compressed in the browser and uploaded straight to Cloudinary with
an unsigned, size- and format-restricted preset; a nightly job deletes uploads
that never became a report. Seeded demo reports deliberately point at
Cloudinary's public sample image.

## Security notes

`JWT_SECRET` **must** be set in any real deployment. The development default in
`application.yml` is public knowledge, and anybody holding the signing key can
mint an ADMIN token. The application refuses to start on a key shorter than 32
bytes.

The refresh token never reaches JavaScript: Next.js route handlers keep it in
an httpOnly cookie and exchange it server-side. The access token lives only in
memory.
