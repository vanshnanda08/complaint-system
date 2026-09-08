/**
 * Formatting. All of it, in one place.
 *
 * Components take pre-formatted strings wherever a value could be rendered
 * more than one way, so that date policy is not decided independently in nine
 * components. The rules here are the copy rules (blueprint §9) applied to
 * numbers: plain, specific, no filler.
 */

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * A coarse duration: "2 days", "4 hours", "35 minutes".
 *
 * Single-unit on purpose. "2 days 4 hours 12 minutes" is more precise and less
 * useful -- nobody reading a queue acts differently at 4 hours than at 4 hours
 * 12 minutes, and the extra units cost column width the row does not have.
 */
export function humaniseMs(ms: number): string {
  if (!Number.isFinite(ms)) return "an unknown time";
  const abs = Math.abs(ms);
  if (abs >= DAY) {
    const d = Math.round(abs / DAY);
    return d === 1 ? "1 day" : `${d} days`;
  }
  if (abs >= HOUR) {
    const h = Math.round(abs / HOUR);
    return h === 1 ? "1 hour" : `${h} hours`;
  }
  const m = Math.max(1, Math.round(abs / MINUTE));
  return m === 1 ? "1 minute" : `${m} minutes`;
}

/** "Reported 6 days ago". */
export function ageLabel(isoInstant: string | null | undefined, now: Date = new Date()): string {
  const t = isoInstant ? Date.parse(isoInstant) : NaN;
  if (Number.isNaN(t)) return "Reported at an unknown time";
  return `Reported ${humaniseMs(now.getTime() - t)} ago`;
}

/**
 * An absolute date a person can act on: "14 March, 6:00 pm".
 *
 * Asia/Kolkata explicitly. The server speaks UTC and the citizen does not, and
 * leaving it to the runtime's locale means the deadline on a deployed
 * screenshot reads differently from the deadline on the phone that took it.
 */
export function absoluteDateTime(isoInstant: string | null | undefined): string {
  // Guarded, because `Intl` throws RangeError on an invalid date and a
  // formatter has no business taking down the page that called it. This is not
  // hypothetical: the staff work view crashed exactly this way when it was
  // typed against the wrong DTO and `effectiveDeadline` arrived undefined.
  if (!isoInstant || Number.isNaN(Date.parse(isoInstant))) return "—";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "long",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
    timeZone: "Asia/Kolkata",
  }).format(new Date(isoInstant));
}

export function absoluteDate(isoInstant: string | null | undefined): string {
  if (!isoInstant || Number.isNaN(Date.parse(isoInstant))) return "—";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "Asia/Kolkata",
  }).format(new Date(isoInstant));
}

/** Metres, at the precision the measurement actually supports. */
export function metres(m: number | null | undefined): string {
  if (m == null) return "—";
  return m < 10 ? `${m.toFixed(1)} m` : `${Math.round(m)} m`;
}

/** A coordinate pair, for the Ref/mono treatment. */
export function coordinates(lat: number, lng: number): string {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

/** "4 reporters" / "1 reporter". */
export function reporters(n: number): string {
  return n === 1 ? "1 reporter" : `${n} reporters`;
}

/** Bytes as a size a person on a weak connection can judge: "287 KB". */
export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * The ordinal a citizen reads on the result screen: "4th".
 *
 * The server already sends a complete sentence in `message`, and that sentence
 * is the authority. This exists for the one place the number is set in Display
 * type on its own and the sentence would be too long.
 */
export function ordinal(n: number): string {
  const suffix =
    n % 100 >= 11 && n % 100 <= 13
      ? "th"
      : n % 10 === 1
        ? "st"
        : n % 10 === 2
          ? "nd"
          : n % 10 === 3
            ? "rd"
            : "th";
  return `${n}${suffix}`;
}
