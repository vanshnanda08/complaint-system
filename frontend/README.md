# CivicTrack frontend

Next.js 16 (App Router), React 19, Tailwind v4. Delivered in phase 4.

## Running

Needs the backend on `:8080` and its CORS origin set to `http://localhost:3000`
— the port matters, because the backend allows exact origins and never `*`.

```bash
npm install
npm run dev      # http://localhost:3000
```

`.env.local` holds `API_BASE` (read on the server) and `NEXT_PUBLIC_API_BASE`
(read in the browser). Cloudinary is optional: with no credentials the composer
sends a placeholder URL and everything else works. See `.env.example`.

## Commands

| | |
|---|---|
| `npm run test` | 53 unit tests over the logic that mirrors a server rule |
| `npm run lint` | includes the rule confining Leaflet to one module |
| `npm run build` | production build |

**Restart `npm run start` after `npm run build`.** A stale server serves old
chunks and the failure presents as an application bug (DD-027 records the
equivalent trap on the Maven side).

## Things that are load-bearing

**`src/lib/status.ts` is the only place a status gets a colour, a shape or a
word.** Nine statuses, eight colour tokens — ACKNOWLEDGED, ASSIGNED and
IN_PROGRESS share one colour deliberately, and the glyph and word are what
separate them. Every status surface must stay legible in greyscale; that is the
test of whether the encoding works, not an accessibility afterthought.

**`src/components/map/MapCanvasInner.tsx` is the only module that may import
Leaflet**, enforced by an ESLint rule rather than a comment. Leaflet touches
`window` at module scope, so importing it anywhere else breaks the server render
of whatever page transitively reaches it — and it keeps the report composer from
downloading a map it never shows.

**`src/lib/transitions.ts` mirrors the server's `TransitionPolicy`.** It decides
what to *render*, never what is permitted. It has been wrong before (DD-035);
`tools/table.mjs` and `tools/uiwalk.mjs` in the repository root check it against
the running server, which is the only thing that can.

**`src/lib/schemas.ts` holds every validation rule**, each naming the server rule
it mirrors. Nothing validates inline in a submit handler.

**Never put a token in `localStorage` or `sessionStorage`.** The access token
lives in React state; the refresh token is in an httpOnly cookie set by the
route handlers under `src/app/api/auth/`.
