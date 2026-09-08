import type { NextResponse } from "next/server";

/**
 * The refresh-token cookie.
 *
 * httpOnly, so script cannot read it -- which is the entire point. Blueprint §6
 * rules out localStorage and sessionStorage for the refresh token because both
 * are readable by any script that gets injected onto the page, and a thirty-day
 * refresh token in a readable store is a thirty-day account takeover.
 *
 * The ACCESS token deliberately does NOT come here. It lives in React state,
 * dies with the tab, and is short enough (15 minutes) that keeping it in memory
 * costs only a silent refresh on reload.
 *
 * `sameSite: "lax"` rather than "strict": strict would drop the cookie on a
 * cross-site navigation into the app, so following a shared issue link while
 * signed in would silently sign you out. There is no CSRF exposure from lax
 * here, because this cookie is only ever read by our own route handler and is
 * never itself an authorisation.
 */
export const REFRESH_COOKIE = "civictrack_refresh";

const THIRTY_DAYS = 30 * 24 * 60 * 60;

export function setRefreshCookie(res: NextResponse, token: string): void {
  res.cookies.set(REFRESH_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: THIRTY_DAYS,
  });
}

export function clearRefreshCookie(res: NextResponse): void {
  res.cookies.set(REFRESH_COOKIE, "", {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 0,
  });
}
