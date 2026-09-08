"use client";

import type { Priority } from "@/lib/types";

/**
 * The priority band.
 *
 * Deliberately colourless. Priority is not status, and blueprint §1.2 reserves
 * saturated colour for status alone -- a coloured priority badge next to a
 * coloured status rule would put two competing colour codes in one row and
 * destroy the thing the palette exists to do.
 *
 * The band is carried by weight and by a filled-segment meter, which also
 * makes it ordinal at a glance: four segments, filled to the band. The exact
 * score is available on hover and long-press via `title`, per blueprint §4,
 * and is also in the accessible name so it is not mouse-only.
 */

const BANDS: Record<Priority, { label: string; filled: number }> = {
  LOW: { label: "Low", filled: 1 },
  MEDIUM: { label: "Medium", filled: 2 },
  HIGH: { label: "High", filled: 3 },
  CRITICAL: { label: "Critical", filled: 4 },
};

export interface PriorityBadgeProps {
  priority: Priority;
  score?: number;
}

export function PriorityBadge({ priority, score }: PriorityBadgeProps) {
  const band = BANDS[priority];
  const detail = score == null ? band.label : `${band.label} priority, score ${score.toFixed(1)}`;

  return (
    <span className="inline-flex items-center gap-1.5" title={detail}>
      <span className="inline-flex gap-0.5" aria-hidden="true">
        {[0, 1, 2, 3].map((i) => (
          <span
            key={i}
            style={{
              width: 4,
              height: 12,
              background: i < band.filled ? "var(--ink)" : "transparent",
              border: `1px solid ${i < band.filled ? "var(--ink)" : "var(--rule)"}`,
            }}
          />
        ))}
      </span>
      <span className="text-meta">{band.label}</span>
      <span className="sr-only">{detail}</span>
    </span>
  );
}
