"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiBase } from "./api";

/**
 * Authentication.
 *
 * The access token lives HERE -- in React state, in memory, for the life of the
 * tab -- and nowhere else. Blueprint §6 rules out localStorage and
 * sessionStorage, and the reason is that both are readable by any injected
 * script. The refresh token is in an httpOnly cookie that this code cannot
 * read even if it wanted to, set by the route handlers under /api/auth.
 *
 * The cost of that choice is one silent refresh on every page load, which is
 * what `useEffect` below does. That is a real cost and it is the right trade:
 * a reload that takes one extra round trip is better than a token that survives
 * in a store an attacker can read.
 */

export type Role = "CITIZEN" | "STAFF" | "SUPERVISOR" | "ADMIN";

export interface Session {
  accessToken: string;
  userId: string;
  fullName: string;
  role: Role;
}

interface AuthValue {
  session: Session | null;
  /** True until the first silent refresh settles, so guards do not flash. */
  initialising: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  signOut: () => Promise<void>;
  /** Runs a request with the access token, refreshing once on a 401. */
  authed: <T>(run: (token: string) => Promise<T>) => Promise<T>;
}

export interface RegisterInput {
  email: string;
  password: string;
  fullName: string;
  phone?: string;
}

const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [initialising, setInitialising] = useState(true);

  // A single in-flight refresh, shared. Without this, a page that fires four
  // authenticated queries at once and gets four 401s would start four
  // refreshes, and each rotates the cookie -- so three of them would present a
  // token the server has already replaced.
  const refreshInFlight = useRef<Promise<Session | null> | null>(null);

  const refresh = useCallback(async (): Promise<Session | null> => {
    if (refreshInFlight.current) return refreshInFlight.current;

    const attempt = (async () => {
      try {
        const res = await fetch("/api/auth/refresh", { method: "POST" });
        // 204 means "no cookie, nobody is signed in" -- the ordinary anonymous
        // case, not a failure. Anything else non-OK is a real refusal.
        if (res.status === 204 || !res.ok) {
          setSession(null);
          return null;
        }
        const next = (await res.json()) as Session;
        setSession(next);
        return next;
      } catch {
        setSession(null);
        return null;
      } finally {
        refreshInFlight.current = null;
      }
    })();

    refreshInFlight.current = attempt;
    return attempt;
  }, []);

  useEffect(() => {
    // One silent refresh on mount. A visitor with no cookie gets a 401 here,
    // which is the ordinary anonymous case and not an error.
    void refresh().finally(() => setInitialising(false));
  }, [refresh]);

  const signIn = useCallback(async (email: string, password: string) => {
    const res = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    if (!res.ok) {
      const problem = await res.json().catch(() => null);
      throw new ApiError(res.status, problem);
    }
    setSession((await res.json()) as Session);
  }, []);

  const register = useCallback(async (input: RegisterInput) => {
    const res = await fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    });
    if (!res.ok) {
      const problem = await res.json().catch(() => null);
      throw new ApiError(res.status, problem);
    }
    setSession((await res.json()) as Session);
  }, []);

  const signOut = useCallback(async () => {
    await fetch("/api/auth/logout", { method: "POST" }).catch(() => {});
    setSession(null);
  }, []);

  const authed = useCallback(
    async <T,>(run: (token: string) => Promise<T>): Promise<T> => {
      const current = session ?? (await refresh());
      if (!current) throw new ApiError(401, { detail: "Sign in to see this." });

      try {
        return await run(current.accessToken);
      } catch (e) {
        // Exactly one retry. A second 401 after a fresh token is not a stale
        // token, it is a real refusal, and retrying again would loop.
        if (e instanceof ApiError && e.status === 401) {
          const renewed = await refresh();
          if (!renewed) throw e;
          return run(renewed.accessToken);
        }
        throw e;
      }
    },
    [session, refresh],
  );

  const value = useMemo(
    () => ({ session, initialising, signIn, register, signOut, authed }),
    [session, initialising, signIn, register, signOut, authed],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}

/** Convenience for the authenticated endpoints, which all live off apiBase(). */
export function authedFetch(token: string, path: string, init?: RequestInit) {
  return fetch(`${apiBase()}${path}`, {
    ...init,
    headers: { ...init?.headers, Authorization: `Bearer ${token}` },
  });
}
