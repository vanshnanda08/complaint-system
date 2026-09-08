"use client";

import { useEffect, useLayoutEffect, useSyncExternalStore } from "react";

/**
 * The light/dark theme, as a subscribable store.
 *
 * Three things have to agree, and the order they happen in is the whole
 * problem:
 *
 *  1. The `dark` class on <html>, which is what the CSS actually reads.
 *  2. `localStorage.theme`, which is what survives a reload.
 *  3. React state, which only drives the toggle's own label.
 *
 * (1) MUST be set before the browser paints, or the page renders light and
 * then flips -- a full-page flash on every single navigation for anyone using
 * dark mode. An effect cannot do that: effects run after paint, by
 * definition. So the class is applied by a tiny blocking script in
 * `layout.tsx`, and this module never fights it -- it reads the class the
 * script already set.
 *
 * `useSyncExternalStore` rather than `useState` + `useEffect` for the same
 * reason `useNow` uses it: the class on <html> is genuinely external mutable
 * state. The `useState` version has to write state inside an effect body,
 * which React's compiler-aware lint rules reject -- and the version this
 * replaces silenced that rule with an eslint-disable rather than answering it.
 *
 * `getServerSnapshot` returns "light" because the server cannot know the
 * choice; it lives in localStorage, which the server never sees. The toggle's
 * label is therefore "Dark Mode" in the server HTML and corrects itself on
 * mount. That is a two-word label, not a repaint of the page -- the page
 * itself is already correct, because the blocking script ran first.
 */

export type Theme = "light" | "dark";

export const THEME_STORAGE_KEY = "theme";

/**
 * Inlined into <head> and run before first paint. Kept as a string on purpose:
 * it must not be bundled, deferred, or hydrated, it must simply have already
 * happened by the time the first pixel is drawn.
 *
 * Wrapped in try/catch because reading localStorage throws outright in a
 * browser configured to block site data, and a theme preference is not worth
 * a blank page.
 */
export const THEME_INIT_SCRIPT = `try{var t=localStorage.getItem(${JSON.stringify(
  THEME_STORAGE_KEY,
)});if(t==="dark"||(t===null&&matchMedia("(prefers-color-scheme: dark)").matches)){document.documentElement.classList.add("dark")}}catch(e){}`;

const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot(): Theme {
  return document.documentElement.classList.contains("dark") ? "dark" : "light";
}

function getServerSnapshot(): Theme {
  return "light";
}

/** The active theme. "light" during server render and first hydration. */
export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}

/**
 * `useLayoutEffect` in the browser, `useEffect` on the server.
 *
 * React logs a warning for `useLayoutEffect` during server rendering, and this
 * component is server-rendered like every other client component in the App
 * Router. The effect below has nothing to do on the server anyway.
 */
const useIsomorphicLayoutEffect =
  typeof window !== "undefined" ? useLayoutEffect : useEffect;

/**
 * Re-applies the stored theme after React's development remount.
 *
 * A no-op in production, and it exists for a documented Next.js behaviour
 * rather than for a bug of ours: in Strict Mode React remounts once, and on
 * that remount it resets <html> to only the attributes it manages from JSX --
 * which clears the `dark` class the pre-paint script set. Without this, dark
 * mode silently stops working in `next dev` only, which is the worst place for
 * a difference between development and production to live.
 *
 * `useLayoutEffect` rather than `useEffect` so the correction lands before
 * paint. Call it once, from whichever component owns the theme control.
 */
export function useThemeSync(): void {
  useIsomorphicLayoutEffect(() => {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(THEME_STORAGE_KEY);
    } catch {
      // Site data blocked; fall through to the media query.
    }
    const dark =
      stored === "dark" ||
      (stored === null && window.matchMedia("(prefers-color-scheme: dark)").matches);

    if (document.documentElement.classList.contains("dark") !== dark) {
      document.documentElement.classList.toggle("dark", dark);
      emit();
    }
  }, []);
}

/** Flip the theme, persist the choice, and notify every subscriber. */
export function toggleTheme(): void {
  const next: Theme = getSnapshot() === "dark" ? "light" : "dark";
  document.documentElement.classList.toggle("dark", next === "dark");
  try {
    localStorage.setItem(THEME_STORAGE_KEY, next);
  } catch {
    // Site data blocked. The theme still applies for this page view; it just
    // will not survive a reload. Losing the preference is better than a crash.
  }
  emit();
}
