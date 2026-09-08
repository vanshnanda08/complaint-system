# Verification harnesses

Checks that unit tests structurally cannot perform, because they need the real
server. Each one exists because it caught a bug that a passing test suite had
endorsed — see DD-035, DD-036 and DD-038.

Run with both servers up (backend `:8080` on the `demo` profile, frontend
`:3000`), from a directory with `playwright` installed:

| Script | What it catches |
|---|---|
| `contract.mjs <path-to-types.ts>` | A TypeScript interface disagreeing with the JSON the endpoint actually returns. TypeScript cannot see this: a fetch response is `any` until something asserts a type, and the assertion can be wrong. |
| `table.mjs` | A transition the client offers that the server refuses. Walks NEW → ACKNOWLEDGED → ASSIGNED → IN_PROGRESS → PENDING_VERIFICATION through the API and asserts no step 4xxs. |
| `uiwalk.mjs` | The wrong action rendered for a status. Walks one issue through all five states in a browser and prints the action offered at each. |
| `sweep.mjs` | Runtime errors on any route, signed out and signed in, including deliberate 404 routes and `NaN`/`undefined` leaking into rendered text. |

These belong in CI in phase 5. Every failure they catch is invisible until
somebody opens the right page in the right state.
