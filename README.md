# CivicTrack

Municipal issue reporting with incremental geo-clustering, SLA escalation and public
accountability.

Section references below (§3, §8, §17) point at the project's technical blueprint document,
which is kept outside this repository.

## Status — Week 1

Authentication slice is done and running: registration, login, JWT issue/refresh, and a
protected endpoint, with a login screen in front of it.

Not built yet: reports, issues, clustering, SLA, dashboard. Those are weeks 2+.

## Running it

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>. No database to install — see the note below.

```bash
./mvnw test          # 8 tests
```

## Demo accounts

Seeded automatically on first start. Password for all of them: `password123`

| Email | Role |
|---|---|
| `citizen@civictrack.in` | CITIZEN |
| `staff@civictrack.in` | STAFF |
| `supervisor@civictrack.in` | SUPERVISOR |
| `admin@civictrack.in` | ADMIN |

Self-registration always creates a `CITIZEN`. Staff roles are provisioned, never
self-selected — otherwise anyone could sign up as an ADMIN.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | — | Create a citizen account |
| `POST` | `/api/v1/auth/login` | — | Email **or** phone + password |
| `POST` | `/api/v1/auth/refresh` | refresh token | New access token |
| `GET`  | `/api/v1/auth/me` | access token | Current user |

Errors are RFC 7807 `application/problem+json`, so the message is always in `detail`.

Access tokens last 15 minutes, refresh tokens 30 days. The two carry a `typ` claim and
are checked against it, so a refresh token cannot be replayed as an access token.

## Deliberate week-1 shortcuts

Each of these is a known gap with a planned fix, not an oversight.

**H2 instead of PostgreSQL + PostGIS.** The database is a file at `./data/civictrack.mv.db`,
created automatically. Nothing in the auth slice is spatial, so PostGIS buys nothing yet and
would have cost an install. Week 2 swaps the `spring.datasource` block for Postgres and this
becomes a real concern.

**`ddl-auto: update` instead of Flyway.** Blueprint §8 is firm that Flyway owns the schema and
Hibernate runs at `validate` — Hibernate cannot generate the functional GiST index the
clustering query depends on. That rule starts applying when the spatial schema lands; letting
Hibernate create a two-column `users` table for now is not worth a migration file.

**Refresh tokens are not revocable.** Signing out clears the browser's copy, but the token
stays valid until it expires. A real logout needs a token store or a rotating token family.

**The JWT secret has a dev default** in `application.yml`. Deployment must set `JWT_SECRET`.
The app refuses to start if the secret is under 256 bits.

**No rate limiting on login.** Blueprint §17 calls for Bucket4j; nothing throttles password
guessing today.

## Layout

Package structure follows blueprint §8, so later work drops into place rather than moving things.

```
src/main/java/com/civictrack/
├── auth/          AuthController, AuthService, JwtService, JwtAuthFilter, AuthenticatedUser
├── user/          User, Role, UserRepository
├── config/        SecurityConfig, DemoDataLoader
└── common/error/  ApiException, GlobalExceptionHandler
src/main/resources/static/
├── index.html     login + register
├── app.html       signed-in view, reads GET /auth/me
└── assets/app.css
```

## Next

| Week | Work |
|---|---|
| 2 | Postgres + PostGIS, Flyway baseline, `reports`/`issues` entities, `POST /reports` that always creates an issue |
| 3 | Clustering: candidate query, accuracy-weighted centroid, advisory lock, concurrency test |
| 4 | Status state machine, transition policy, staff queue |
| 5 | SLA clock, escalation job, ShedLock, idempotency tests |
| 6 | Cloudinary photos, verification quorum |
| 7 | Map, public dashboard, split/merge tool, deploy |
