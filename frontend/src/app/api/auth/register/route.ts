import { NextResponse } from "next/server";
import { setRefreshCookie } from "@/lib/authCookie";

/**
 * Proxies the backend's login and splits the token pair.
 *
 * The backend returns both tokens in the body. This route puts the refresh
 * token into an httpOnly cookie and returns everything else, so the refresh
 * token never reaches JavaScript at all. That split is the whole reason this
 * route exists rather than the browser calling Spring directly.
 */
export async function POST(request: Request) {
  const base = process.env.API_BASE ?? "http://localhost:8080/api/v1";
  const body = await request.text();

  const upstream = await fetch(`${base}/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  });

  const text = await upstream.text();
  if (!upstream.ok) {
    // The backend's ProblemDetail is passed through untouched, including its
    // deliberate refusal to distinguish "no such account" from "wrong
    // password" -- that ambiguity is an anti-enumeration measure and
    // rewording it here would undo it.
    return new NextResponse(text, {
      status: upstream.status,
      headers: { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
    });
  }

  const { refreshToken, ...rest } = JSON.parse(text) as Record<string, unknown> & {
    refreshToken: string;
  };
  const res = NextResponse.json(rest);
  setRefreshCookie(res, refreshToken);
  return res;
}
