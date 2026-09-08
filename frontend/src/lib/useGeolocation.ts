"use client";

import { useCallback, useEffect, useState } from "react";

/**
 * The browser's location, with the accuracy threshold the product actually
 * cares about.
 *
 * Above 150 m the automatic fix is DISCARDED, not merely warned about
 * (blueprint §3.2). That number is not arbitrary and it is not only a client
 * rule: `civictrack.clustering.max-accuracy-m` is 150 on the server, and a
 * report above it is rejected with a ProblemDetail carrying the threshold and
 * a remedy. Enforcing it here turns a server rejection into a thing the user
 * can fix before they ever hit send.
 */

export const MAX_ACCURACY_M = 150;

export type GeoState =
  | { kind: "idle" }
  | { kind: "locating" }
  | { kind: "ok"; lat: number; lng: number; accuracyM: number }
  | { kind: "too-imprecise"; accuracyM: number }
  | { kind: "denied" }
  | { kind: "unavailable" };

export function useGeolocation(): { state: GeoState; locate: () => void } {
  // Starts at "locating" rather than "idle", because the effect below asks for
  // a fix on mount and that is therefore the true state from the first render.
  // Initialising to "idle" and correcting it inside the effect would be a
  // synchronous setState in an effect body -- a cascading render, and a frame
  // of a state the component is never actually in.
  const [state, setState] = useState<GeoState>({ kind: "locating" });

  const locate = useCallback(() => {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setState({ kind: "unavailable" });
      return;
    }
    setState({ kind: "locating" });
    // Every setState below runs in a browser callback, not in the effect body,
    // which is the shape the rules-of-hooks lint is asking for: subscribe to an
    // external system, and set state when that system answers.
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const accuracyM = pos.coords.accuracy;
        if (accuracyM > MAX_ACCURACY_M) {
          // Discarded, not kept-with-a-warning. Keeping it would let a user
          // send a fix the server will refuse.
          setState({ kind: "too-imprecise", accuracyM });
          return;
        }
        setState({ kind: "ok", lat: pos.coords.latitude, lng: pos.coords.longitude, accuracyM });
      },
      (err) => {
        setState(err.code === err.PERMISSION_DENIED ? { kind: "denied" } : { kind: "unavailable" });
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    );
  }, []);

  useEffect(() => {
    // Geolocation is a genuine external system, so asking it for a value on
    // mount is what an effect is for.
    //
    // Every state transition below happens in a callback rather than in the
    // effect body -- including the "this browser has no geolocation at all"
    // case, which is deferred to a microtask. That is not a trick to quiet the
    // linter: setting state synchronously here would mean the component
    // renders "locating", then immediately re-renders "unavailable" within the
    // same commit, and the intermediate frame is a state the user is never
    // actually in. Deferring makes it a real transition.
    let live = true;
    const settle = (next: GeoState) => {
      if (live) setState(next);
    };

    if (typeof navigator === "undefined" || !navigator.geolocation) {
      queueMicrotask(() => settle({ kind: "unavailable" }));
      return () => {
        live = false;
      };
    }

    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const accuracyM = pos.coords.accuracy;
        settle(
          accuracyM > MAX_ACCURACY_M
            ? { kind: "too-imprecise", accuracyM }
            : { kind: "ok", lat: pos.coords.latitude, lng: pos.coords.longitude, accuracyM },
        );
      },
      (err) => {
        settle(err.code === err.PERMISSION_DENIED ? { kind: "denied" } : { kind: "unavailable" });
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    );

    return () => {
      live = false;
    };
  }, []);

  return { state, locate };
}
