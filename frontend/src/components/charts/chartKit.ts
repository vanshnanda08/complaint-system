"use client";

import { useEffect, useRef, useState } from "react";

/**
 * Shared chart plumbing: the measured width and the y-axis ticks.
 *
 * Charts are drawn at their real pixel width rather than scaled through a
 * viewBox. Scaling distorts text and stroke widths -- a 2px line becomes 3.4px
 * on a wide screen and a hairline on a phone -- and the mark specs are in
 * pixels for a reason.
 */
export function useWidth<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const ro = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    ro.observe(node);
    return () => ro.disconnect();
  }, []);
  return [ref, width] as const;
}

/** Round tick values from zero: 0 / 5 / 10, never 0 / 3.7 / 7.4. */
export function niceTicks(max: number, count = 4): number[] {
  if (max <= 0) return [0, 1];
  const raw = max / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? raw;
  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(Math.round(v * 1e6) / 1e6);
  return ticks;
}

export const fmt = (n: number) => n.toLocaleString("en-IN");

/** "4 Mar". Days arrive as ISO dates already in the city's zone, so no zone conversion here. */
export function shortDay(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-IN", {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });
}
