"use client";

import { useEffect, useState } from "react";

/**
 * THE ONE ANIMATED MOMENT IN THE ENTIRE APPLICATION (blueprint §1.6).
 *
 * A report count incrementing from N-1 to N, once, over 240ms, with the value
 * briefly carrying the status rule at full weight before settling. Nothing else
 * in this codebase moves: no entrance animations, no hover transitions on rows,
 * no skeleton shimmer.
 *
 * It is here because it is the product's core claim made visible -- the moment
 * a citizen sees their report become the fourth piece of evidence rather than
 * disappearing into a queue.
 *
 * The starting value is the INITIAL STATE, not something an effect assigns.
 * That ordering matters twice over: it avoids a cascading render, and it means
 * the pre-increment value is what the server sends down and hydrates, so the
 * animation genuinely starts from N-1 rather than flashing N and jumping back.
 *
 * `prefers-reduced-motion` is honoured by never setting `settling`, so the
 * value changes once, immediately, with no transition -- which is exactly what
 * blueprint §1.6 asks for.
 */
export function CountUp({ to, durationMs = 240 }: { to: number; durationMs?: number }) {
  const from = to > 1 ? to - 1 : to;
  const [value, setValue] = useState(from);
  const [settling, setSettling] = useState(false);

  useEffect(() => {
    if (to <= 1) return;

    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    // Both setState calls happen in timer callbacks, never in the effect body.
    const bump = window.setTimeout(() => {
      setValue(to);
      if (!reduced) setSettling(true);
    }, 60);

    const settle = window.setTimeout(() => setSettling(false), 60 + durationMs);

    return () => {
      window.clearTimeout(bump);
      window.clearTimeout(settle);
    };
  }, [to, durationMs]);

  return (
    <span
      className="text-display tabular-nums"
      style={{
        // The status rule at full weight while settling, then gone.
        borderLeft: settling ? "6px solid var(--st-active)" : "6px solid transparent",
        paddingLeft: 8,
        transition: `border-left-color ${durationMs}ms linear`,
      }}
    >
      {value}
    </span>
  );
}
