# Phase 5 — what is left

> [!NOTE]
> **Superseded, mostly.** This was written when five items were outstanding.
> Four are now closed: `main` has everything merged and Vercel deploys from it,
> the backup's restore path was found to be broken and is fixed and tested
> (DD-045), the corpus was reduced to ~110 issues with a real status spread
> (DD-047), and the phase 5 verification checklist is at
> [PHASE-5-VERIFICATION.md](PHASE-5-VERIFICATION.md).
>
> What is genuinely left needs you: the **CI failure** (its reason now prints
> on the run page — see DD-048), **Cloudinary** credentials, and a decision on
> a **keep-alive ping** and a **backup schedule**. The "traps already paid for"
> section below is still worth reading.



A handoff prompt. Written to be pasted into a fresh session that has no memory
of how any of this got here.

Nine of phase 5's fourteen deliverables are done and live. This file covers the
five that are not, plus the context a cold start needs in order not to repeat
mistakes that have already been made and recorded.

---

## Read these first

- `docs/DESIGN-DECISIONS.md` — forty entries. **DD-027, DD-035, DD-039 and
  DD-040 are not optional reading**: each records a failure that a passing test
  suite endorsed, and three of them cost hours.
- `docs/civictrack-claude-code-prompts.md` — authoritative phase numbering, and
  the per-phase manual verification steps.
- `docs/MONTH-1-STATUS.md` — what works, what is stubbed, and the measured
  numbers.
- `tools/README.md` — four harnesses that check what unit tests structurally
  cannot, because they need the running server.

## Standing rules that apply to everything below

1. Every tunable number lives in the `categories` table, not in code.
2. Every distance uses `ST_Distance(...::geography)`. Never planar.
3. Exactly one class constructs a JTS `Point`: `GeoFactory`.
4. Tests are written alongside features, not after.
5. At the end of each phase, print what the user should verify manually.
6. Status changes go through `IssueStatusService.transition` and nowhere else.

And the rule that matters most here, learned the hard way three times this
month: **a test that passes is not evidence until its discriminating power has
been demonstrated.** Break the behaviour it guards, watch it go red, restore it.
Apply that to fixes as well as to tests — a fix that appears to work is not
evidence either, because a stale build produces exactly that symptom.

---

## Current state

| | |
|---|---|
| Frontend | https://civic-track-six.vercel.app (Vercel, builds from `main`) |
| API | https://civictrack-api.onrender.com (Render, Docker, builds from `phase-4-frontend-and-public-api`) |
| API docs | https://civictrack-api.onrender.com/swagger-ui/index.html |
| Database | Supabase, PostgreSQL 17.6 + PostGIS 3.3.7, seeded with 802 issues / 2,000 reports |
| Tests | 166 backend, 53 frontend — all green locally |

Local development:

```bash
docker compose up -d
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--civictrack.demo.staff-password=demo1234 \
                               --civictrack.cors.allowed-origins=http://localhost:3000"
cd frontend && npm run dev
```

**Always `mvn clean test`, never incremental, and restart `next start` after
`npm run build`.** Stale artefacts on both toolchains produced confidently wrong
diagnoses during phase 4 (DD-027).

---

## The five remaining items

### 1. CI is red — fix it first, everything else is gated on this

`.github/workflows/ci.yml` exists, is syntactically valid, and its frontend job
was dry-run locally. It has run **once**, on `phase-4-frontend-and-public-api`,
and **failed**. It has never passed.

The failure has not been diagnosed. Get the failing job and step from
https://github.com/vanshnanda08/complaint-system/actions — the repository is
public, so `gh run view` or the Actions UI both work.

Two candidates worth checking before anything else, both of which would be
genuine environment differences rather than broken code, since the suite is
green locally:

- `ClusteringQueryPlanIT` asserts the composite GiST index appears in the query
  plan at 50k rows. A CI runner's planner, with different memory and statistics,
  may legitimately choose otherwise.
- `ClusteringConcurrencyIT` runs twenty concurrent submissions against an
  advisory lock. A constrained runner changes the timing.

If either is the cause, the fix is to make the test robust to the environment
without weakening what it asserts — not to delete it and not to skip it in CI. A
test that only runs on a laptop guards nothing.

Also check `actions/upload-artifact@v4` on the surefire reports: it fails when
the path does not exist, which it will not if the build died before tests ran.

### 2. Merge everything to `main`, then point Render at `main`

`main` is **7 commits behind** and has none of phase 5 — no CI workflow, no
architecture doc, no month-1 status, no backup script, and not even
`application-deploy.yml`.

Render currently builds from `phase-4-frontend-and-public-api` and Vercel from
`main`. That split works only by accident, because the frontend has not changed
since the merge.

**Do not merge while CI is red.** Once green:

1. Open a PR from `phase-4-frontend-and-public-api` to `main`, merge it.
2. Render → `civictrack-api` → Settings → change the deploy branch to `main`.
3. That push exercises Vercel's auto-deploy and CI's deploy hook for the first
   time — both are currently unverified.

### 3. Cloudinary — needs the user

**Blocked.** Requires a cloud name and an unsigned upload preset from the user.

Today the frontend uploads client-side with an unsigned preset, or falls back to
a placeholder URL when unconfigured (`src/lib/uploadPhoto.ts`). The backend takes
`{ photoUrl }` as a string and has no Cloudinary code at all.

What phase 5 asks for:

- Server-side upload with transformation.
- **The upload must happen BEFORE the ingest transaction opens.** A slow
  third-party call must never be made while holding the clustering advisory lock
  — `ReportController` already documents this and DD-024 records the same
  principle for the display-name lookups.
- A nightly job deleting Cloudinary assets with no corresponding report row.
  Failed submissions otherwise leak storage indefinitely.

The frontend already degrades when an image fails to load (`Photo` component,
DD-037), so a deleted asset shows "Photo unavailable" rather than a broken icon.

### 4. cron-job.org keep-alive — needs the user

**Blocked.** Requires an account.

Render's free tier sleeps after 15 minutes idle; the first request afterwards
takes roughly 50 seconds. A 10-minute ping to
`https://civictrack-api.onrender.com/actuator/health` prevents it.

Worth telling the user plainly: this is optional. The cost of skipping it is a
slow first load, not a broken system, and a keep-alive ping on a free tier is a
small abuse of someone's generosity. Their call.

### 5. Phase 5 verification checklist — standing rule 5

Phase 4 has a "Manual verification" section in
`docs/civictrack-claude-code-prompts.md`. Phase 5 does not. Write one covering:
both public URLs, credentials for all four roles, a cold-start observation, a
staff login and transition against the deployed stack, and a backup-and-restore
round trip.

---

## Two smaller loose ends

**The backup runs manually.** `scripts/backup.sh` works — it has produced a
verified 320 KB custom-format dump of all eleven tables. Nothing schedules it.
The spec says weekly, which means a cron entry on a machine that is actually
running at that time. A laptop asleep on Sunday at 02:00 backs nothing up. Raise
it as a decision rather than adding a cron line that will silently never fire.

**A restore has never been tested.** The script prints restore instructions and
verifies the dump's magic bytes, but nobody has restored it into an empty
database and confirmed the application starts against the result. Until that has
been done once, it is a file of unknown value. Note that restoring over a
populated database leaves Flyway's schema history and the actual schema
disagreeing, which fails at next startup.

---

## Traps already paid for — do not rediscover these

**Supabase connections.** Use the **session pooler**:
`aws-0-<region>.pooler.supabase.com:5432`, user `postgres.<project-ref>`. The
direct host `db.<ref>.supabase.co` is IPv6-only and unreachable from Render's
free tier — it fails with `Network is unreachable`. Port 6543 on the pooler is
transaction mode and breaks Hibernate's prepared statements. **The port is not
the tell; the hostname is** (DD-040). Supabase's UI shows the direct string by
default, including right after a password reset, which is exactly when it gets
pasted in wrongly.

**Applied migrations are immutable, including their comments.** Flyway checksums
the file. Editing `V1__baseline.sql` to fix a stale comment broke startup on
every database that had already run it, and `mvn clean test` stayed green
throughout because Testcontainers builds a fresh schema every run (DD-039).

**Where the client mirrors a server rule, a unit test is necessary and not
sufficient.** `src/lib/transitions.ts` mirrors `TransitionPolicy`. It was wrong,
and its unit test asserted the same wrong table, so it passed and verified
nothing (DD-035). `tools/table.mjs` and `tools/uiwalk.mjs` check against the
running server, which is the only thing that can.

**`GET /api/v1/issues/{id}` returns `IssueDto`, not `PublicIssueDto`.** They are
different records with different fields. Typing the staff view against the wrong
one produced an intermittent `RangeError: Invalid time value` (DD-036).
`tools/contract.mjs` compares every declared TypeScript interface against the
JSON the live API actually returns.

**Never put a credential on a command line.** `scripts/seed-remote.sh` and
`scripts/backup.sh` both read from `backend/.env.seed`, which is gitignored.
Command lines reach shell history, `ps` output, and screenshots — all three
happened during phase 5.

---

## What to ask the user for

1. The CI failure — which job, which step (they can read the Actions tab).
2. Cloudinary cloud name and unsigned upload preset, if they want real photo
   upload this phase.
3. Whether they want the cron-job.org keep-alive at all.
4. Where the weekly backup should actually run, if anywhere.

Everything else can proceed unattended.
