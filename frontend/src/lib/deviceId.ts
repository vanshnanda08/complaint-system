"use client";

/**
 * A per-device identifier for anonymous reports.
 *
 * The server counts distinct reporters by `COALESCE(reporter_id, device_id)`,
 * so this is what stops twenty submissions from one phone counting as twenty
 * reporters and buying an issue undeserved priority.
 *
 * It is a random value in localStorage, and that is a deliberate difference
 * from the tokens: this is not a credential and grants nothing. Losing it
 * costs nothing but a slightly worse duplicate count; an attacker learning it
 * gains nothing, because the server never authenticates anybody by device.
 * That is also why /me/reports cannot show anonymous reports -- a device is
 * not an identity.
 */
const KEY = "civictrack_device_id";

export function deviceId(): string {
  if (typeof window === "undefined") return "server";
  try {
    const existing = window.localStorage.getItem(KEY);
    if (existing) return existing;
    const fresh = crypto.randomUUID();
    window.localStorage.setItem(KEY, fresh);
    return fresh;
  } catch {
    // Private browsing, or storage disabled. A per-session id still separates
    // this device from others for the duration that matters.
    return `ephemeral-${Math.random().toString(36).slice(2)}`;
  }
}
