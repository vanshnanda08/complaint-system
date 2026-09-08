import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { REFRESH_COOKIE, clearRefreshCookie, setRefreshCookie } from "@/lib/authCookie";

/**
 * Silent refresh.
 *
 * Reads the refresh token from the httpOnly cookie -- which the client cannot
 * do, and that is deliberate -- exchanges it, and rotates the cookie with
 * whatever the backend returns.
 *
 * A failed refresh clears the cookie. Leaving a dead token in place would make
 * every subsequent 401 trigger another doomed refresh, so the client would
 * loop instead of sending the user to sign in.
 */
export async function POST() {
  const base = process.env.API_BASE ?? "http://localhost:8080/api/v1";
  const jar = await cookies();
  const refreshToken = jar.get(REFRESH_COOKIE)?.value;

  if (!refreshToken) {
    // 204, not 401. Having no cookie is the ordinary state of every visitor who
    // has not signed in, and this route is called once on every page load. A
    // 401 here would put a red error in the console of every anonymous visit to
    // a site whose whole premise is that it needs no account -- and real 401s
    // would be lost among them. A genuine failure (a cookie that no longer
    // exchanges) still answers 401 below.
    return new NextResponse(null, { status: 204 });
  }

  const upstream = await fetch(`${base}/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });

  if (!upstream.ok) {
    const res = NextResponse.json({ detail: "Your session has expired." }, { status: 401 });
    clearRefreshCookie(res);
    return res;
  }

  const { refreshToken: rotated, ...rest } = (await upstream.json()) as Record<string, unknown> & {
    refreshToken: string;
  };
  const res = NextResponse.json(rest);
  setRefreshCookie(res, rotated);
  return res;
}
