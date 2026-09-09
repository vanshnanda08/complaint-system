# Phase 5 — manual verification

Standing rule 5: every phase ends by printing what you should check by hand.
This is that list for phase 5, the one-month milestone.

Each item says what to do, what you should see, and — where it matters — what
it would look like if it were broken. An item you cannot tell the difference on
is not a check.

Everything below has been run once already; the point of doing it again is that
you see it, not that it passes.

---

## 1. The deployed site (5 minutes)

Open **https://civic-track-six.vercel.app**.

| Do this | You should see |
|---|---|
| Land on `/` | The overdue count in the page's first paragraph, already there — not a spinner that resolves. It is server-rendered on purpose |
| `/issues` | The list, with a status pill on every row |
| Squint, or take a greyscale screenshot | You can still tell the statuses apart. Three of them share one colour, so if you cannot read them in greyscale the encoding is broken |
| `/map` | Pins, and panning refetches for the new box |
| `/dashboard` | The overdue tile filled red. If it is ever **green**, that is wrong — green means resolved in this system |
| Open any issue | A history list, with timestamps in ascending order |
| `/report` on a phone, or a 360px window | Content starts immediately. The menu is behind the hamburger. If you see a full-screen stack of nav links before any content, the mobile nav has regressed |
| Toggle dark mode, then reload | It stays dark, and **does not flash light first**. The flash is what the pre-paint script in `layout.tsx` exists to prevent |
| In dark mode, revisit `/issues` | Status colours are still readable. They were 1.92–3.70 contrast before DD-041 — every one below AA |

**Then check the keep-alive.** Actions → **Keep-alive** → *Run workflow*. It
should go green in well under a minute and its log should show
`health -> 200` and `public/issues -> 200`. It runs every ten minutes on its
own; GitHub does not promise punctuality for scheduled runs, so it reduces cold
starts rather than eliminating them (DD-049).

**Sign in** as `commissioner@civictrack.example`. The password is the one set in
Render's `CIVICTRACK_DEMO_STAFF_PASSWORD`.

| Do this | You should see |
|---|---|
| `/staff/queue` | Work items, with the two tabs |
| Open a NEW issue from the queue | Exactly one action: "Acknowledge". Not a row of greyed-out buttons — actions the state machine forbids are **absent**, not disabled |
| Acknowledge it | The action changes to what comes next, and a history row appears |
| Try an issue from another department as a non-admin supervisor | 403, and the page says so. That is `@issueGuard` working, not a bug |

**Then check what is NOT in the browser.** DevTools → Application → Local
Storage and Session Storage. There must be **no access token and no refresh
token**. The access token lives in memory; the refresh token is in an httpOnly
cookie a script cannot read. If you find a token in storage, that is a real
finding.

---

## 2. The corpus is now ~110 issues, not ~800

This changed in phase 5 at your request, **on the live database as well as
locally**. `corpus-size` in `application-seed.yml` is **250 reports**, which
the clustering engine merges into 110 issues.

The pre-reduction dump is kept as
`backups/civictrack-2026-09-09-pre-reduction-802-issues.dump.gz` — the only
copy of the 802-issue corpus that exists, since the reduction truncated it. It
was verified restorable before the truncate, not after.

Check the spread is still varied — this is the thing that was broken and got
fixed (DD-047):

```bash
docker exec civictrack-db psql -U civictrack -d civictrack \
  -c "SELECT status, count(*) FROM issues GROUP BY status ORDER BY 2 DESC;"
```

You should see **eight** statuses, not two. Expect roughly:

```
 NEW 24 | RESOLVED 26 | CLOSED 23 | ACKNOWLEDGED 10
 ASSIGNED 9 | IN_PROGRESS 9 | PENDING_VERIFICATION 6 | REJECTED 3
```

REOPENED is absent on purpose. It needs citizen verification records, which
arrive in phase 6. Faking them would mean a corpus that lies about the
verification loop.

---

## 3. The backup, and the restore

**This is the item most worth your attention**, because it was the one that was
actually broken. The documented restore procedure did not work and
`pg_restore` reported success anyway (DD-045).

```bash
./scripts/backup.sh          # writes backups/civictrack-<date>.dump.gz
./scripts/restore.sh --self-test
```

The self-test restores the newest dump into a throwaway container and prints
row counts for seven tables. You want **`SELF-TEST PASSED`** and no table
showing `MISSING` or `0`.

To convince yourself the check is real rather than decorative, break it: delete
the `psql -c "$EXTENSIONS"` line from the self-test block in
`scripts/restore.sh` and run it again. It goes red with `issues MISSING` —
while `pg_restore` still calls that a warning and exits 0. Put the line back.

**Run this monthly.** An untested backup is a file, not a backup, and that was
true of this one for a month.

---

## 4. Local development still works from a clean start

```bash
docker compose up -d
cd backend && mvn spring-boot:run \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--civictrack.demo.staff-password=demo1234 \
                               --civictrack.cors.allowed-origins=http://localhost:3000"
cd frontend && npm run dev
```

On the `demo` profile SLAs are compressed to about three minutes, so **nearly
everything reads as overdue**. That is the profile working, not a bug. Run
without it for realistic deadlines, at the cost of not being able to log in.

If routes 404 for everything, the dev server's `.next` cache is stale — stop
it, `rm -rf frontend/.next`, start it again. (This bit me during phase 5.)

---

## 5. The test suites

```bash
cd backend  && mvn -B clean test     # 166 tests, real PostGIS via Testcontainers
cd frontend && npm run lint && npm test && npm run build
```

`mvn clean` is not hygiene — a stale `target/` served old compiled SQL once and
produced two wrong diagnoses of a working query (DD-027).

`npm run build` matters more than `npm run dev`: the React Compiler rules fail
the build and not the dev server.

---

## 6. The four verification harnesses

These catch things the test suites structurally cannot, because they need the
real server. Both servers up, then from a directory with Playwright installed:

```bash
node tools/contract.mjs frontend/src/lib/types.ts   # 12 types, 0 mismatches
node tools/table.mjs                                 # NEW -> PENDING_VERIFICATION, no 4xx
node tools/uiwalk.mjs                                # the action offered at each status
node tools/sweep.mjs                                 # every route, signed out and in
```

For `sweep.mjs`, three routes are **meant** to appear under ERRORS —
`/does-not-exist`, the all-zeros UUID, and `CT-1999-000001`. They are the
deliberate 404s. Anything else flagged is real.

---

## 7. CI — the one thing still red

**https://github.com/vanshnanda08/complaint-system/actions**

The frontend job passes. The backend job fails at "Build and test", and has on
every run. The log is not readable without admin rights on the repository, so
phase 5 added a step that writes the failure onto the **run page itself**.

Open the newest run and read the summary at the top. It will name the failing
test, or — if the build died before any test ran — show the tail of the Maven
log. **That is the one piece of information still needed**, and it now comes to
you rather than having to be dug out.

For context on what has already been ruled out: the same 166 tests pass on
macOS arm64, inside a Linux container as root under `TZ=UTC`, and against the
identical `postgis/postgis:17-3.4` image CI uses. The failure is not in the
test code (DD-048).

---

## 8. Point Render at `main` — 30 seconds, and worth doing

Render still builds from **`phase-4-frontend-and-public-api`**, not `main`.

Right now that is harmless because every push in this session went to both
branches, so they are identical. It is a trap rather than a bug: the day
somebody pushes to `main` alone, Vercel deploys the new frontend and Render
keeps serving the old API, and the two halves disagree with nothing announcing
it.

Render → `civictrack-api` → Settings → Branch → `main` → Save.

Until that is done, treat "push to `main`" as "push to both":

```bash
git push origin main && git push origin main:phase-4-frontend-and-public-api
```

---

## What is deliberately NOT done at one month

Not oversights — decisions, each with a reason:

| | Why |
|---|---|
| Cloudinary server-side upload | Needs a cloud name and an unsigned preset from you. The composer uploads client-side today and falls back to a placeholder when unconfigured |
| A scheduled backup | The cron line is in `scripts/backup.sh`. A laptop cron only fires when the laptop is awake, so it is a weaker guarantee than it appears — your call whether that is worth having |
| REOPENED in the corpus | Needs verification records; phase 6 |
| A reproducible corpus | Needs a fixed clock and seeded ids. Real changes to how the app is wired, and phase 8 depends on it, so it belongs before phase 8 rather than inside a corpus-size change (DD-046) |
| Dashboard aggregate tiles | Phase 7, with SSE. They render their own empty states rather than zeros |
