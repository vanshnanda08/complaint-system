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

## Repository layout

```
backend/     Spring Boot 3.5 on Java 21
frontend/    Next.js 16 App Router, Tailwind v4 (phase 4)
docs/        Specification, and DESIGN-DECISIONS.md
```

The backend is packaged **by feature, not by layer**:
`com.civictrack.{report, clustering, issue, sla, verification, user, ward,
category, dashboard, seed, common}`. Related code lives together, so the
clustering engine is one directory rather than a service, a repository and a
DTO scattered across three.

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| JDK | 21 | `brew install openjdk@21` |
| Maven | 3.9+ | `brew install maven` |
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

## Running it

```bash
docker compose up -d                      # PostgreSQL 16 + PostGIS 3.4 on :5432
cd backend && mvn spring-boot:run         # http://localhost:8080
```

Flyway applies the schema on first boot. Verify:

```bash
curl -s localhost:8080/actuator/health    # {"status":"UP", ...}
```

Tests, including the Testcontainers integration tests:

```bash
cd backend && mvn test
```

Surefire is configured to run `*IT` alongside `*Test`, so `mvn test` really
does run everything. In this project the integration tests *are* the important
tests — spatial behaviour cannot be verified any other way — and a green build
that quietly skipped them would be worse than no build at all.

## Configuration that is not optional

Four settings are load-bearing. Each is a defect if changed, and two of them
present as bugs somewhere else entirely.

| Setting | Where | Why |
|---|---|---|
| `spring.jpa.open-in-view=false` | `application.yml` | Defaults to **true**, holding a DB connection for the whole request. With Hikari at 8, the phase-2 concurrency test exhausts the pool and it presents as a phantom locking bug in the clustering engine |
| `ddl-auto: validate` | `application.yml` | Flyway owns the schema. Hibernate cannot generate a composite `btree_gist` index over a cast expression |
| `-XX:MaxRAMPercentage=75` | `Dockerfile` | Default JVM heap sizing in a 512 MB container causes intermittent OOM kills |
| Direct DB port 5432, never a transaction pooler | `docker-compose.yml`, deploy env | `pg_advisory_xact_lock` and `FOR UPDATE` need a stable session; Hibernate's server-side prepared statements are incompatible with transaction pooling. Hikari is already the pool |

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

Rationale for each, and for every other non-obvious call in the project, is in
[docs/DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md).

## Build order

Phase numbering is authoritative in
[docs/civictrack-claude-code-prompts.md](docs/civictrack-claude-code-prompts.md),
which also carries the manual verification steps for each phase. The blueprint's
own eleven-phase table is superseded (DD-022): phase 3 here delivers what it
listed as 3, 4 and 5.

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Flyway schema, PostGIS wired, health check, `GeoFactory`, ward canary | **done** |
| 2 | Clustering engine, candidate query, weighted centroid, extent cap, advisory lock, concurrency test, seed corpus and ground-truth labels | **done** |
| 3 | State machine and `TransitionPolicy`, status history, staff queue, SLA clock, escalation ladder, ShedLock sweep, priority ageing, auth and RBAC | **done** |
| 4 | Public read API, Next.js frontend: design tokens, shared components, twelve routes, report composer, cluster inspector, staff work view, auth with httpOnly refresh | **done** |
| 5 | Deploy: Docker image, Supabase, Vercel, GitHub Actions, Cloudinary, Swagger UI | next |
| 6 | Verification quorum, timeout sweep, auto-close, notifications, `/me/verify` | |
| 7 | Moderation, public dashboard aggregates, SSE, ward detail | |
| 8 | Evaluation: clustering accuracy against the ground-truth labels | |
| 9 | Anti-abuse, rate limiting, polish | |

Note that this table uses the numbering in the prompts document, **not** the
blueprint's own eleven-phase table, which is superseded (DD-022). Phase 3 here
delivers what the blueprint listed as 3, 4 and 5; the frontend the blueprint put
at 6 is delivered at 4.

## What runs today

Both halves run locally against the seeded corpus of 2,000 reports across ~800
issues.

```bash
docker compose up -d                     # PostGIS on 5432

cd backend && mvn spring-boot:run \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--civictrack.demo.staff-password=demo1234 \
                               --civictrack.cors.allowed-origins=http://localhost:3000"

cd frontend && npm install && npm run dev     # http://localhost:3000
```

The `demo` profile is what enables login for the seeded staff accounts: they
ship from the migration **without** a password hash, deliberately, because a
migration is the wrong place to put credentials (V4). It also compresses SLAs to
about three minutes so a breach and an escalation happen while a slide is still
on screen — which means nearly every seeded issue reads as overdue. Run without
the profile for realistic deadlines, at the cost of not being able to log in.

Seeded logins, all with the password passed above:

| Role | Email |
|---|---|
| ADMIN | `commissioner@civictrack.example` |
| SUPERVISOR | `head.roads@civictrack.example` (and one per department, plus four ward officers) |
| STAFF | `crew.roads@civictrack.example`, `crew.water@`, `crew.sanitation@` |

There is no seeded citizen login — citizens in the corpus have no password.
Register at `/register` to exercise `/me/reports`. Reporting itself needs no
account at all (DD-017).

## Documents

| | |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Component, ER, state-machine, ingest-sequence and escalation diagrams, each with the reasoning it encodes |
| [docs/MONTH-1-STATUS.md](docs/MONTH-1-STATUS.md) | What works, what is stubbed, and what went wrong — written to be checkable |
| [docs/DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md) | Forty decisions: the defect, why it mattered, the fix, the alternative rejected |
| [docs/civictrack-claude-code-prompts.md](docs/civictrack-claude-code-prompts.md) | Authoritative phase numbering and per-phase verification steps |

## Deployed

| | |
|---|---|
| Frontend | https://civic-track-six.vercel.app |
| API | https://civictrack-api.onrender.com |
| API docs | https://civictrack-api.onrender.com/swagger-ui/index.html |

Backend on Render (Docker, free tier), database on Supabase, frontend on Vercel.
The deployed corpus is the seeded 2,000 reports across ~800 issues.

Two things about that stack that are not obvious and have both bitten once:

**Connect through Supabase's SESSION POOLER, not the direct endpoint.** The
direct host `db.<ref>.supabase.co` is IPv6-only and Render's free tier has no
IPv6 route, so it fails with "Network is unreachable". Use
`aws-0-<region>.pooler.supabase.com:5432` with user `postgres.<project-ref>`.
Not port 6543 — that is transaction mode and breaks Hibernate's prepared
statements. The port is not the tell; the hostname is (DD-040).

**Render's free tier sleeps after 15 minutes idle**, so the first request after
a quiet spell takes roughly a minute. The CDS archive gets the application
itself to ~3.5 s; the rest is Render starting the container.

## Testing

```bash
cd backend  && mvn clean test    # 162 integration and unit tests, Testcontainers
cd frontend && npm run test      # 53 unit tests
cd frontend && npm run lint      # includes the Leaflet import boundary rule
```

**Always `mvn clean test`, never incremental.** A stale `target/` produced two
false diagnoses of a working query and cost most of an afternoon; DD-027 records
what happened. Likewise, restart `next start` after `npm run build` — a stale
server serves old chunks and the failure looks like an application bug.

`tools/` holds four harnesses that check what unit tests structurally cannot,
because they need the running server: the API contract against the declared
TypeScript types, the transition table, the action rendered at each lifecycle
state, and a runtime-error sweep of every route. See `tools/README.md`; each one
exists because it caught a bug a passing suite had endorsed.

## Running the demo profile

```bash
cd backend && SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run
```

Three-minute SLAs and a twenty-second sweep, so a breach, an escalation and a
re-armed deadline all happen inside a five-minute slot on a projector. It runs
the same code as any other profile — only the durations change.

The org-chart accounts seeded by `V4__org_chart.sql` ship with **no password**
and cannot be logged into. The demo profile gives them one at startup, from
configuration:

```bash
SPRING_PROFILES_ACTIVE=demo CIVICTRACK_DEMO_STAFF_PASSWORD=... mvn spring-boot:run
```

A migration is the wrong place for credentials — it is in version control,
identical everywhere, and applied to production automatically — so what it ships
is an org chart, and enabling logins is a deliberate act in one environment.

## Security notes

`JWT_SECRET` **must** be set in any real deployment. The development default in
`application.yml` is public knowledge, and anybody holding the signing key can
mint an ADMIN token. The application refuses to start on a key shorter than 32
bytes.
