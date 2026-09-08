"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import type { CompressedImage } from "./compressImage";

/**
 * The report being composed, held above the composer screen.
 *
 * Blueprint §3.2 and §6: on a network failure the composed report is held and
 * the button reads "Retry submission" -- the photo is never discarded. Holding
 * it in a provider rather than in the page's own state is what makes that
 * survive a re-render, a route change and a back button, which is exactly when
 * a frustrated user on a bad connection is most likely to lose it.
 *
 * This is client state, not server state, so it is React state and not
 * TanStack Query. The distinction matters: a half-composed report is not a
 * cached copy of anything the server knows about.
 */

export interface ComposedReport {
  categoryCode: string | null;
  lat: number | null;
  lng: number | null;
  accuracyM: number | null;
  manualPin: boolean;
  description: string;
  landmark: string;
  photo: CompressedImage | null;
  /** Set once the photo has been uploaded, so a retry does not re-upload it. */
  photoUrl: string | null;
}

export const EMPTY_REPORT: ComposedReport = {
  categoryCode: null,
  lat: null,
  lng: null,
  accuracyM: null,
  manualPin: false,
  description: "",
  landmark: "",
  photo: null,
  photoUrl: null,
};

interface ComposerValue {
  report: ComposedReport;
  patch: (changes: Partial<ComposedReport>) => void;
  reset: () => void;
}

const ComposerContext = createContext<ComposerValue | null>(null);

export function ComposerProvider({ children }: { children: React.ReactNode }) {
  const [report, setReport] = useState<ComposedReport>(EMPTY_REPORT);

  /**
   * `patch` and `reset` are stable for the life of the provider.
   *
   * This is load-bearing, not tidiness. They were previously created inside the
   * `useMemo` keyed on `report`, so their identity changed every time the report
   * did. Any effect that both depended on `patch` and called it -- which the
   * composer's geolocation effect does -- became an infinite render loop: patch
   * changes report, report changes patch, the effect re-runs. The page rendered
   * fine and behaved correctly right up until it had to commit a navigation,
   * which it never got a quiet frame to do.
   *
   * Functional updates make the identity independent of `report`, so the
   * dependency is real and the loop is gone.
   */
  const patch = useCallback(
    (changes: Partial<ComposedReport>) => setReport((r) => ({ ...r, ...changes })),
    [],
  );
  const reset = useCallback(() => setReport(EMPTY_REPORT), []);

  const value = useMemo<ComposerValue>(
    () => ({ report, patch, reset }),
    [report, patch, reset],
  );

  return <ComposerContext.Provider value={value}>{children}</ComposerContext.Provider>;
}

export function useComposer(): ComposerValue {
  const ctx = useContext(ComposerContext);
  if (!ctx) throw new Error("useComposer must be used inside <ComposerProvider>");
  return ctx;
}
