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
frontend/    Next.js App Router (from phase 8)
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
against a real `postgis/postgis:16-3.4`, and H2 has no PostGIS.

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

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Flyway schema, PostGIS wired, health check, `GeoFactory`, ward canary | **done** |
| 2 | Clustering engine, candidate query, weighted centroid, extent cap, advisory lock, concurrency test | **done** |
| 3 | State machine, transition policy, status history, staff queue | next |
| 4 | SLA computation, escalation ladder, ShedLock, priority ageing | |
| 5 | Auth and RBAC via the OAuth2 resource server | |
| 6 | Cloudinary, photo validation, dHash, proof-photo flow | |
| 7 | Verification quorum, sweep job, auto-close | |
| 8 | Next.js: report flow, map, issue detail, cluster inspector | |
| 9 | Public dashboard, SSE | |
| 10 | Split/merge moderation tool | |
| 11 | Seed corpus, demo profile, deploy, rehearse | |
