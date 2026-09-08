import type { ReactNode } from "react";

/**
 * Category glyphs, keyed by the backend's category `code`.
 *
 * DD-025 records why this map lives in the frontend rather than in the
 * categories table. In short: standing rule 1 puts every tunable *number* in
 * that table so changing one is an UPDATE rather than a redeploy. An icon does
 * not have that property -- whatever the database stored would be a key into
 * an asset bundle that ships with this code, so a genuinely new icon needs a
 * frontend deploy either way. The round trip buys nothing and costs a
 * migration plus a new way for configuration and code to disagree.
 *
 * `FALLBACK` is the load-bearing part: a category added server-side renders a
 * generic marker rather than a hole, so the picker never breaks because the
 * database moved first.
 *
 * These are drawn in currentColor. They are NOT coloured -- a category is not
 * a status, and blueprint 1.2 reserves colour for status alone.
 */

const s = { width: 24, height: 24, viewBox: "0 0 24 24", "aria-hidden": true } as const;
const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.75, strokeLinecap: "round" as const };

export const FALLBACK: ReactNode = (
  <svg {...s}>
    <circle cx="12" cy="12" r="8" {...stroke} />
    <path d="M12 8v5" {...stroke} />
    <circle cx="12" cy="16" r="1" fill="currentColor" />
  </svg>
);

export const CATEGORY_GLYPHS: Readonly<Record<string, ReactNode>> = {
  // Keys are the live `code` values from GET /api/v1/categories, verified
  // against the running backend rather than guessed. An earlier draft of this
  // map invented POTHOLE/DRAINAGE/ROAD_SIGNAGE and only two of the ten
  // actually matched, which would have shown the fallback glyph for eight of
  // the ten entries in the picker -- and shown it silently, because falling
  // back is exactly what the map is supposed to do when it does not know a
  // code.
  POTHOLE: (
    <svg {...s}>
      <path d="M3 17h18" {...stroke} />
      <path d="M7 17c1-4 3-5 5-5s4 1 5 5" {...stroke} />
    </svg>
  ),
  ROAD_DAMAGE: (
    <svg {...s}>
      <path d="M3 18h18" {...stroke} />
      <path d="M6 18 9 7M15 7l3 11" {...stroke} />
      <path d="M11 7l1 4-1 3 1 4" {...stroke} />
    </svg>
  ),
  STREETLIGHT: (
    <svg {...s}>
      <path d="M12 21V9" {...stroke} />
      <path d="M7 9h10l-2-4H9z" {...stroke} />
    </svg>
  ),
  GARBAGE_DUMP: (
    <svg {...s}>
      <path d="M5 7h14l-1 13H6z" {...stroke} />
      <path d="M9 7V4h6v3" {...stroke} />
    </svg>
  ),
  ILLEGAL_DUMPING: (
    <svg {...s}>
      <path d="M4 20h16" {...stroke} />
      <path d="M6 20l2-6h8l2 6" {...stroke} />
      <path d="M12 11V4M9 6l3-3 3 3" {...stroke} />
    </svg>
  ),
  WATER_LEAK: (
    <svg {...s}>
      <path d="M12 3c4 5 6 8 6 11a6 6 0 0 1-12 0c0-3 2-6 6-11z" {...stroke} />
    </svg>
  ),
  DRAINAGE_BLOCK: (
    <svg {...s}>
      <circle cx="12" cy="12" r="8" {...stroke} />
      <path d="M8 9v6M12 8v8M16 9v6" {...stroke} />
    </svg>
  ),
  OPEN_MANHOLE: (
    <svg {...s}>
      <circle cx="12" cy="12" r="8" {...stroke} />
      <circle cx="12" cy="12" r="3.5" {...stroke} />
    </svg>
  ),
  STRAY_ANIMAL: (
    <svg {...s}>
      <circle cx="9" cy="10" r="2" {...stroke} />
      <circle cx="15" cy="10" r="2" {...stroke} />
      <path d="M7 15c1.5 3 8.5 3 10 0" {...stroke} />
    </svg>
  ),
  SIGNAGE: (
    <svg {...s}>
      <path d="M12 21V6" {...stroke} />
      <path d="M12 6h8l-2 3 2 3h-8z" {...stroke} />
    </svg>
  ),
};

export function glyphFor(code: string): ReactNode {
  return CATEGORY_GLYPHS[code] ?? FALLBACK;
}
