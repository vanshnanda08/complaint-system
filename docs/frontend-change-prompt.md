# Frontend change prompt

Paste this into a fresh session whenever you want a change made to the
CivicTrack frontend. It is written to be self-contained: it assumes the reader
has never seen this project.

Replace the line marked **THE CHANGE** with what you actually want. Everything
else stays as it is.

---

````markdown
# CivicTrack — frontend change

**THE CHANGE:** <describe what you want changed here>

Work only inside `frontend/`. Read the rules below before touching anything;
several of them exist because the exact mistake they forbid has already been
made once in this project and cost a debugging session.

## 1. Scope — what you may not touch

**Do not edit anything outside `frontend/`.** In particular:

| Path | Why it is off limits |
|---|---|
| `backend/**` | This is a frontend task. If the change genuinely needs a new field or endpoint, stop and say so rather than adding one. |
| `backend/src/main/resources/db/migration/**` | Flyway checksums an applied migration. Editing one breaks startup against every existing database, and the test suite will not catch it because Testcontainers builds a fresh database every run. (DD-039) |
| `.github/workflows/ci.yml` | CI is currently red for a pre-existing backend reason. Do not "fix" it as a side effect of a frontend change. |
| `scripts/**`, `backups/**` | Operational. Nothing about a UI change belongs here. |
| `frontend/.env.local` | Local config, gitignored. Read it if you need to; never commit it, never print secrets from it. |

Inside `frontend/`, these are also off limits:

| File | Why |
|---|---|
| `eslint.config.mjs` | It enforces the Leaflet import boundary. If a lint rule blocks you, the code is wrong, not the rule. Do not relax it, do not add an eslint-disable comment to get past it. |
| `next.config.ts` | The Turbopack root is pinned deliberately; unpinning it makes module resolution depend on whose machine it is. |
| `package.json` — the `next`, `react`, `react-dom` versions | Pinned to a working React 19 + Next 16 + React Compiler combination. Adding a *new* dependency is fine if you say why; upgrading these three is not. |

## 2. The four load-bearing files

You may edit these, but each has a rule that is not obvious from reading it:

**`src/lib/transitions.ts`** — mirrors the backend's `TransitionPolicy`. It
decides which action button a staff user is shown. If it says a transition is
legal and the server disagrees, the button 409s every time. This has already
happened: the client offered "Start work" on an ACKNOWLEDGED issue and the
server refused it. Its unit test was written from the same wrong assumption as
the code, so the suite stayed green throughout. If you change this file, check
it against `backend/src/main/java/com/civictrack/issue/policy/TransitionPolicy.java` —
the server
is the authority — and do not trust `transitions.test.ts` as evidence. (DD-035)

**`src/lib/status.ts`** — the single source of the status vocabulary. Nine
statuses, eight colour tokens: ACKNOWLEDGED, ASSIGNED and IN_PROGRESS
deliberately share one colour, and the *word* is what separates them. Nothing
else in the codebase may write a status string or pick a status colour. Do not
add a ninth colour to make the counts line up.

**`src/lib/types.ts`** — hand-written mirrors of the backend's DTOs. TypeScript
cannot check these: a fetch response is `any` until something asserts a type,
and the assertion can simply be wrong. A renamed field here fails silently at
runtime as `undefined`, not at compile time. If you change a type, verify it
against a real response body from the running server.

**`src/components/map/MapCanvasInner.tsx`** — the only module in the codebase
allowed to import Leaflet. Leaflet touches `window` at module scope, so
importing it anywhere else breaks the *server* render of whatever page
transitively reached it, and the error names a route with no visible connection
to maps. Everything else uses `<MapCanvas>` from `@/components/MapCanvas`,
which loads this one dynamically with `ssr: false`.

## 3. Free to change

Everything else: all pages under `src/app/`, all components under
`src/components/` (except `map/`), the design tokens in `src/app/globals.css`,
copy, layout, spacing, `format.ts`, `categoryGlyphs.tsx`. Change these freely.

Two conventions in `globals.css` that are decisions, not accidents:

- **Colour means status and nothing else.** There is no brand accent, no hero
  gradient, no tinted illustration. If something needs emphasis and is not
  encoding a status, use weight, size, rule or space.
- **Status is never encoded by colour alone** — always colour AND shape AND
  word, because three statuses share a colour.

## 4. Traps that will bite you

These are build failures and runtime bugs this project has already paid for:

- **The React Compiler lint rules fail the build, not just the lint.** No
  `Date.now()` or `Math.random()` during render — use the shared
  `useNow()` from `src/lib/useNow.ts`, which is one timer behind
  `useSyncExternalStore` rather than one per component. No `setState` in an
  effect body — use lazy initial state. No ref writes during render.
- **`useSearchParams()` requires a `<Suspense>` boundary** or `next build`
  fails. Four pages already wrap it; copy that shape.
- **An effect that both depends on a callback and calls it will loop forever.**
  This already shipped once and made the composer submit successfully (201) and
  then never navigate. Wrap callbacks in `useCallback` and use functional
  state updates.
- **Never render a remote photo with a bare `<img>`.** Use
  `src/components/Photo.tsx`, which degrades to "Photo unavailable". Seed data
  contains URLs that 404 by design. (DD-037)
- **Effective deadline is `dueAt + pausedSeconds`, not `dueAt`.** The SLA clock
  genuinely pauses during PENDING_VERIFICATION, and a countdown that keeps
  running on a paused clock is lying. `DeadlineCountdown` already handles this.
- **Public and staff endpoints return different shapes.** `/public/issues/{id}`
  returns `PublicIssue`; `/issues/{id}` returns `StaffIssue`. They are not
  interchangeable, and typing one as the other produced a
  `RangeError: Invalid time value` on the staff work view. (DD-036)

## 5. Running it locally

Three terminals. The frontend alone will render, but every page that fetches
will error, so start the backend too.

```bash
# 1 — database
docker compose up -d                     # PostGIS on 5432

# 2 — backend, seeded and demo-profile
cd backend && mvn spring-boot:run \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--civictrack.demo.staff-password=demo1234 \
                               --civictrack.cors.allowed-origins=http://localhost:3000"

# 3 — frontend
cd frontend && npm install && npm run dev     # http://localhost:3000
```

The `demo` profile does two things: it sets a password on the seeded staff
accounts (the migration ships them without one, deliberately — a migration is
the wrong place for credentials), and it compresses SLAs to about three
minutes so breaches are visible immediately. The side effect is that nearly
every seeded issue reads as overdue. That is expected on this profile.

Logins, all with `demo1234`:

| Role | Email |
|---|---|
| ADMIN | `commissioner@civictrack.example` |
| SUPERVISOR | `head.roads@civictrack.example` |
| STAFF | `crew.roads@civictrack.example` |

There is no seeded citizen login. Register at `/register` to exercise
`/me/reports`. Reporting itself needs no account at all.

If the backend will not start because port 8080 is taken, add
`--server.port=0` and read the chosen port from the log, then set
`NEXT_PUBLIC_API_BASE` and `API_BASE` in `frontend/.env.local` to match.

If you cannot run the backend, say so and work against the deployed API by
pointing both env vars at the Render host — but then do not claim you verified
anything that needs seeded data.

## 6. Before you tell me it is done

Run all three. The build is the one that matters most, because the React
Compiler rules fail there and not in `dev`:

```bash
cd frontend
npm run lint
npm test          # 53 vitest tests
npm run build
```

Then, only if the change touched what each one guards:

- **Status colours, badges, or the issue row** — screenshot `/issues` and
  `/staff/queue` **in greyscale**. If you cannot tell the statuses apart, the
  encoding is broken. Pay attention to the three that share a colour.
- **Staff actions or transitions** — run `node tools/table.mjs` and
  `node tools/uiwalk.mjs` with both servers up. These walk a real issue through
  the state machine and catch a client offering an action the server refuses,
  which no unit test in this project can see.
- **Types or a fetch call** — run `node tools/contract.mjs frontend/src/lib/types.ts`,
  which compares the interfaces against the JSON the endpoints actually return.
- **Any route** — run `node tools/sweep.mjs`, which loads every route signed
  out and signed in and fails on a runtime error or on `NaN`/`undefined`
  reaching the screen.

The three browser-driving harnesses (`uiwalk`, `sweep`, and `table`'s browser
leg) need Playwright available — `npx playwright install chromium` once. If it
is not installed and you choose not to install it, say which checks you
therefore did not run rather than reporting the change as verified.
- **Layout** — confirm it is usable at 360px with no horizontal scroll, and
  that every interactive element is keyboard reachable with a visible focus
  ring.

A passing test is not evidence in this project until you have seen it fail. If
you add or change a test, break the thing it guards, watch it go red, restore
it, and tell me what you saw. If a mutation leaves the suite green, that is a
finding to report, not something to quietly fix.

## 7. How to show me the change

1. `git status --short` and `git diff --stat` first, so I can see the shape.
2. Then `git diff` for the files that matter.
3. Tell me which URLs to open on `localhost:3000` to see it, and what I should
   be looking at on each.
4. Say plainly what you did **not** do, and anything you were unsure about.

**Do not commit unless I ask.** Do not `git add -A` — stage named files.
**Never push to `main`.**

Why `main` matters: Vercel builds production from `main`, so a push there is a
live deploy. The working branch is `phase-4-frontend-and-public-api`, and a
push to it produces a Vercel *Preview* only — the live site is untouched.

One thing that will look alarming and is not your fault: **CI is currently red
on this branch for a pre-existing backend reason.** If you push and the run
fails, check that it failed the same way it already was failing before you
assume you broke it.
````
