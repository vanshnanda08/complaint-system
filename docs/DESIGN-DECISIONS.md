# CivicTrack — Design Decision Log

Every non-obvious call made in this project, recorded as: the defect or
question, why it matters, the fix chosen, and the alternative rejected.

This file is the source; §12 of `civictrack-project-report.md` is generated
from it. It is appended to as the project proceeds, not rewritten — a decision
that was later reversed stays here with its reversal recorded underneath,
because a design that has been reviewed and corrected is a stronger claim than
one that has not.

Entries DD-001 through DD-006 are the six defects found in the pre-implementation
review of the specification in `docs/`. They are referenced by ID in migration
comments and in code, so that anyone reading `V1__baseline.sql` and wondering
why `max_member_dist_m` exists has one place to look.

---

## Index

| ID | Decision | Status |
|---|---|---|
| DD-001 | Bound cluster spatial extent | Adopted, constant pending empirical sweep |
| DD-002 | Low-confidence band action is per-category | Adopted |
| DD-003 | Re-verify candidate distance after locking | Adopted |
| DD-004 | Recompute priority in the scheduled sweep | Adopted |
| DD-005 | Explicit ordered escalation resolver, capped at 4 | Adopted |
| DD-006 | Anonymous-only issues: measure, do not fix | Adopted as a measurement |
| DD-007 | Spring Security resource server, not a hand-written JWT filter | Adopted |
| DD-008 | Records with static factories, not MapStruct | Adopted |
| DD-009 | `open-in-view=false` | Adopted |
| DD-010 | Composite `btree_gist` index over the cast expression | Adopted |
| DD-011 | One `GeoFactory`, and no distance arithmetic in Java | Adopted |
| DD-012 | Class Data Sharing in the runtime image | Adopted |
| DD-013 | Reference data ships as a Flyway migration, not an admin UI | Adopted |
| DD-014 | Testcontainers finds Colima's socket via an auto-activating Maven profile | Adopted |
| DD-015 | Actuator exposes more endpoints than the security chain permits | **Open — decide in phase 5** |

---

## DD-001 — Unbounded cluster drift

**The defect.** Leader clustering with a moving centroid admits chaining.
Report A creates a cluster. Report B arrives 20 m east and merges, pulling the
centroid ~10 m east. Report C arrives 20 m east of the *new* centroid and
merges too. Along a linear defect — a damaged stretch of road, a drain that
backs up along its line — every individual merge decision is locally valid
while the cluster as a whole walks arbitrarily far from where it started. The
specification had no mechanism preventing this, and the failure is silent:
nothing in the audit trail flags it, because no single merge was wrong.

**Why it matters.** A cluster that has walked 300 m down a road is a work item
whose centroid points at a location where there is no defect. The crew is
dispatched to the wrong place, and the ticket's own geometry no longer
describes the problem it represents.

**The fix.** Maintain `issues.max_member_dist_m`, the distance from the
centroid to the furthest member report. Before merging, compute the extent the
cluster *would* have if the merge happened, and refuse the merge if that
projected extent exceeds `categories.max_extent_multiplier × merge_radius_m`.
A refused merge creates a new issue flagged for supervisor review. The update
stays O(1): one extra column, one comparison, no rescan of member reports.

The projected extent is recorded on the report row (`reports.projected_extent_m`)
whether or not the merge went ahead, so the ablation experiment can be run over
the audit trail rather than by re-running the whole pipeline.

**The alternative rejected.** Periodic re-clustering of oversized issues.
Rejected because it reassigns cluster membership *after* ticket numbers have
been issued to citizens — which is precisely the objection that rules out
DBSCAN in the first place. A fix that reintroduces the problem it was meant to
avoid is not a fix.

**Implementation finding: the obvious test does not exercise this at all.**
The first `driftIsBounded` test placed thirty reports at a fixed 20 m spacing
along a line, on the assumption that each merge would be locally valid. It was
not: with uniform spacing, after k reports the centroid sits at the arithmetic
mean, `(k-1) x 10 m`, while the arriving report is at `k x 20 m`. The gap is
`10k + 10`, which grows without limit and crosses the band ceiling by the fourth
or fifth report. The cluster splits on **distance**, and the extent cap is never
consulted — the test passed its "extent stayed bounded" assertion while
measuring something else entirely.

Real chaining needs each report to arrive near the *current* centroid rather
than at a fixed spacing. Placing each one a constant step `s` east of wherever
the centroid has moved to gives `c_n = c_(n-1) + s/n`, so the centroid marches
east as `s x H(n)`, the harmonic series: unbounded, but only logarithmically.
With `s = 20 m` the 50 m POTHOLE cap binds around the seventh or eighth report.

Two consequences. First, the test suite now holds both cases, with the uniform
line pinned as a **negative** control asserting the cap does *not* fire, so that
nobody later mistakes it for evidence. Second, and more importantly for the
sweep: **a corpus of evenly spaced reports along a road cannot measure this
feature.** The generator must place reports relative to the evolving cluster, or
in spatially clustered arrival orders, or the ablation will compare the cap
against a scenario that never invokes it and conclude it does nothing. That is a
plausible way to get a wrong negative result and delete a feature that works.

Note also that the logarithmic growth rate is itself an argument to check before
trusting: drift of `s x ln(n)` means an issue needs on the order of `e^(cap/s)`
reports to breach the cap. Whether real report densities reach that is exactly
what the sweep has to answer.

**Open question, to be resolved with data.** The default multiplier of 2.0 is
not derived from anything. It is stored as configuration and swept empirically
in the evaluation. If the data shows that drift chaining does not occur at
realistic report densities, the cap will be removed and that removal reported
as a result. A change that was tested and withdrawn is still a finding, and a
more honest one than a change adopted on argument alone.

---

## DD-002 — The low-confidence band always merged

**The defect.** The specification argues that in the ambiguous band between
`R_eff` and `1.5 × R_eff`, merging is the cheaper error: a wrong merge costs
one click to split, a wrong split leaves two tickets nobody notices. That
reasoning is sound for potholes. It is wrong for `OPEN_MANHOLE`, which carries
a six-hour SLA precisely because the hazard is severe. Folding a second open
manhole into an existing ticket does not create a data-quality problem that a
supervisor can tidy up later; it conceals a distinct physical hazard behind an
incremented counter, and the second manhole stays open while the first one gets
fixed and the ticket closes.

**Why it matters.** The cost asymmetry that justifies optimistic merging is a
property of the defect type, not of the algorithm. Hard-coding one answer for
all categories applies pothole economics to a safety hazard.

**The fix.** `categories.low_conf_action`, taking `MERGE_FLAG` or `SPLIT_FLAG`.
Safety-critical categories take `SPLIT_FLAG` and produce a separate flagged
issue; everything else keeps the optimistic path. Both outcomes land in the
supervisor review queue, so no evidence is lost in either direction — the
difference is only which way the system errs while a human is not looking.

Currently `SPLIT_FLAG`: `OPEN_MANHOLE`, `STRAY_ANIMAL`. The second is there for
a different reason — the target moves, so spatial proximity is weaker evidence
of identity than it is for fixed infrastructure.

### Which categories actually take `SPLIT_FLAG`, and why the spec disagreed

A review of the seeded values against the specification found the two out of
step, in both directions. Recording it here because the discrepancy is in the
*rule*, not in a single row, and because the provenance turned out to matter.

**What the specification actually says.** `OPEN_MANHOLE` is the only category
any document names. Report §12.2 states the rule as "safety-critical **and
property-damage** categories take `SPLIT_FLAG`", and names no others.
`WATER_LEAK` appears exactly once in the whole document set — in the blueprint's
merge-radius table — with nothing said about band policy. There was never an
instruction assigning it a band action, so the seeded value is not a migration
error and not a deviation from a stated requirement.

**Where the seed and the rule diverge.** Measured against the report's own
two-clause rule, the seed is wrong on both sides:

| Category | Seeded | Report's rule implies | Gap |
|---|---|---|---|
| `OPEN_MANHOLE` | `SPLIT_FLAG` | `SPLIT_FLAG` | agrees |
| `WATER_LEAK` | `MERGE_FLAG` | `SPLIT_FLAG` — property damage | seed too permissive |
| `STRAY_ANIMAL` | `SPLIT_FLAG` | `MERGE_FLAG` — neither clause applies | rule too narrow |

`WATER_LEAK` is the clearest property-damage category in the set: severity 20,
the highest after `OPEN_MANHOLE`, and a 24-hour SLA. A second leak folded into
an existing ticket goes unrepaired while the first is fixed and the ticket
closes, which is the same failure mode that justifies the manhole case, differing
in consequence rather than in kind.

`STRAY_ANIMAL` is neither safety-critical nor property damage. It was given
`SPLIT_FLAG` on a **third** rationale that the report's rule does not cover: the
target moves, so co-location is weaker evidence that two reports concern the
same animal than it is that two reports concern the same pothole. For a mobile
target, proximity is close to no evidence of identity at all.

**The resolution.** The rule gains a third clause rather than the seed losing a
row, because the mobile-target argument is sound and dropping it would make the
system merge sightings of different animals on the strength of a shared street
corner. The rule is therefore:

> A category takes `SPLIT_FLAG` when a concealed duplicate is itself the harm.
> That holds in three cases: **safety-critical** hazards, where the hidden
> duplicate stays dangerous; **property-damage** categories, where it keeps
> causing damage; and **mobile targets**, where spatial proximity is weak
> evidence of identity in the first place.

Report §12.2 has been updated to state this, so the specification and the seed
now describe the same system.

**The alternative rejected.** A single global switch. Rejected because the
correct action genuinely differs by defect type, which is the same reasoning
that already puts `merge_radius_m` in the categories table rather than in a
constant. Having accepted that argument once, applying it here is consistency,
not new design.

---

## DD-003 — Row locks are acquired after ordering

**The defect.** PostgreSQL evaluates `ORDER BY` and `LIMIT` *before* acquiring
row locks under `FOR UPDATE`. The candidate query orders issues by distance,
takes the nearest five, and locks them — but between the moment the ordering is
computed and the moment the lock is granted, a concurrent transaction can
commit a centroid update to one of those rows. The transaction then holds a
lock on a row whose distance is no longer the number it computed, and makes a
band decision on stale data.

The spatial advisory lock does not cover this. It serialises reports within the
same ~200 m cell and category, but a cluster's centroid can be moved by a
report in an *adjacent* cell, which the lock deliberately allows to proceed in
parallel.

**Why it matters.** It is a genuine race, and the symptom — an occasional
merge or split that the recorded distance does not justify — is almost
impossible to distinguish from an ordinary clustering misjudgement. It would be
found, if at all, by someone puzzling over the audit trail months later.

**The fix.** After the locks are held, re-read `centroid` and `sum_w` for the
locked candidates, recompute the distance, and base the band decision only on
the re-read values. The recorded `cluster_distance_m` is the re-read distance,
so the audit trail reflects what the decision was actually made on.

**The alternative rejected.** Locking the 3×3 cell neighbourhood around the
report, which would eliminate the race by serialising every transaction that
could possibly move a relevant centroid. Rejected on throughput grounds: it
multiplies the serialised area ninefold to close a race that a few microseconds
of re-reading closes just as completely.

---

## DD-004 — Priority froze for ageing issues

**The defect.** The priority score includes a `0.15 × age_hours` term, but
priority was recomputed only on merge. An issue that receives exactly one
report — which is most issues — holds its creation-time score forever and never
climbs the queue.

**Why it matters.** This inverts the intended behaviour precisely. The age term
exists so that neglected issues rise; recomputing only on merge means the only
issues that age are the ones already receiving attention. A single-report issue
in a quiet ward would sit at the bottom of the queue indefinitely, which is the
exact failure the whole system exists to prevent.

**The fix.** Recompute priority for all open issues inside the existing
five-minute SLA sweep, which already scans that set under a distributed lock.
The sweep therefore does two jobs: escalate breaches, and re-band priorities.
No new job, no new lock, no new schedule.

**The alternative rejected.** Computing priority at query time as a derived SQL
expression, which would make it always current for free. Rejected because the
deadline-tightening rule depends on detecting a band *transition* — priority
rising from MEDIUM to HIGH shortens `due_at` — and detecting a transition
requires a stored previous value to compare against. A derived expression has
no previous value.

---

## DD-005 — The escalation ladder was not a tree walk

**The defect, in two parts.** First, the specification describes escalation as
a walk up `departments.parent_department_id`, but level 3 of the published
ladder is a ward officer, who is not a node in the department tree. The walk
has no defined behaviour at that step. Second, reopen-on-recurrence increments
`escalation_level` with no upper bound, so a chronically reopened issue climbs
past the terminal level and off the end of the ladder.

**Why it matters.** The first is an unimplementable specification — the code
would have had to invent something at level 3, and whatever it invented would
have diverged from the documented ladder. The second is a live bug: level 7
resolves to nobody, so a chronically reopened issue silently loses its owner,
which is the opposite of what escalation is for.

**The fix.** An explicit ordered resolver rather than a traversal:

| Level | Owner | Source |
|---|---|---|
| 1 | Department head | `departments.head_user_id` |
| 2 | Parent department head | `departments.parent_department_id` → `head_user_id` |
| 3 | Ward officer | `wards.officer_user_id` |
| 4 | Administrator | terminal |

Every increment — from SLA breach and from reopen alike — goes through the same
resolver and is capped at 4. The cap is enforced twice: in the resolver, and by
a `CHECK (escalation_level BETWEEN 0 AND 4)` constraint on the issues table, so
that a code path that forgets the cap fails loudly at the database rather than
producing an unowned issue. Level 4 issues appear in a chronic-breach panel on
the public dashboard instead of escalating further.

**The alternative rejected.** Adding ward officers to the department tree as
synthetic nodes, which would preserve the single elegant traversal. Rejected
because it corrupts the department hierarchy — a real thing with real meaning
for assignment and access control — in order to keep one algorithm tidy. The
ladder is four steps long; an explicit list of four is not worse than a loop.

---

## DD-006 — Anonymous-only issues can never be verified

**The defect.** Anonymous reporters cannot vote in verification, to prevent
ballot stuffing. Silence for 72 hours counts as consent. An issue reported only
by anonymous users therefore has no eligible voters at all, and always
auto-resolves through the timeout — which is exactly the outcome the
verification loop exists to prevent. The two rules are individually correct and
jointly produce a hole.

**Why it matters.** Anonymous reporting is not a marginal path. It is the
lowest-friction way to report, and it is the option a citizen takes when they
do not trust the system enough to register with it — which correlates with
exactly the wards where accountability matters most.

**The fix, and its limits.** This one is not fixable without opening the abuse
vector the restriction exists to close, and that is stated plainly rather than
papered over. Instead the outcome is flagged on the issue
(`issues.resolved_without_verification`) and published per department as an
unverified-closure rate alongside the reopen rate. A department with a high
proportion of unverified closures is visible on a public page even though no
individual closure among them can be challenged.

The claim being made is narrower than "we solved it": the system converts an
unfalsifiable per-issue outcome into a falsifiable per-department statistic.

**The alternative rejected.** Allowing device-identified anonymous votes.
Rejected because a device identifier is trivially resettable, so the resulting
signal would be weaker than no signal at all — and worse, it would *look* like
verification on the dashboard. A misleading metric is worse than a missing one.

---

## DD-007 — Spring Security resource server, not a hand-written JWT filter

**The question.** The original stack specified `jjwt` plus a custom
`OncePerRequestFilter` to parse and validate tokens.

**The decision.** Use `spring-boot-starter-oauth2-resource-server`, which
supplies `JwtDecoder` and `JwtAuthenticationConverter`.

**Why.** Hand-written authentication filters are where security bugs live, and
the bugs are the boring, catastrophic kind: algorithm confusion (`alg: none`),
missing expiry checks, missing signature verification on a code path that
returns early, claims read before validation. The framework has already made
every one of those mistakes and fixed them. Writing the filter ourselves buys
no functionality and inherits none of that.

**The alternative rejected.** `jjwt` with a custom filter, which is what most
tutorials show. Rejected on the grounds above. This also removes three
dependencies from the build.

---

## DD-008 — DTOs as records with static factories, not MapStruct

**The decision.** Java 21 records with static `from(Entity)` factories.

**Why.** There are roughly fifteen DTOs in this system. MapStruct is an
annotation processor, and combining it with Lombok requires explicit
`annotationProcessorPaths` ordering — Lombok must generate getters before
MapStruct reads them, and when that ordering is wrong the error message points
at neither library. That is a real cost, paid on every build and every clean
checkout, against a benefit that fifteen hand-written factories do not justify.

A static factory is also the natural place to put the two rules that matter
here: never serialise a JTS geometry (Jackson emits the whole geometry graph
and clients choke), and never expose a reporter identity in a public DTO. Those
are decisions, not mappings, and a mapper that generates them from annotations
makes them harder to see, not easier.

**The alternative rejected.** MapStruct. Reasonable at fifty DTOs; not at
fifteen.

Lombok stays, but on entities only. Entities are the one place where the
getter/setter volume is genuinely mechanical.

---

## DD-009 — `spring.jpa.open-in-view=false`

**The decision.** Set it to false in phase 1, and do not change it.

**Why.** It defaults to *true*, which keeps a database connection bound to the
thread for the entire request lifecycle — including view rendering and JSON
serialisation, long after the transaction has committed. With Hikari capped at
8 on free-tier hosting, the 20-thread concurrency test in phase 2 exhausts the
pool. The failure does not present as pool exhaustion: it presents as threads
blocking inside the clustering path, which reads exactly like a locking bug in
the advisory-lock strategy. That is a day lost to debugging correct code.

Setting it false forces lazy-loading errors to surface at development time, in
the service layer where they belong, instead of silently holding connections.

**The alternative rejected.** Leaving the default and raising the pool size.
Rejected because the pool ceiling is imposed by the database host, not chosen
by us, so it cannot be raised.

---

## DD-010 — Composite `btree_gist` index over the cast expression

**The decision.** One partial GiST index on
`(category_code, ward_id, (centroid::geography))`, excluding terminal states,
rather than a plain GiST index on the geometry plus separate B-trees.

**Why.** The clustering candidate query filters on category and ward and then
does a spatial `ST_DWithin` — all three in one predicate, on every ingest, in
the request cycle. The `btree_gist` extension lets the two scalar columns live
inside the GiST index alongside the geography expression, so a single index
serves the whole query.

The cast must be *inside the index definition*. `ST_DWithin(a::geography, ...)`
cannot use an index built on the bare geometry: the expressions do not match,
and the planner falls back to a sequential scan. The degradation is from ~1 ms
to ~400 ms at 50k issues, and it is invisible until the dataset is large — it
will not show up on a developer laptop with 200 rows.

Storing `geometry` and casting at query time, rather than using a `geography`
column outright, keeps the Hibernate Spatial mapping clean and keeps the
bounding-box prefilter fast, while still yielding true geodesic metres.

**Guard.** A test asserts that `EXPLAIN` on the candidate query contains an
index scan. If someone later edits the index definition and drops the cast, CI
fails rather than the demo getting slow.

**The alternative rejected.** A `geography` column. Gives metres for free but
maps awkwardly through Hibernate and prefilters more slowly.

---

## DD-011 — One `GeoFactory`, and no distance arithmetic in Java

**The decision.** `com.civictrack.common.geo.GeoFactory` is the only place a
JTS `Point` is constructed. It takes `(lat, lng)` in human order and performs
the swap to JTS `(x, y)` order internally, once. It computes no distances.

**Why.** JTS orders coordinates `(x, y)` = `(longitude, latitude)`, the reverse
of how every input source states them. A swapped pair does not throw: a
Ludhiana coordinate reversed lands in the Indian Ocean and fails a ward lookup
several layers away from the swap, which is a bad place to start debugging. One
constructor means one place to get it right, and the factory range-checks its
arguments so that a swap fails immediately and by name.

The factory deliberately offers no distance method. Planar distance between
SRID 4326 points is in degrees, and a degree of longitude at Ludhiana's
latitude is ~96 km against ~111 km for a degree of latitude, so any metre
figure derived in Java would be silently wrong by a latitude-dependent factor.
All distances come from PostGIS as `ST_Distance(a::geography, b::geography)`.
Standing rule 2. The absence of the method is the enforcement.

**Note on the weighted centroid.** The running sums `sum_wx`/`sum_wy` *are*
accumulated in planar lon/lat, and that is a deliberate, bounded exception. The
anisotropy induces sub-millimetre centroid error over cluster extents under
150 m, and it never touches a decision — only the representative point. Every
comparison against a threshold is still geodesic.

---

## DD-012 — Class Data Sharing in the runtime image

**The decision.** A CDS training run in the Dockerfile, and
`-XX:SharedArchiveFile` on the runtime entrypoint.

**Why.** Cold start is the highest-likelihood demo risk in the project. Render's
free tier spins an instance down after 15 minutes idle, and an uninstrumented
Spring Boot cold start is 20-40 seconds. CDS pre-parses class metadata into a
memory-mappable archive so the JVM loads it instead of re-parsing on every boot.
It attacks the risk at its source rather than only mitigating it with a
keep-alive ping — both are used, because the ping fails if the venue network does.

**The wrinkle.** The training run refreshes the application context, and there
is no database at image-build time. A dedicated `cds` profile disables Flyway
and sets `hibernate.boot.allow_jdbc_metadata_access: false` so Hibernate does
not dial out for dialect metadata. That profile exists only for the training
run and never activates in a real environment.

**The trap, found by measuring rather than by reading.** The first working
version of the Dockerfile trained the archive on `eclipse-temurin:21-jdk` and
ran it on `eclipse-temurin:21-jre`. A dynamic CDS archive is tied to the base
archive of the *exact* JVM build that produced it, so the runtime JVM rejected
it. The default `-Xshare:auto` then fell back silently: the container booted
fine, the archive sat in the image unused, nothing in the logs mentioned it,
and the project would have carried a cold-start mitigation that did nothing
into the demo it exists to protect.

Two changes followed. The CDS stage now uses the same base image as the runtime
stage. And the build asserts the archive loads, by running it once under
`-Xshare:on`, which turns a silent fallback into a failed build. Production
still runs on the default `-Xshare:auto`, because there a graceful fallback
beats refusing to boot — but the build has already proved the fallback will not
be taken.

Measured on the phase-1 image, three runs each: **2.1-2.3 s with CDS against
2.9-3.2 s without**, roughly 30% off startup. Worth re-measuring at phase 11,
because the saving scales with class count and the application currently has
very few classes. Note also that a single run of each showed no difference at
all — page-cache and JIT warmup dominated the first sample. Any startup figure
quoted in the report needs more than one run behind it.

**The alternative rejected.** GraalVM native image. Far better cold start, but
it does not tolerate the reflection and dynamic proxying that Hibernate and
Spring Data use without substantial configuration, and the build is slow enough
to hurt iteration. Wrong trade for an eight-week project.

---

## DD-013 — Reference data ships as a Flyway migration

**The decision.** Departments, wards and categories are inserted by
`V2__reference_data.sql`. There is no admin CRUD screen for them in this build.

**Why.** These are ten-row configuration tables that change when the
municipality reorganises, which is to say almost never. A CRUD screen for them
demonstrates nothing that is not already demonstrated elsewhere and costs about
a week of an eight-week schedule. Shipping them as a migration also means every
environment — laptop, CI, Supabase — is provably identical, and a radius change
is a reviewed, versioned diff rather than an untracked production edit.

This is a scope decision, and it is recorded here rather than left implicit so
that "why is there no admin UI" has an answer that is a choice and not an
omission.

**The alternative rejected.** An admin configuration UI. It is on the
out-of-scope list in the project report for the same reason.

---

## DD-014 — Testcontainers and the Colima socket

**The problem.** Colima, the Homebrew container runtime, exposes its Docker
socket at `~/.colima/default/docker.sock` rather than `/var/run/docker.sock`.
Testcontainers does not find it and fails with "Could not find a valid Docker
environment" — an error that says nothing about sockets and sends people
looking at their test code.

**The decision.** A Maven profile in `backend/pom.xml` activated by the
*existence* of that socket file, setting `DOCKER_HOST` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` for the test JVM.

**Why this shape.** Activation by file existence means the profile is inert on
CI and on machines running Docker Desktop, so nothing machine-specific is
imposed on anyone who does not need it. The obvious alternatives are both
worse: hardcoding the path into the build breaks every other environment, and
documenting an `export` in the README makes a green build depend on a step
people forget — and when they forget it, the failure looks like a broken test
rather than a missing environment variable.

**The alternative rejected.** Symlinking Colima's socket to
`/var/run/docker.sock`, which is the fix most search results suggest. It
requires `sudo`, it is invisible to anyone reading the repository, and it has
to be redone whenever the runtime changes.

---

## DD-015 — Actuator exposure and the security whitelist disagree

**Status: open.** Recorded now, decided in **phase 3 of the build order**, when
authentication lands.

> **On the phase number, because multiple schemes are in play and they disagree.**
> The build order in `civictrack-java-blueprint.md` §14 (mirrored in the README)
> has eleven phases, and auth is phase 5. The delivery timeline in
> `civictrack-project-report.md` §17 has eight weeks, and auth is week 3.
> However, `docs/civictrack-claude-code-prompts.md` is the **authoritative
> prompt and build order document for this project**, and it explicitly lands
> auth in **Phase 3**. Therefore, we defer this decision to Phase 3, where
> the authentication mechanism is actually built.

**The current state.** `application.yml` exposes four actuator endpoints:

```yaml
management.endpoints.web.exposure.include: health,info,metrics,prometheus
```

`SecurityConfig` permits two of them anonymously:

```java
.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
```

So `/actuator/metrics` and `/actuator/prometheus` are **exposed but unreachable**.
They are registered, they are served by the actuator infrastructure, and the
security chain rejects every anonymous request to them. Since phase 3 has not
landed there is no authentication mechanism yet either, so at present they are
unreachable by anyone, through any means.

**Why this is being recorded rather than fixed.** The mismatch is not currently
causing a defect, and the tight whitelist is the correct default: an allow-list
that only names what is needed is much safer than one that is widened
speculatively, because endpoints get added to a chain far more often than they
get removed. Widening it now would be choosing an access policy for metrics
before there is any authentication to express that policy in terms of.

It is worth being clear about what is and is not at stake. `/actuator/metrics`
and especially `/actuator/prometheus` are an information-disclosure surface:
they publish request counts and latency distributions per endpoint, JVM and pool
internals, and — for this system — the ingest timer, from which report volume
and its timing could be inferred. That is not catastrophic for a public
accountability platform whose issue data is deliberately open, but it is real,
and it is the sort of thing that gets exposed by accident rather than decided.

**What must NOT be inferred from this entry.** That the ingest timer is not
recording. It is, and `IngestMetricsIT` proves it by asserting on the
`MeterRegistry` directly rather than through the HTTP endpoint — precisely so
that this access-control question cannot silently break the observability
guarantee the week-7 evaluation depends on. The metrics exist; only the HTTP
route to them is closed.

**The decision due in phase 3**, once `JwtDecoder` and role-based access are in
place, is one of:

1. Permit both anonymously, on the argument that a platform whose pitch is
   "everything here is public, no login" should not exempt its own operational
   numbers. Cheapest, and consistent with the project's stance elsewhere.
2. Require `ADMIN` for both. Most conservative, and the conventional choice.
3. Permit `/actuator/prometheus` only from the scrape source, and require
   `ADMIN` for `/actuator/metrics`. Correct in principle, but there is no
   scraper in this deployment, so it would be machinery for a need that does not
   exist yet.
4. Narrow the exposure list instead, dropping `prometheus` until something
   actually scrapes it. Worth considering seriously: an endpoint that nothing
   consumes is a surface with no offsetting benefit.

**The alternative rejected, for now.** Widening the whitelist in phase 2 to
match the exposure list. Rejected because it would resolve a security question
by making the configuration self-consistent rather than by deciding what access
these endpoints should have — the two are easy to confuse, and only one of them
is a decision.

---

## Appendix — standing rules

These are project-wide invariants, not decisions about a particular feature.
They are listed here because a reviewer looking for the rules should not have
to infer them from the code.

1. **Every tunable number lives in the `categories` table, not in code.** Merge
   radii, SLA hours, severity weights, extent multipliers, reopen windows, band
   policies. A magic number in a service is a bug report waiting to happen; a
   row in a table is configuration with a rationale.
2. **Every distance uses `ST_Distance(...::geography)`.** Never planar, never
   computed in Java. See DD-011.
3. **Exactly one class constructs a JTS `Point` from lat/lng: `GeoFactory`.**
   See DD-011.
4. **Tests are written alongside features, not after.** The concurrency test in
   particular is a phase-2 blocker, not a phase-8 nice-to-have — it is the test
   most likely to fail, and it is the one that finds the bug in the first
   implementation of the clustering engine.
5. **Status changes go through exactly one method.** `IssueStatusService.transition`
   is the only caller of the status setter, which is package-private for that
   reason. Enforced by visibility, not by convention.
