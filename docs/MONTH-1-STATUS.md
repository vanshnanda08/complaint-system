# CivicTrack — month 1

Written for the month-one review, and written to be checkable rather than
flattering. Everything below is either a URL you can open or a number a command
prints.

**Live now**

| | |
|---|---|
| Frontend | https://civic-track-six.vercel.app |
| API | https://civictrack-api.onrender.com |
| API docs | https://civictrack-api.onrender.com/swagger-ui/index.html |

Deployed corpus: **~110 issues** from **250 reports**, across eight of the nine
statuses. It was 802 issues from 2,000 reports until phase 5 -- reduced
deliberately, and the reduction is what exposed DD-047: the old corpus only
ever contained two statuses, which 800 rows of scrolling had hidden.

---

## What works

**The clustering engine, which is the project's actual contribution.** Reports
arriving near each other in the same category and ward merge into one issue by
an accuracy-weighted centroid, under an adaptive radius that tightens as
evidence accumulates: `R_cat + ½·accuracy + ½·σ`, where σ is the cluster's own
positional uncertainty, `1/√Σw`. Twenty reports at 10 m accuracy give about
2 m; two give about 7 m. A well-evidenced cluster earns a tighter radius with
nothing to tune.

Concurrency is handled rather than hoped for. Three phones reporting the same
pothole simultaneously produce **one** issue, not three, via a transaction-scoped
advisory lock on a hash of (category, ~200 m cell) plus a two-phase candidate
lookup that re-reads distances after row locks are granted. `ClusteringConcurrencyIT`
runs twenty concurrent submissions and asserts a single issue results.

Cluster extent is capped at a per-category multiple of the base radius, so a
moving centroid cannot chain along a linear defect through a sequence of
individually valid merges (DD-001).

**The state machine, and specifically what it refuses.** There is no transition
from any staff-reachable state to RESOLVED or CLOSED. Staff submit for citizen
verification and stop. This is enforced in three independent places:
`TransitionPolicy` refuses to construct if the table violates it,
`TransitionPolicyTest` attempts a STAFF move to RESOLVED from all nine states
and requires all nine to be refused, and the work view renders no such action.

**The SLA clock, including the part that stops.** Deadlines derive from category
hours scaled by priority band. The clock pauses during PENDING_VERIFICATION,
because the department is waiting on citizens and should not be charged for the
delay — visible in the interface as "Clock paused", not as a ticking countdown.
Breach escalates through a four-rung ladder (department head → parent department
head → ward officer → administrator), capped, idempotent under concurrent
sweeps three ways over.

**A frontend that encodes status three ways.** Colour, shape and word — never
colour alone. Nine statuses share eight colour tokens deliberately, and the
greyscale screenshot is the test of whether that works. It caught a real defect
during development: RESOLVED was rendering with no left rule, so it carried two
encodings instead of three, invisibly, because the green glyph and green word
carried the row.

**Deployment.** Docker image with a Class Data Sharing archive trained and
verified at build time; measured cold start **3.5 seconds** against 20–40 for an
uninstrumented Spring Boot. The build asserts the archive loads with
`-Xshare:on`, so a broken archive fails the build rather than silently costing
twenty seconds nobody attributes.

---

## What is stubbed or absent

Stated plainly, because a reviewer should see a boundary rather than discover a
gap.

| Not built | Where it lands |
|---|---|
| Citizen verification screen (`/me/verify/[id]`) — the quorum service and DB schema exist; the screen and the vote endpoint do not | Phase 6 |
| Notifications — table exists, nothing writes to it | Phase 6 |
| Supervisor routes: assignment board, review queue, split/merge tool | Phase 7 |
| Dashboard aggregates — median resolution, SLA compliance, reopen rate, backlog histogram. The tiles render their specified empty state saying what they will show | Phase 7 |
| SSE live updates | Phase 7 |
| Clustering evaluation against the ground-truth labels | Phase 8 |
| Cloudinary server-side upload; photos currently carry a placeholder URL | Phase 5, outstanding |
| Rate limiting and anti-abuse | Phase 9 |

**The most honest gap:** an acknowledged issue cannot progress through the UI,
because the next step is assignment and that is a supervisor action in phase 7.
The work view says so explicitly rather than offering a button the server would
refuse. The full lifecycle is reachable through the API and is exercised by
`tools/table.mjs`.

---

## What the numbers are

| | |
|---|---|
| Backend tests | **166**, Testcontainers against real PostGIS 17 |
| Frontend tests | **53** unit |
| Design decisions recorded | **40** |
| Report flow, open to ticket | **3.0 s** (1.6 Mbps, 150 ms RTT, 4× CPU throttle) |
| `/report` initial JS | 297 KB gzipped — over the revised budget; Zod and React Hook Form are ~96 KB of it |
| Leaflet on the composer's happy path | **0 KB** — loads only when manual pin placement is forced |
| Cold start | 3.5 s application; ~50 s including Render's container start after idle |

Every test claiming to guard a behaviour was verified by breaking that behaviour
and watching it go red. Where a mutation left the suite green, that was treated
as a finding and the test was fixed.

---

## What went wrong, and what it taught

Three failures during this month were the same shape: **a passing test that
verified nothing.**

**A stale `target/` directory produced two confidently wrong diagnoses of a
query that was correct all along** (DD-027). The suite was green throughout,
because Testcontainers rebuilds the schema every run.

**The client's transition table disagreed with the server's, and its unit test
asserted the same wrong table** (DD-035). Every "Start work" click on an
acknowledged issue returned 409. The test passed because it was written from the
same assumption as the code.

**Editing an applied migration — only a comment — broke startup on every
database that had already run it** (DD-039). `mvn clean test` stayed green,
because a fresh container has no history to mismatch.

The lesson they share: where code mirrors an external system's rules, a unit
test pins the code to its own statement of those rules and cannot tell you
whether that statement is true. `tools/` now holds four harnesses that check
against the running server — API contract, transition table, per-state UI, and a
runtime-error sweep — and each exists because it caught something a green suite
had endorsed.

---

## Month 2

Phases 6 through 9: the verification loop that closes the accountability
argument, supervisor moderation, the public dashboard's real aggregates with
SSE, the clustering evaluation against the ground-truth labels, and anti-abuse.

The evaluation is the one that matters academically — the corpus and its labels
already exist and are backed up, so the measurement is a matter of running it
rather than of building anything new.
