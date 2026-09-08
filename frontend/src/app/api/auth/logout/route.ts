import { NextResponse } from "next/server";
import { clearRefreshCookie } from "@/lib/authCookie";

/** Drops the refresh cookie. The access token dies with the tab's memory. */
export async function POST() {
  const res = NextResponse.json({ ok: true });
  clearRefreshCookie(res);
  return res;
}
