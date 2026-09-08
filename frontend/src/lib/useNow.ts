"use client";

import { useSyncExternalStore } from "react";

/**
 * The current time, as a subscribable store, shared by every countdown on the
 * page.
 *
 * ONE timer for the whole document, not one per component. A staff queue
 * renders fifty rows, and fifty `setInterval`s that each wake the main thread
 * every thirty seconds is fifty times the work for one clock's worth of
 * information -- on precisely the cheap hardware this project is built for.
 *
 * `useSyncExternalStore` rather than `useState` + `useEffect` because the value
 * is genuinely external mutable state. The `useState` version has to call
 * `Date.now()` during render or set state synchronously inside an effect, and
 * both are impurities that React's compiler-aware lint rules correctly reject:
 * a component that reads the wall clock during render produces a different
 * tree for the same props, which is exactly the thing memoisation may not do.
 *
 * `getServerSnapshot` returns 0, meaning "not known yet". The server cannot
 * know the client's clock, so the first render -- on the server and on the
 * hydrating client alike -- shows the absolute deadline, which is true, stable
 * and identical on both sides. The relative countdown appears after mount.
 * Nothing false is rendered in the meantime.
 */

const TICK_MS = 30_000;

let now = 0;
let timer: ReturnType<typeof setInterval> | null = null;
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (timer === null) {
    now = Date.now();
    timer = setInterval(() => {
      now = Date.now();
      for (const l of listeners) l();
    }, TICK_MS);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  };
}

/** Stable between ticks, which is what `useSyncExternalStore` requires. */
function getSnapshot(): number {
  return now;
}

function getServerSnapshot(): number {
  return 0;
}

/** Milliseconds since the epoch, or 0 before the client clock is known. */
export function useNow(): number {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}
