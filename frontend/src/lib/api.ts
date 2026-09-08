import type { ProblemDetail } from "./types";

/**
 * The HTTP layer.
 *
 * Two base URLs, deliberately. `API_BASE` is read on the server, where a
 * request goes straight to Spring; `NEXT_PUBLIC_API_BASE` is what the browser
 * uses. They point at the same place in development and need not in
 * deployment.
 */
const SERVER_BASE = process.env.API_BASE ?? "http://localhost:8080/api/v1";
const BROWSER_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api/v1";

export const apiBase = () => (typeof window === "undefined" ? SERVER_BASE : BROWSER_BASE);

/**
 * A failed request, carrying the server's RFC 7807 body when there was one.
 *
 * The `problem` is kept intact rather than flattened to a message, because
 * `ErrorState` renders `problem.detail` verbatim. The state machine is the
 * authority on why a transition was refused, and its sentence is more useful
 * than anything the client could compose.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ProblemDetail | null,
    message?: string,
  ) {
    super(message ?? problem?.detail ?? `Request failed with ${status}`);
    this.name = "ApiError";
  }
}

export interface RequestOptions extends Omit<RequestInit, "body"> {
  body?: unknown;
  /** Bearer token. Held in memory by the auth context; never read from storage. */
  token?: string | null;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, token, headers, ...rest } = options;

  const res = await fetch(`${apiBase()}${path}`, {
    ...rest,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!res.ok) {
    // A ProblemDetail is JSON; a proxy error page is not. Failing to parse one
    // must not mask the status code, which is the only thing the caller can
    // still act on.
    let problem: ProblemDetail | null = null;
    try {
      const text = await res.text();
      problem = text ? (JSON.parse(text) as ProblemDetail) : null;
    } catch {
      problem = null;
    }
    throw new ApiError(res.status, problem);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** Drops empty values so a blank filter is an absent filter, not `?status=`. */
export function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === "") continue;
    sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : "";
}
