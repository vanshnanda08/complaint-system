# CivicTrack — Project Report (Revision 2)

**Municipal Issue Reporting Platform with Bounded Incremental Geo-Clustering and SLA-Driven Escalation**

---

## Revision note

This is a revised version of the original project report. Six defects were found in the original design during a pre-implementation review, and the technology stack was changed in three places, one of which was a project-ending risk. Section 12 documents every change with its rationale and the alternative rejected. Nothing was changed silently.

---

## 1. Project at a Glance

| Field | Detail |
|---|---|
| Project name | CivicTrack |
| Category | Web application, civic technology, GIS |
| Type | Full-stack web platform (public + administrative) |
| Domain | E-governance / municipal service delivery |
| Core technical claim | Bounded incremental online geo-clustering of citizen reports into deduplicated municipal work items |
| Primary language | Java 21 |
| Backend | Spring Boot 3.5 |
| Frontend | Next.js (React) |
| Database | PostgreSQL 16 with PostGIS 3.4, hosted on Supabase |
| Deployment | Render (backend), Supabase (database), Vercel (frontend) — publicly accessible, not localhost |
| Duration | 8 weeks |
| Users | Citizens, field staff, department supervisors, municipal administrators, plus anonymous public read access |

**One-line description:** A web platform where citizens report civic problems with a photo in under twenty seconds, the system automatically merges nearby reports of the same problem into a single prioritised ticket, and a public dashboard holds departments accountable to resolution deadlines.

---

## 2. Abstract

Municipal complaint handling in most Indian cities runs on phone calls, paper registers, and WhatsApp groups. Citizens get no ticket number, no timeline, and no confirmation that anyone acted. Departments receive the same problem reported many times over and treat each report as a separate complaint, so a pothole reported by twenty commuters looks like twenty low-priority items rather than one urgent one. Nobody outside the department can see how long anything takes.

CivicTrack addresses this with three interlocking mechanisms. First, a bounded incremental geo-clustering engine that merges spatially and categorically similar reports into one issue in real time, converting report volume from duplicate noise into a priority signal. Second, a workflow engine that attaches a category-specific resolution deadline to every issue and escalates it automatically up the departmental hierarchy when that deadline is breached, with citizen verification required before any issue can be marked resolved. Third, a public dashboard exposing median resolution time, deadline compliance, reopen rate, and unverified-closure rate per ward and per department to anyone, without a login.

The technical contribution is the clustering approach. Reports arrive as an unbounded live stream, each carrying its own GPS uncertainty, and each cluster corresponds to a public ticket number that cannot be reassigned. This rules out batch density clustering such as DBSCAN and calls for single-pass online clustering with accuracy-weighted centroid maintenance, an adaptive merge radius derived from positional uncertainty, an explicit bound on cluster spatial extent to prevent centroid drift chaining, and concurrency control so that simultaneous reports of the same problem cannot produce duplicate tickets.

---

## 3. Problem Statement

### 3.1 Current state

| Aspect | How it works today | Consequence |
|---|---|---|
| Reporting channel | Phone call, physical register, WhatsApp, occasionally a form that emails someone | No structured data, no ticket, no searchability |
| Acknowledgement | Verbal at best | Citizen cannot prove they reported anything |
| Deduplication | Manual, if at all | Twenty reports of one pothole become twenty complaints |
| Prioritisation | Political influence, whoever calls loudest | Volume of genuine public impact is invisible |
| Deadlines | Informal or nonexistent | No definition of "late" |
| Escalation | Requires someone to notice and complain again | Ignored complaints stay ignored |
| Closure | The department decides it is closed | "Marked resolved" and "actually fixed" diverge |
| Transparency | Internal reports, if produced | No external pressure to improve |

### 3.2 The four problems, stated precisely

1. **Reports disappear.** No ticket identity means no tracking, no follow-up, and no accountability. The citizen has no artifact.
2. **Duplicates destroy the priority signal.** Every complaint management system treats one complaint as one row. Twenty people reporting one pothole should be the strongest possible evidence that it needs fixing today, but in a flat schema it is indistinguishable from twenty unrelated minor issues.
3. **Nothing is late, because nothing has a deadline.** Without a per-category service level and automatic escalation, an unassigned ticket can sit indefinitely and no one is notified.
4. **The department grades its own work.** When the same office both performs the repair and declares it complete, "resolved" measures paperwork, not outcomes.

### 3.3 Why existing tools do not solve it

Generic complaint apps and form builders address problem 1 only. They produce a ticket, then stop. They have no spatial reasoning (so duplicates persist), no deadline model (so nothing escalates), no verification loop (so closure is self-certified), and no public reporting layer (so there is no external pressure). CivicTrack targets problems 2, 3, and 4 specifically, and treats problem 1 as table stakes.

---

## 4. Objectives

### 4.1 Primary objectives

| ID | Objective | Measurable outcome |
|---|---|---|
| O1 | Reduce reporting friction to near-zero | Median time from opening the app to submitted report under 20 seconds |
| O2 | Automatically deduplicate spatially co-located reports | Clustering F1 above 0.90 against a generated ground-truth label set |
| O3 | Convert report volume into a priority signal | Issue priority is a monotonic function of distinct reporter count |
| O4 | Guarantee every issue has an owner and a deadline | 100% of issues carry a `due_at`; zero breached issues remain unescalated after one sweep interval |
| O5 | Prevent self-certified closure | No state transition path exists from any state to RESOLVED or CLOSED with a STAFF actor |
| O6 | Make performance publicly visible | Median resolution time, SLA compliance, reopen rate, and unverified-closure rate per ward and department available without authentication |
| O7 | Bound cluster spatial extent | No issue's furthest member report exceeds a configured multiple of its category merge radius; verified by an ablation experiment |

### 4.2 Secondary objectives

- Provide a moderator tool to correct clustering errors without destroying evidence (split and merge).
- Maintain a complete, immutable audit trail of every status change and escalation.
- Deploy publicly so the system can be evaluated by anyone from their own phone.
- Keep total running cost at zero by staying inside free tiers that do not expire during the project.

---

## 5. Scope

### 5.1 In scope

- Citizen reporting with photo, geolocation, category, and description
- Bounded incremental geo-clustering of reports into issues
- Category-specific SLA computation and automatic hierarchical escalation
- Validated issue lifecycle state machine with role-gated transitions
- Staff resolution flow requiring a proof photo
- Citizen verification of claimed fixes, with quorum and timeout rules
- Automatic reopening on citizen rejection or on recurrence within a window
- Supervisor moderation tools: split, merge, recategorise, reject
- Role-based access control across four user types plus anonymous read
- Public accountability dashboard with ward and department metrics
- Interactive map with filtering, plus a read-only cluster inspector
- Live updates via server-sent events
- Public deployment with seeded historical data
- Clustering evaluation: precision/recall/F1, merge-radius sweep, latency-versus-scale, extent-cap ablation

### 5.2 Explicitly out of scope

Stated deliberately, because an examiner will ask where the boundary is. The list is longer than in revision 1 because the schedule shortened from eleven weeks to eight.

| Excluded | Reason |
|---|---|
| Native mobile apps (Android/iOS) | Responsive web covers the use case; native adds no research value |
| SMS and voice channels | Requires paid gateway and telecom compliance; interface is designed to allow it later |
| Payment or fine collection | Different regulatory domain entirely |
| Integration with real municipal ERP systems | No access to such systems; a deployment concern, not a design one |
| Machine-learning image classification | Interesting, but orthogonal to the clustering contribution and a time sink |
| Multilingual UI | Future work |
| Offline-first / sync | Substantial complexity for marginal demonstration value |
| Predictive maintenance analytics | Requires years of real data |
| **Admin configuration UI** | Categories, wards and departments are configured through Flyway migrations. A CRUD screen for a ten-row table demonstrates nothing and costs a week |
| **Email notifications** | In-app notification centre covers the demo; email adds a delivery dependency |
| **Refresh token rotation, password reset** | Mechanical auth features with no bearing on the contribution |
| **Reverse geocoding, EXIF GPS fallback** | Nice-to-have inputs to a flow that already works |
| **Heatmap and choropleth layers** | The cluster inspector communicates the contribution far better |
| **Business-hours-aware SLA** | Adds a calendar model to a system whose interesting property is the escalation ladder, not the clock arithmetic |

---

## 6. Stakeholders and User Roles

| Role | Who they are | What they can do | What they cannot do |
|---|---|---|---|
| **Anonymous visitor** | General public, press, researchers | View the dashboard, map, all issues, timelines; submit a rate-limited report | Verify fixes, see reporter identities |
| **Citizen** | Registered resident | Everything above, plus track own reports, receive notifications, verify fixes on issues they reported | Change issue status, see other reporters' identities |
| **Field staff** | Ward-level worker, sanitation crew, electrician | View own department queue, acknowledge, start work, upload proof photo and move to pending verification | Assign work, close issues, mark resolved, act on other departments' issues |
| **Supervisor** | Department head or ward officer | Everything staff can do, plus assign work, split and merge clusters, recategorise, reject invalid issues, view reporter identities, review the low-confidence queue | Force-close, change SLA policy, manage users |
| **Administrator** | Municipal IT or commissioner's office | Full access; configuration is applied through migrations rather than a UI in this build | — |

### 6.1 Personas

**Harpreet, 34, commuter.** Rides past a pothole on Ferozepur Road every morning. Has complained twice by phone with no result. Wants proof that a report exists and a way to check on it later. Will spend twenty seconds, not two minutes.

**Rajinder, 45, sanitation supervisor.** Has forty open items and no way to tell which matter most. Currently prioritises by whoever telephones him. Wants a queue ordered by something defensible, and wants credit when his ward performs well.

**Sunita, 29, ward councillor's office.** Needs to answer "what is the status of complaints in ward 12" without calling four departments. Wants a page she can share.

**Anmol, 22, staff member.** Fixes what he is assigned. Objects to being measured on tickets that sat unassigned for a week before reaching him, which is why the SLA clock pauses during citizen verification and why escalation moves ownership rather than just sending a reminder.

---

## 7. Feature Breakdown by Module

Priority uses MoSCoW: **M** must have, **S** should have. Could-haves from revision 1 have been moved to the out-of-scope table rather than left as aspirations.

### Module 1 — Reporting

| Feature | Priority |
|---|---|
| Photo capture or upload, client-side compression to ~300 KB | M |
| Automatic geolocation with accuracy capture | M |
| Manual pin adjustment when accuracy exceeds 150 m | M |
| Category selection with icons | M |
| Free-text description and landmark field | M |
| Anonymous reporting with device throttling | S |

### Module 2 — Clustering Engine

| Feature | Priority |
|---|---|
| Candidate lookup by category, ward, status, and radius | M |
| Accuracy-weighted incremental centroid, maintained in O(1) | M |
| Adaptive merge radius from category and positional uncertainty | M |
| **Post-lock distance re-verification** | M |
| **Cluster extent cap to prevent centroid drift chaining** | M |
| **Category-configurable low-confidence band action (merge or split)** | M |
| Three-band decision with review flagging | M |
| Concurrency control via spatial advisory lock and row locking | M |
| Reopen on recurrence within window | M |
| Per-report clustering audit trail | M |
| Supervisor split and merge with full centroid recomputation | S |
| Low-confidence review queue | S |
| **Deterministic seed corpus generator with ground-truth labels** | M |

### Module 3 — Issue Lifecycle

| Feature | Priority |
|---|---|
| Nine-state machine with a declarative transition table | M |
| Role and guard checks on every transition | M |
| Immutable status history | M |
| Proof photo mandatory before pending verification | M |
| Priority scoring and banding | M |
| **Scheduled priority recomputation for ageing issues** | M |
| Assignment and reassignment | M |
| Rejection with mandatory reason and reporter notification | S |

### Module 4 — SLA and Escalation

| Feature | Priority |
|---|---|
| Category and priority based deadline computation | M |
| Clock pause during citizen verification | M |
| Scheduled breach sweep | M |
| Idempotent escalation with unique-constraint enforcement | M |
| **Explicit four-level escalation resolver, capped** | M |
| Deadline tightening on priority increase, never extension | M |

### Module 5 — Verification

| Feature | Priority |
|---|---|
| Verification prompt to all eligible reporters | M |
| Quorum evaluation (fixed vs not-fixed) | M |
| 72-hour timeout treated as consent | M |
| **Flagging and public reporting of unverified closures** | M |
| Automatic reopen on rejection majority | M |
| Auto-close seven days after resolution | S |

### Module 6 — Access Control

| Feature | Priority |
|---|---|
| Registration and login, JWT via Spring Security resource server | M |
| Four roles with a permission matrix | M |
| Department and ward scoping beyond role checks | M |

### Module 7 — Public Dashboard

| Feature | Priority |
|---|---|
| Median resolution time by ward and department | M |
| SLA compliance percentage with trend | M |
| Open backlog age histogram | M |
| Currently breaching list, live | M |
| Reopen rate per department | M |
| **Unverified-closure rate per department** | M |
| Reported vs resolved daily trend | S |
| Top clusters by distinct reporter count | S |

### Module 8 — Map and Visualisation

| Feature | Priority |
|---|---|
| Leaflet map with status-coloured markers | M |
| Category, status, and date filtering | M |
| Bounding-box query for viewport-scoped loading | M |
| **Cluster inspector: member pins, accuracy circles, centroid, effective radius** | M |

The cluster inspector was promoted from "should have, week 10" to "must have, week 4". It is the single screen that makes the technical contribution visible, and anything scheduled for the last fortnight of an eight-week project is a feature you have decided not to build.

### Module 9 — Notifications and Realtime

| Feature | Priority |
|---|---|
| In-app notification centre | M |
| Server-sent events with keep-alive heartbeat | M |

---

## 8. System Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                          CLIENT LAYER                            │
│  Next.js (React) on Vercel                                       │
│  ├── Public: landing, map, dashboard, issue detail, inspector    │
│  ├── Citizen: report form, my reports, verification              │
│  ├── Staff: queue, resolve flow                                  │
│  └── Supervisor: review queue, split tool                        │
│  Leaflet (dynamic import, ssr:false) · TanStack Query · SSE      │
└───────────────────────────┬──────────────────────────────────────┘
                            │ HTTPS / JSON / multipart
┌───────────────────────────▼──────────────────────────────────────┐
│                     APPLICATION LAYER                            │
│  Spring Boot 3.5 on Render (Docker, CDS-enabled)                 │
│                                                                  │
│  API tier          Controllers, DTOs (records), ProblemDetail    │
│  Security tier     oauth2-resource-server JWT, department guards │
│  Service tier      ┌────────────────────────────────────────┐    │
│                    │ ClusteringService   ← the core         │    │
│                    │ IssueStatusService  + TransitionPolicy │    │
│                    │ SlaService / EscalationService         │    │
│                    │ VerificationService                    │    │
│                    │ PhotoService · NotificationService     │    │
│                    └────────────────────────────────────────┘    │
│  Scheduling tier   @Scheduled + ShedLock (SLA sweep + priority   │
│                    recompute, verification timeout, auto-close)  │
│  Observability     Actuator + Micrometer timers on ingest        │
│  Persistence tier  Spring Data JPA · Hibernate Spatial · Flyway  │
└──────────┬──────────────────────────────────┬────────────────────┘
           │ DIRECT connection, port 5432     │
           │ (never the transaction pooler)   │
┌──────────▼────────────────┐   ┌─────────────▼───────────────────┐
│  PostgreSQL 16 + PostGIS  │   │  Cloudinary                     │
│  Supabase managed         │   │  Image storage, transformation  │
│  Composite btree_gist idx │   │  CDN delivery                   │
│  Advisory locks           │   └─────────────────────────────────┘
└───────────────────────────┘
```

### 8.1 Architectural decisions worth defending

| Decision | Rationale | Alternative rejected |
|---|---|---|
| Monolith, not microservices | One team, one deployment, and transactional consistency across clustering and issue creation is required. Splitting them would need distributed transactions to solve a problem we do not have. | Microservices |
| Clustering runs synchronously in the request | The citizen must be told immediately whether their report merged and what the new count is. That feedback is the product. | Async queue-based clustering |
| Server-sent events, not WebSocket | Traffic is one-directional. SSE is a fraction of the code and works through proxies, provided a heartbeat is sent. | WebSocket / STOMP |
| Schema owned by Flyway, Hibernate on `validate` | Hibernate cannot generate a composite `btree_gist` index on a cast expression, and spatial DDL must be hand-written. | `ddl-auto: update` |
| Geometry column with cast to geography at query time | Clean JPA mapping plus true geodesic metres, with a functional index preserving performance. | Geography column, or planar approximation |
| Stateless JWT via Spring Security resource server | Horizontal scaling and a static frontend on a different origin, without a hand-written authentication filter. | Server-side sessions; hand-rolled JWT filter |
| Direct database connection, not the managed pooler | Hibernate's server-side prepared statements and our advisory-lock strategy are both incompatible with transaction-mode connection pooling. Hikari is already the pool. | Supabase Supavisor / PgBouncer transaction mode |

---

## 9. Technology Stack

### 9.1 Backend

| Component | Choice | Why |
|---|---|---|
| Language | Java 21 (LTS) | Records, pattern-matching switch; the stack municipal IT actually runs |
| Framework | Spring Boot 3.5.x | Declarative transactions, scheduling and security are exactly what this project's hard parts need |
| Web | Spring MVC | Blocking model is correct here; the work is DB-bound and transactional |
| Persistence | Spring Data JPA + Hibernate 6 | Entity mapping plus an escape hatch to native SQL where spatial work demands it |
| Spatial mapping | `hibernate-spatial` + JTS | Maps `org.locationtech.jts.geom.Point` to `geometry(Point,4326)` |
| Migrations | Flyway | Plain SQL; the schema needs raw PostGIS DDL |
| Security | Spring Security 6 + `spring-boot-starter-oauth2-resource-server` | Built-in `JwtDecoder` and `JwtAuthenticationConverter`. No hand-written filter |
| Scheduling | Spring `@Scheduled` + ShedLock | Distributed lock making the escalation job idempotent across instances |
| Observability | Actuator + Micrometer | `@Timed` on ingest supplies the p50/p95/p99 figures the evaluation needs |
| DTO mapping | Java 21 records with static factories | Compile-time, no annotation-processor coupling |
| Validation | Jakarta Bean Validation | Declarative request validation |
| Error responses | Spring 6 `ProblemDetail` | RFC 7807 with no third-party library |
| Rate limiting | Bucket4j | Token-bucket throttling per device and account |
| Image handling | Cloudinary Java SDK, metadata-extractor | Upload with transformation; EXIF timestamp extraction |
| API docs | springdoc-openapi | Swagger UI generated from annotations |
| Boilerplate | Lombok (entities only) | |
| Testing | JUnit 5, MockMvc, Testcontainers | Testcontainers runs real PostGIS; spatial logic cannot be tested on H2 |
| Load testing | k6 | Produces latency percentiles natively for the evaluation |

Removed from revision 1: `jjwt` (superseded by the Spring Security resource server), and MapStruct (roughly fifteen DTOs do not justify an annotation processor whose interaction with Lombok requires explicit processor-path ordering).

### 9.2 Database

| Component | Choice | Why |
|---|---|---|
| RDBMS | PostgreSQL 16 | Advisory locks, `FOR UPDATE SKIP LOCKED`, window functions, `PERCENTILE_CONT` for medians |
| Spatial extension | PostGIS 3.4 | `ST_DWithin`, `ST_Distance` on geography, `ST_Contains` for ward lookup, GiST indexing |
| Index support | `btree_gist` | Allows `category_code` and `ward_id` to sit inside the same GiST index as the geography expression, so one index serves the whole candidate query |
| Indexing | Composite partial GiST on `(category_code, ward_id, centroid::geography)` excluding terminal states; partial B-tree on `(status, due_at)` for the sweep | Keeps the clustering candidate query in single-digit milliseconds |

### 9.3 Frontend

| Component | Choice | Why |
|---|---|---|
| Framework | Next.js (App Router) | Server components for the public dashboard, client components for the map |
| UI | React 18+, Tailwind CSS | |
| Maps | Leaflet + react-leaflet, dynamically imported with `ssr: false` | Open source, no API key. Leaflet touches `window` at module scope, so it cannot be server-rendered |
| Data fetching | TanStack Query | Cache invalidation driven by SSE events |
| Charts | Recharts | Dashboard panels |
| Realtime | Native `EventSource` | |
| Forms | React Hook Form + Zod | |

### 9.4 Infrastructure and services

| Component | Choice | Tier | Notes |
|---|---|---|---|
| Backend hosting | Render, Docker web service | Free | 15-minute idle spin-down; CDS plus a keep-alive ping mitigates |
| **Database hosting** | **Supabase PostgreSQL** | Free | **Changed from Render Postgres. See §12.7** |
| Frontend hosting | Vercel | Free (hobby) | |
| Image storage/CDN | Cloudinary | Free | Ample for a demo; face-blur transformation available |
| Version control | GitHub | Free | |
| CI | GitHub Actions | Free | Build, test with a reused Testcontainers instance, deploy hooks |
| Uptime ping | cron-job.org | Free | Prevents both Render spin-down and Supabase inactivity pause |
| Backups | Scripted weekly `pg_dump` committed out-of-band | Free | Free tiers are not durable; the seed corpus and ground-truth labels are project-critical |
| Total monthly cost | **₹0** | | A municipality can pilot this at zero cost |

### 9.5 Runtime configuration that is not optional

These are recorded here because each one is a defect if omitted, and two of them present as bugs elsewhere in the system.

| Setting | Reason |
|---|---|
| `spring.jpa.open-in-view=false` | Defaults to true, holding a database connection for the entire request lifecycle. With Hikari capped at 8 on free-tier hosting, the twenty-thread concurrency test exhausts the pool and the failure presents as a phantom locking bug |
| `-XX:MaxRAMPercentage=75` | Default JVM heap sizing in a 512 MB container causes intermittent OOM kills |
| Spring Boot CDS enabled | Cuts JVM startup, directly attacking the highest-likelihood demo risk |
| Hikari `maximum-pool-size: 8`, leak detection on | Free-tier connection ceiling |
| Direct DB endpoint (5432), not the pooler (6543) | See §12.7 |

On virtual threads: `spring.threads.virtual.enabled` may be set, but no performance claim is made on that basis. With the connection pool capped at eight, the pool is the bottleneck, not the thread model. The argument for Java here rests on declarative transaction management and scheduling, not on concurrency primitives that this workload never reaches.

---

## 10. Data Model Summary

Eleven tables. The critical design decision is that **reports and issues are separate entities**.

| Table | Purpose | Key columns |
|---|---|---|
| `users` | All four roles with a role discriminator | role, department_id, ward_id, reputation |
| `departments` | Self-referencing tree, drives escalation levels 1–2 | parent_department_id, head_user_id |
| `wards` | Administrative polygons; clustering never crosses one. Also supplies escalation level 3 | boundary (MultiPolygon), officer_user_id |
| `categories` | Configuration, not code | merge_radius_m, default_sla_hours, severity_weight, **low_conf_action**, **max_extent_multiplier** |
| `reports` | **Immutable** citizen observations | location (Point), gps_accuracy_m, photo_url, cluster_decision, cluster_distance_m, **projected_extent_m** |
| `issues` | **Mutable** municipal work items | centroid (Point), sum_w, sum_wx, sum_wy, **max_member_dist_m**, report_count, distinct_reporter_count, status, due_at, escalation_level, **resolved_without_verification** |
| `issue_status_history` | Every transition, who and when | from_status, to_status, actor_id |
| `escalation_events` | One row per (issue, level); the unique constraint *is* the idempotency guarantee | issue_id, level, UNIQUE(issue_id, level) |
| `verifications` | Citizen fixed / not-fixed verdicts | verdict, UNIQUE(issue_id, citizen_id) |
| `notifications` | Outbound queue and in-app centre | type, read_at |
| `shedlock` | Scheduler lock state | |

**Why the separation matters:** a report is evidence and is never edited or deleted; an issue is work and changes constantly. Many reports map to one issue, and a report can be moved between issues by a moderator without losing its identity. This is what makes report count a valid priority signal, makes split and merge non-destructive, and gives citizen verification an addressable audience.

The three running-sum columns on `issues` make centroid updates O(1) instead of requiring a rescan of member reports on every incoming report. `max_member_dist_m` extends the same principle to cluster extent, so the extent cap introduced in revision 2 costs one column and one comparison rather than a rescan.

---

## 11. Core Workflows

### 11.1 Reporting and clustering

1. Citizen opens the report form. The browser returns coordinates plus an accuracy radius in metres.
2. Citizen takes a photo. The client downscales to 1600 px and compresses to roughly 300 KB before upload.
3. Citizen picks a category and optionally types a description. Submit.
4. Backend validates. If GPS accuracy is worse than 150 m, the request is rejected and the user is asked to place a pin manually.
5. Photo is uploaded to Cloudinary before any database transaction opens. A nightly job removes assets with no corresponding report row, since a failed transaction otherwise leaks storage.
6. Ward is resolved from the coordinates using `ST_Contains`.
7. A transaction-scoped advisory lock is taken on a hash of (category, ~200 m spatial cell). This serialises simultaneous reports of the same thing.
8. Candidate issues are queried: same category, same ward, open or recently resolved, within a generous search radius, ordered by distance, row-locked.
9. **After the row locks are held, each candidate's centroid and weight sum are re-read and the distance recomputed.** PostgreSQL evaluates ordering before acquiring locks, so a concurrent update in an adjacent spatial cell can have moved a centroid in between. The band decision uses only the re-read values.
10. The effective merge radius is computed from the category base radius, the new report's accuracy, and the existing cluster's positional uncertainty.
11. **The extent guard is evaluated before the band decision.** If merging would push the cluster's furthest-member distance past its configured multiple of the category radius, the merge is refused and a new flagged issue is created.
12. Decision: merge, merge-or-split according to the category's low-confidence policy, or create a new issue.
13. On merge, the running sums and `max_member_dist_m` update and the centroid is recalculated in constant time. Report count increments.
14. Priority is recomputed; the SLA deadline tightens if priority rose.
15. The report row is saved with a full audit of the clustering decision.
16. Transaction commits. After commit, an SSE event broadcasts the new count to every connected dashboard.
17. The citizen sees a response telling them honestly what happened: new issue, or merged and now the Nth report, with the distance to the cluster.

### 11.2 Assignment and work

Supervisor sees the department queue ordered by priority score then deadline. Acknowledges, assigns to a staff member. Staff member starts work. When finished, staff uploads a proof photo and a note; the system moves the issue to pending verification. Staff cannot move it further.

### 11.3 SLA escalation and priority ageing

A scheduled sweep runs every five minutes under a distributed lock. It performs two jobs.

First, escalation. It finds issues whose effective deadline has passed and whose clock is running, batching with `FOR UPDATE SKIP LOCKED`. For each, it resolves the next owner through an explicit four-level ladder, writes an escalation event, and advances the escalation level with a compare-and-swap update, capped at level 4. The deadline is re-armed at half the remaining time, floored at two hours.

Second, priority recomputation across all open issues. The priority score includes an age term, so an issue that never receives a second report would otherwise freeze at its creation-time score and never climb the queue. That is precisely backwards: a neglected issue is the one that should rise.

Three independent mechanisms guarantee that running this job twice produces the same result as running it once: the distributed scheduler lock, the unique constraint on (issue, level), and the conditional update.

### 11.4 Verification and closure

All eligible reporters on the issue receive a verification prompt. Each may vote once. If rejections meet or exceed confirmations, the issue reopens with a raised escalation level and a halved deadline. If confirmations meet quorum, or 72 hours pass with no rejection, the issue resolves. Seven days later it auto-closes.

An issue that resolves through the timeout with zero votes cast is flagged `resolved_without_verification`, and the rate of such closures is published per department. Section 12.6 explains why this is measured rather than fixed.

If a new report arrives at the same location within the reopen window (14 days, or 3 for mobile targets like stray animals), the resolved issue reopens automatically. A department cannot close a ticket, let the problem recur, and get a fresh clock.

### 11.5 Moderation

Low-confidence outcomes from any cause land in a supervisor review queue: optimistic merges near the band boundary, cautious splits in safety-critical categories, and merges refused by the extent cap. The supervisor sees the issue centroid and every member report as a separate pin with its accuracy circle. They can select a subset and split it into a new issue, or merge two issues. Both operations trigger full recomputation of the affected centroids and extents from the member reports, and both are logged. No report is ever deleted.

---

## 12. Design Revisions and Rationale

Six defects were identified in the revision 1 design before implementation began. Each is recorded here with the alternative rejected, because a design that has been reviewed and corrected is a stronger claim than one that has not.

### 12.1 Unbounded cluster drift

**Defect.** Leader clustering with a moving centroid admits chaining. Report A creates a cluster; B merges 20 m east and pulls the centroid 10 m east; C merges 20 m east of the *new* centroid. Along a linear defect such as a damaged road, every individual merge decision is locally correct while the cluster walks arbitrarily far from its origin. Revision 1 had no mechanism preventing this.

**Fix.** Maintain `max_member_dist_m` on the issue and refuse a merge whose projected extent would exceed `max_extent_multiplier × R_cat`. Refused merges create a flagged issue and enter the review queue. The update remains O(1).

**Alternative rejected.** Periodic re-clustering of oversized issues. Rejected because it reassigns cluster membership after ticket numbers have been issued, which is the same objection that rules out DBSCAN.

**Open question.** The default multiplier of 2.0 is not derived. It is stored as configuration and swept empirically in §19. If the data shows drift chaining does not occur at realistic report densities, the change will be removed and that removal reported as a result.

### 12.2 Low-confidence band always merged

**Defect.** Revision 1 argued that a wrong merge is cheaper than a wrong split because it costs one click to undo. That holds for potholes. It does not hold for `OPEN_MANHOLE`, which carries a six-hour SLA precisely because the hazard is severe. Silently folding a second open manhole into an existing ticket conceals a distinct hazard behind an incremented counter.

**Fix.** `categories.low_conf_action` takes `MERGE_FLAG` or `SPLIT_FLAG`. A category takes `SPLIT_FLAG` when a concealed duplicate is itself the harm, which holds in three cases: **safety-critical** hazards, where the hidden duplicate stays dangerous (`OPEN_MANHOLE`); **property-damage** categories, where it keeps causing damage (`WATER_LEAK`); and **mobile targets**, where spatial proximity is weak evidence that two reports even concern the same thing (`STRAY_ANIMAL`). Everything else keeps the optimistic path. Both outcomes reach the review queue, so no evidence is lost either way.

The third clause was added during implementation. Revision 2 stated the rule with two clauses, but a stray animal is neither safety-critical nor property damage, and merging two sightings on the strength of a shared street corner asserts an identity the data does not support. See `docs/DESIGN-DECISIONS.md`, DD-002.

**Alternative rejected.** A global switch. Rejected because the correct action genuinely differs by defect type, which is the same reasoning that already puts merge radius in the categories table.

### 12.3 Lock acquisition after ordering

**Defect.** PostgreSQL evaluates `ORDER BY` and `LIMIT` before acquiring row locks under `FOR UPDATE`. A concurrent transaction in an adjacent spatial cell can therefore move a candidate's centroid between the moment the ordering is computed and the moment the lock is taken, leaving the transaction holding a lock on a row whose distance is no longer the value it computed. The spatial advisory lock covers same-cell contention but not this case.

**Fix.** Re-read centroid and weight sum after the locks are held, recompute the distance, and base the band decision only on the re-read values.

**Alternative rejected.** Locking a 3×3 cell neighbourhood. Rejected on throughput grounds; the re-read is a few microseconds and eliminates the same class of error.

### 12.4 Priority froze for ageing issues

**Defect.** The priority score includes `0.15 × age_hours`, but revision 1 recomputed priority only on merge. An issue receiving exactly one report would hold its creation-time score forever and never rise in the queue, which inverts the intended behaviour: neglected issues are exactly the ones that should climb.

**Fix.** Recompute priority for all open issues in the existing five-minute sweep, which already scans that set.

**Alternative rejected.** Computing priority at query time as a derived expression. Rejected because the deadline-tightening rule depends on detecting a band *transition*, which requires a stored previous value.

### 12.5 Escalation ladder was not a tree walk

**Defect.** Revision 1 specified escalation as a walk up `departments.parent_department_id`, but level 3 of the published ladder is a ward officer, who is not a node in the department tree. The walk has no defined behaviour at that step. Separately, reopen-on-recurrence incremented `escalation_level` with no upper bound, so a chronically reopened issue would climb past the terminal level.

**Fix.** An explicit ordered resolver: level 1 department head, level 2 parent department head, level 3 ward officer from `wards.officer_user_id`, level 4 administrator and terminal. Every increment is capped at 4. Level 4 issues appear in a chronic-breach panel rather than escalating further.

**Alternative rejected.** Adding ward officers as synthetic department nodes. Rejected because it corrupts the department tree to preserve the elegance of a single traversal.

### 12.6 Anonymous-only issues can never be verified

**Defect.** Anonymous reporters cannot vote, to prevent ballot stuffing. Silence for 72 hours counts as consent. An issue reported only by anonymous users therefore has no eligible voters and always auto-resolves without verification, which is exactly the outcome the verification loop exists to prevent.

**Fix, and its limits.** This is not fixable without opening the abuse vector the restriction exists to close. Instead the outcome is flagged on the issue and published per department as an unverified-closure rate, alongside the reopen rate. A department with a high proportion of unverified closures is visible even though no individual closure can be challenged.

**Alternative rejected.** Allowing device-identified anonymous votes. Rejected because a device identifier is trivially resettable, which would make the verification signal weaker than no signal at all.

### 12.7 Database host would have expired mid-project

**Defect.** Revision 1 specified Render's free PostgreSQL tier. Render free databases expire thirty days after creation and are deleted after a fourteen-day grace period. Against an eight-week schedule this guarantees loss of the database, the seed corpus, and the generated ground-truth labels at roughly the halfway point, during the weeks with the least slack.

**Fix.** Supabase, which keeps free PostgreSQL running indefinitely, ships PostGIS preinstalled, and pauses only after a week of inactivity, which the existing keep-alive ping prevents. Neon is an equivalent alternative. A scripted weekly `pg_dump` is retained regardless, because no free tier is a durability guarantee.

**Consequence requiring care.** Both providers front PostgreSQL with a connection pooler in transaction mode. Hibernate's server-side prepared statements are incompatible with transaction pooling, and this system's correctness rests on `pg_advisory_xact_lock` and `FOR UPDATE` row locks. The application therefore connects to the direct endpoint on port 5432, never the pooled endpoint. Hikari is already the connection pool. Misconfiguring this produces intermittent failures indistinguishable from clustering bugs.

---

## 13. Functional Requirements

| ID | Requirement | Priority |
|---|---|---|
| FR-01 | The system shall accept a report consisting of photo, coordinates, accuracy, category, and optional description | M |
| FR-02 | The system shall reject reports with GPS accuracy worse than 150 m and offer manual pin placement | M |
| FR-03 | The system shall assign every report to exactly one issue at the time of submission | M |
| FR-04 | The system shall merge a report into an existing issue only if category, ward, and open-status preconditions all hold | M |
| FR-05 | The system shall compute the merge radius adaptively from category configuration and positional uncertainty | M |
| FR-06 | The system shall maintain an accuracy-weighted centroid updated in constant time per report | M |
| FR-07 | The system shall re-verify candidate distance after acquiring row locks and before deciding | M |
| FR-08 | The system shall refuse any merge that would push cluster extent beyond its configured bound | M |
| FR-09 | The system shall apply the category's configured action for reports falling in the low-confidence band | M |
| FR-10 | The system shall flag every low-confidence and extent-capped outcome for supervisor review | M |
| FR-11 | The system shall guarantee that concurrent reports of the same problem produce exactly one issue | M |
| FR-12 | The system shall assign a resolution deadline to every issue based on category and priority | M |
| FR-13 | The system shall recompute priority for all open issues on a fixed interval | M |
| FR-14 | The system shall escalate breached issues through a four-level ladder terminating at administrator | M |
| FR-15 | The system shall ensure escalation is idempotent under repeated or concurrent job execution | M |
| FR-16 | The system shall prevent staff from transitioning an issue to resolved or closed | M |
| FR-17 | The system shall require a proof photo before an issue may enter pending verification | M |
| FR-18 | The system shall solicit verification from all eligible reporters of an issue claimed as fixed | M |
| FR-19 | The system shall reopen an issue when citizen rejections meet or exceed confirmations | M |
| FR-20 | The system shall reopen a resolved issue when a new report arrives within the reopen window | M |
| FR-21 | The system shall flag and publish issues resolved without any citizen verification | M |
| FR-22 | The system shall record every status transition with actor and timestamp | M |
| FR-23 | The system shall allow supervisors to split and merge issues without deleting reports | S |
| FR-24 | The system shall expose ward and department performance metrics without authentication | M |
| FR-25 | The system shall push live issue updates to connected clients | S |
| FR-26 | The system shall rate-limit reports per device and per account | S |
| FR-27 | The system shall scope staff and supervisor actions to their own department | M |

---

## 14. Non-Functional Requirements

| Category | Requirement | Target |
|---|---|---|
| **Performance** | Report ingest end to end (excluding photo upload) | p95 < 150 ms at 50,000 issues |
| | Clustering candidate query | < 10 ms, index-backed |
| | Map viewport query | < 300 ms for 500 markers |
| | Dashboard aggregate queries | < 500 ms over 90 days of data |
| **Scalability** | Issue volume supported without restructuring | 500k+; the clustering query is index-bound, not linear |
| | Concurrency ceiling | Serialisation occurs only within a ~200 m spatial cell per category |
| **Availability** | Backend uptime during evaluation | Best effort on free tier; CDS plus keep-alive ping mitigates cold starts |
| **Reliability** | Scheduled job correctness | Idempotent by construction; repeated execution is safe |
| | Data integrity | All multi-step operations transactional; no partial cluster updates |
| | Data durability | Weekly scripted `pg_dump`; free-tier hosting is not a durability guarantee |
| **Security** | Password storage | BCrypt, cost factor 12 |
| | Transport | HTTPS enforced by both hosts |
| | Authorisation | Role check plus department/ward scope check on every mutating endpoint |
| | Injection | Parameterised queries throughout; no string-concatenated SQL |
| **Privacy** | Reporter identity | Hidden from public and from staff; visible to supervisors and above |
| | Anonymous reporting | Supported, device-throttled |
| **Usability** | Report submission | Under 20 seconds, three steps, one required photo |
| | Mobile | Responsive down to 360 px width |
| **Accessibility** | Contrast and semantics | WCAG AA colour contrast; status never conveyed by colour alone |
| **Maintainability** | Configuration | Merge radii, SLAs, extent multipliers, and band policies live in the database |
| | Test coverage | Every clustering and state-machine rule covered by an integration test |
| **Observability** | Health | Actuator health endpoint |
| | Metrics | Micrometer timers on the ingest path, feeding the evaluation directly |
| | Auditability | Complete status history and clustering decision trail per report |

---

## 15. Security and Privacy Design

**Authentication.** Stateless JWT issued and validated through Spring Security's OAuth2 resource server support, with short-lived access tokens (15 minutes) and longer refresh tokens (30 days). Passwords hashed with BCrypt at cost 12. Using the framework's `JwtDecoder` and `JwtAuthenticationConverter` rather than a hand-written filter removes an entire category of authentication bug from the project.

**Authorisation in two layers.** Role checks via `@PreAuthorize` handle "can this kind of user do this kind of thing." A separate guard bean handles "is this specific user allowed to touch this specific issue," checking department membership, assignment, and, for verification, whether the user actually reported the issue in question. Role alone is insufficient: a sanitation staff member must not be able to act on a roads ticket.

**Rate limiting.** One report per device per category per five minutes within 50 m. Twenty reports per account per day. Login attempts throttled.

**Anti-abuse.** Perceptual image hashing detects a citizen resubmitting the same photo and, more usefully, detects a staff member reusing one "after" photo across several resolutions. EXIF capture timestamps days older than submission raise a soft flag. Priority scoring uses distinct reporters with logarithmic scaling, so one street cannot buy priority by volume.

**Privacy.** Reporter identity is never exposed publicly or to field staff. Anonymous reports store only a device identifier used for throttling. Photo uploads can optionally pass through a face-blur transformation. No personal data appears in URLs or query strings.

**Input handling.** All requests validated with Bean Validation before reaching a service. File uploads are MIME-sniffed from actual bytes rather than trusting the declared content type, and are size-capped. Errors return RFC 7807 problem documents via Spring's `ProblemDetail`, without stack traces.

---

## 16. Development Process

**Repository layout.** Monorepo with `/backend` and `/frontend`. Backend follows a feature-package structure (`clustering`, `issue`, `sla`, `report`, `seed`) rather than a layer-package one, so related code lives together.

**Branching.** `main` always deployable, feature branches per module, pull requests with at least one review.

**Continuous integration.** GitHub Actions on every push: Maven build, full test suite including Testcontainers integration tests against a single reused PostGIS container, then a deploy hook to Render on merge to `main`. Vercel deploys the frontend automatically with preview deployments on pull requests.

**Design decision log.** `docs/DESIGN-DECISIONS.md` records every non-obvious call with the alternative rejected. Section 12 of this report is generated from it.

**Definition of done.** Code merged, integration test passing, endpoint documented in the OpenAPI spec, visible in the deployed environment.

---

## 17. Timeline

Eight weeks, with a milestone review at the end of week 4.

| Week | Focus | Deliverable |
|---|---|---|
| 1 | Setup and schema | Repo, CI, Flyway migrations, entities, GeoFactory, PostGIS wired locally and on Supabase, health check deployed, ward-containment canary test |
| 2 | **Clustering engine and seed generator** | Candidate query, weighted centroid, adaptive radius, extent cap, post-lock re-read, advisory locking, full test suite including the concurrency test; deterministic seed corpus with ground-truth labels |
| 3 | State machine, SLA, auth | Transition policy, guards, status history, escalation ladder, sweep job with ShedLock, priority ageing, idempotency tests, JWT and RBAC |
| 4 | Frontend core and deployment | Report flow, map, issue detail, staff queue, **read-only cluster inspector**, public URLs live, seeded data |
| — | **Milestone review** | |
| 5 | Verification | Quorum, timeout sweep, reopen on rejection, unverified-closure flagging, notification centre |
| 6 | Moderation and dashboard | Split/merge tool, review queue, full public dashboard, SSE with heartbeat |
| 7 | **Evaluation** | Precision/recall/F1, merge-radius sweep, latency versus scale, extent-cap ablation |
| 8 | Report and rehearsal | IEEE paper, deck, demo profile, three full rehearsals, recorded backup |

Two scheduling decisions differ from revision 1 and are deliberate.

**The seed generator moved from week 11 to week 2.** It is a prerequisite for the latency evaluation, for the clustering F1 evaluation, and for developing any screen against realistic data rather than an empty table. Generating ground-truth labels alongside the corpus costs nothing at generation time and replaces two days of hand-labelling later.

**The cluster inspector moved from week 10 to week 4.** It is the screen that makes the contribution legible in a single glance, and in an eight-week project anything scheduled for the final fortnight is a feature that has already been cut.

Week 2 cannot be compressed. Weeks 5 through 8 can be, in that order of preference.

---

## 18. Work Split (adjust to team size)

| Area | Responsibility |
|---|---|
| **Backend core** | Schema and migrations, clustering engine, state machine, SLA and escalation, tests |
| **Backend platform** | Auth and RBAC, photo pipeline, notifications, SSE, dashboard aggregation queries, deployment |
| **Frontend** | Report flow, map and Leaflet integration, cluster inspector, issue views, staff and supervisor screens |
| **Data and evaluation** | Ward boundary polygons, seed data generator and label emission, evaluation scripts and plots, dashboard design, documentation and report |

Everyone should be able to explain the clustering algorithm regardless of who wrote it, including why the extent cap exists. That is the question the panel will ask, and it will not be directed at whoever volunteers.

---

## 19. Testing Strategy

| Level | Tooling | Coverage |
|---|---|---|
| Unit | JUnit 5, AssertJ | Centroid maths, radius computation, extent projection, priority scoring, quorum evaluation, transition policy |
| Integration | Spring Boot Test + Testcontainers (real PostGIS, single reused instance) | Clustering decisions, ward containment, escalation idempotency, ladder resolution, query plans |
| Web | MockMvc + Spring Security Test | Endpoint contracts, authorisation, validation errors |
| Concurrency | Multi-threaded tests with `CountDownLatch` | N simultaneous identical reports produce exactly one issue for N in {2,5,10,20,50}; concurrent escalation produces one event |
| Manual | Deployed environment, multiple physical phones | The actual demo scenario, end to end |

The highest-value single test asserts that twenty concurrent identical reports produce exactly one issue with a count of twenty. It is also the test most likely to fail first.

Tests added in revision 2:

- **`driftIsBounded`** — thirty reports, each 20 m further east than the last. Asserts no cluster's extent exceeds its cap and that more than one issue results.
- **Band policy** — a report in the 1.0–1.5 R_eff band merges under `MERGE_FLAG` and creates a second issue under `SPLIT_FLAG`, with the same geometry.
- **Ladder resolution** — an issue at level 3 escalates to the ward officer, not a department head, and level 4 does not increment further.
- **Priority ageing** — an untouched issue's band rises over simulated time.
- **Staff closure impossibility** — iterating every state, a STAFF transition to RESOLVED is rejected in all cases.

A regression test asserts that `EXPLAIN` on the clustering query shows an index scan, so that if anyone later modifies the index definition the build fails rather than the demo becoming slow.

---

## 20. Evaluation Plan

Four measurements. All are cheap because the seed generator emits ground-truth labels from week 2.

**Clustering quality.** Run the pipeline over the labelled corpus and report precision, recall, and F1. Sweep the merge radius from 10 m to 100 m in 5 m steps and plot F1 against radius per category, which turns the 25 m and 50 m defaults from assertions into empirically justified choices.

**Ingest latency versus scale.** Measure p50/p95/p99 at 1k, 10k, and 50k existing issues, driven by k6 and measured server-side by the Micrometer timer on the ingest path. Reporting both figures is deliberate: the gap between them is network and photo upload, which is the part that is not our design. The expectation is near-flat server-side latency, demonstrating that the composite GiST index keeps the candidate query sublinear.

**Concurrency correctness.** N simultaneous identical reports for N in {2, 5, 10, 20, 50}, confirming exactly one issue each time.

**Extent-cap ablation.** Run the clustering pipeline with the extent cap enabled and disabled over a corpus containing linear defects, and compare F1. This is the experiment that decides whether §12.1 was a real defect or a hypothetical one. If the cap does not improve F1 at realistic report densities, it will be removed and that removal reported. A design change that was tested and rejected is still a result, and a more honest one than a change adopted on argument alone.

These four plots are what an IEEE-format paper needs in its evaluation section.

---

## 21. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Render free-tier cold start during the live demo | High | High | CDS-enabled startup, keep-alive ping every 10 min, warm up 5 min before presenting, recorded backup video |
| Clustering concurrency bug surfaces on stage | Medium | High | The concurrency test is a week-2 blocker, not a week-8 nice-to-have |
| Latitude/longitude swapped somewhere in the stack | High | High | Single `GeoFactory`, plus a week-1 test asserting a known coordinate falls inside a known ward |
| Connecting through the managed transaction pooler | Medium | High | Direct endpoint documented in config with an inline comment; symptoms would otherwise be misdiagnosed as clustering bugs |
| Connection pool exhaustion under concurrency testing | Medium | High | `open-in-view=false` set in week 1; Hikari capped at 8 with leak detection |
| Query falls back to sequential scan after an index change | Low | Medium | `EXPLAIN` assertion test in CI |
| Venue network too slow for photo uploads | Medium | Medium | Client-side compression to ~300 KB, mobile hotspot, recorded backup |
| Free-tier hosting terms change mid-project | Medium | High | Weekly `pg_dump`; terms verified at project start and reverified at the milestone review |
| Scope creep into SMS, native apps, or ML triage | High | High | All three are on the future-work list and stay there |
| Team member unable to explain the core algorithm | Medium | High | Whole team walks through the clustering flow together in week 3 |

Removed from revision 1: the risk of database expiry is retired, having been converted into the design change in §12.7 rather than left as a hazard to be watched.

---

## 22. Limitations

Stating these honestly is stronger than pretending they do not exist.

1. **Single-pass assignment is order-dependent.** Two clusters that later prove to be one cannot be merged automatically. The split-and-merge moderation tool is the designed mitigation, not an afterthought.
2. **The extent cap is a heuristic with an undetermined constant.** 2.0 is a starting value, not a derived one. Section 20 sweeps it, and the change will be withdrawn if the data does not support it.
3. **Cell-boundary races are theoretically possible.** Two reports straddling the advisory-lock cell boundary could produce duplicate issues. Probability is low (the cell is roughly eight times the largest merge radius) and the failure mode is benign, landing in the review queue. Locking a 3×3 cell neighbourhood would eliminate it at a throughput cost.
4. **Ward boundaries are approximated.** Real municipal boundary data is not publicly available in usable form, so seeded polygons are approximate.
5. **Anonymous-only issues cannot be verified.** Measured and published rather than solved; see §12.6.
6. **Verification depends on citizen participation.** The 72-hour consent timeout exists precisely because participation cannot be assumed, but it means some fixes are marked resolved without genuine confirmation.
7. **Evaluation uses generated rather than real reports.** Ground-truth labels are exact because the corpus is synthetic, which makes the F1 figure an upper bound on real-world performance rather than an estimate of it.
8. **No integration with existing municipal systems.** In real deployment this would be the largest engineering effort and the largest political one.
9. **Free-tier hosting is not production-grade.** Cold starts, connection limits, no redundancy, and terms that change without notice.

---

## 23. Future Scope

| Extension | Value |
|---|---|
| Native mobile apps with offline capture and background sync | Reporting in areas with poor connectivity |
| SMS and IVR reporting channels | Reaches citizens without smartphones, a large fraction of the affected population |
| ML-based photo classification | Auto-suggest category, detect miscategorised or fraudulent photos |
| Constrained re-clustering | Periodically merge clusters that should have been one, preserving the older ticket number so cluster identity remains stable. This is the principled fix for the order-dependence limitation |
| Trajectory clustering for mobile targets | Stray animals and waterlogging move; a point-cluster model is a poor fit |
| Predictive maintenance | Recurrence patterns per location identify chronic infrastructure failure rather than repeated symptom treatment |
| Multilingual interface | Hindi and Punjabi for the target deployment region |
| Budget linkage | Tie resolution records to expenditure, exposing cost per resolved issue |
| Open data API | Publish anonymised issue data for researchers and journalists |
| Contractor accountability | Extend the verification loop to third-party contractors with performance-linked payment |

---

## 24. Glossary

| Term | Meaning |
|---|---|
| **Report** | An immutable citizen observation with its own photo, coordinates, and accuracy |
| **Issue** | A mutable municipal work item aggregating one or more reports |
| **Centroid** | The accuracy-weighted mean position of an issue's member reports |
| **Merge radius** | The distance threshold within which a new report joins an existing issue |
| **Effective radius** | The merge radius after adjustment for GPS accuracy and cluster uncertainty |
| **Cluster extent** | Distance from the centroid to the furthest member report |
| **Extent cap** | The configured bound on cluster extent, expressed as a multiple of the category merge radius |
| **Drift chaining** | Progressive migration of a cluster centroid through a sequence of individually valid merges |
| **Band action** | The per-category policy determining whether a low-confidence match merges or splits |
| **SLA** | Service level agreement; the resolution deadline attached to an issue |
| **Breach** | The state of an issue whose deadline has passed while its clock was running |
| **Escalation level** | An integer recording how far up the ownership ladder an issue has been pushed |
| **Reopen window** | The period after resolution during which a new nearby report reopens the issue |
| **Quorum** | The number of citizen confirmations required to mark an issue resolved |
| **Unverified closure** | An issue resolved through the 72-hour timeout with zero votes cast |
| **Idempotent** | An operation that produces the same result whether executed once or many times |
| **GiST index** | The PostgreSQL index type used for spatial queries |
| **btree_gist** | The extension permitting scalar columns inside a GiST index |
| **PostGIS** | The spatial extension to PostgreSQL |
| **JTS** | Java Topology Suite; the geometry library Hibernate Spatial maps to |

---

## 25. Deliverables Checklist

- [ ] Deployed backend with a public URL and Swagger documentation
- [ ] Deployed frontend with a public URL
- [ ] Supabase database with PostGIS and btree_gist enabled, seeded historical data, weekly dump script
- [ ] Source repository with README, setup instructions, and `docs/DESIGN-DECISIONS.md`
- [ ] Test suite passing in CI, including concurrency, idempotency, drift-bound, and band-policy tests
- [ ] Architecture diagram, ER diagram, and state machine diagram as vector graphics
- [ ] Evaluation results: precision/recall/F1, merge-radius sweep, latency curve, extent-cap ablation
- [ ] Project report in IEEE format
- [ ] Presentation deck
- [ ] Demo script rehearsed three times, plus a recorded backup
