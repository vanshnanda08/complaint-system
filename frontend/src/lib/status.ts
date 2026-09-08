/**
 * The status vocabulary. Single source of truth.
 *
 * Nothing else in the codebase writes a status word, picks a status colour, or
 * decides what shape a status carries. Blueprint 4 makes `StatusRule` the
 * owner of that mapping; this module is the data behind it.
 *
 * WHY NINE STATUSES SHARE EIGHT COLOURS
 * ------------------------------------
 * The backend has nine statuses. The palette has eight tokens, and ACKNOWLEDGED,
 * ASSIGNED and IN_PROGRESS all map to `--st-active`. That is deliberate and it
 * is not a gap to fill by inventing a ninth colour: to a citizen the three mean
 * one thing -- somebody has it -- and the distinction between them is internal
 * municipal process. What separates them on screen is the word and the glyph.
 *
 * This is exactly why colour alone is never sufficient here. The greyscale
 * check in the phase's verification list is not a courtesy to colour-blind
 * users bolted on at the end; it is the test of whether the encoding works at
 * all, because a third of the states are the same colour by design.
 *
 * BREACHED IS NOT A STATUS
 * ------------------------
 * `--st-breached` has no entry below. Overdue is a *condition* -- the clock is
 * running and the effective deadline has passed -- that can hold over any of
 * five open statuses. It is applied as an overlay by `overdueOverlay`, so an
 * issue reads "In progress" and "Overdue" at once, which is the true statement.
 * Collapsing it into a status would lose the first half of that.
 */

export const ISSUE_STATUSES = [
  "NEW",
  "ACKNOWLEDGED",
  "ASSIGNED",
  "IN_PROGRESS",
  "PENDING_VERIFICATION",
  "RESOLVED",
  "REOPENED",
  "CLOSED",
  "REJECTED",
] as const;

export type IssueStatus = (typeof ISSUE_STATUSES)[number];

/** The left-rule treatment. The shape half of the encoding. */
export type RuleShape = "solid" | "double" | "heavy" | "dashed" | "outlined" | "dotted" | "hairline";

export interface StatusToken {
  /** CSS custom property carrying the colour. */
  readonly color: string;
  /** Left-rule treatment. */
  readonly shape: RuleShape;
  /** Glyph key, drawn as SVG by StatusRule. Never a bare unicode character:
   *  the glyph is load-bearing and must not depend on a font having it. */
  readonly glyph: GlyphKey;
  /** What a member of the public reads. Sentence case, blueprint 9. */
  readonly word: string;
  /**
   * What staff read, where it differs. Blueprint 9: citizens never see internal
   * vocabulary, but for staff the technical term IS the domain language, and
   * softening it for them would hide the state machine from the people governed
   * by it.
   */
  readonly staffWord?: string;
}

export type GlyphKey =
  | "circle-open"
  | "quarter"
  | "half"
  | "three-quarter"
  | "pause"
  | "check"
  | "reopen"
  | "square"
  | "cross"
  | "warning";

export const STATUS: Readonly<Record<IssueStatus, StatusToken>> = {
  NEW: {
    color: "var(--st-new)",
    shape: "solid",
    glyph: "circle-open",
    word: "New",
  },
  ACKNOWLEDGED: {
    color: "var(--st-active)",
    shape: "solid",
    glyph: "quarter",
    word: "Seen by the department",
    staffWord: "Acknowledged",
  },
  ASSIGNED: {
    color: "var(--st-active)",
    shape: "double",
    glyph: "half",
    word: "Assigned to a crew",
    staffWord: "Assigned",
  },
  IN_PROGRESS: {
    color: "var(--st-active)",
    shape: "heavy",
    glyph: "three-quarter",
    word: "Work under way",
    staffWord: "In progress",
  },
  PENDING_VERIFICATION: {
    // The pause glyph is not decorative. The SLA clock genuinely stops in this
    // state, and this is the one place in the interface where a paused clock
    // has to be legible at a glance.
    color: "var(--st-pending)",
    shape: "dashed",
    glyph: "pause",
    word: "Waiting to be checked",
    staffWord: "Pending verification",
  },
  RESOLVED: {
    color: "var(--st-resolved)",
    shape: "outlined",
    glyph: "check",
    word: "Resolved",
  },
  REOPENED: {
    color: "var(--st-reopened)",
    shape: "dotted",
    glyph: "reopen",
    word: "Reopened",
  },
  CLOSED: {
    color: "var(--st-closed)",
    shape: "hairline",
    glyph: "square",
    word: "Closed",
  },
  REJECTED: {
    color: "var(--st-rejected)",
    shape: "hairline",
    glyph: "cross",
    word: "Rejected",
  },
};

/** The overdue overlay. Applied on top of a status, never instead of one. */
export const OVERDUE: StatusToken = {
  color: "var(--st-breached)",
  shape: "heavy",
  glyph: "warning",
  word: "Overdue",
};

/**
 * Whether the SLA clock is running for this status.
 *
 * Mirrors `IssueStatus.clockRunning()` on the server exactly, including the
 * PENDING_VERIFICATION exclusion -- the department is not charged for time
 * spent waiting on citizens. The server remains the authority; this exists so
 * a countdown can render without a round trip, and it must not drift.
 */
export function clockRunning(status: IssueStatus): boolean {
  return (
    status !== "PENDING_VERIFICATION" &&
    status !== "RESOLVED" &&
    status !== "CLOSED" &&
    status !== "REJECTED"
  );
}

/**
 * Whether an issue is overdue right now.
 *
 * `effectiveDeadline` is computed server-side as `dueAt + pausedSeconds` and
 * shipped on every issue DTO, precisely so the client never re-derives the SLA
 * clock. Pass it through; do not recompute it from `dueAt`.
 */
export function isOverdue(
  status: IssueStatus,
  effectiveDeadline: string,
  now: Date = new Date(),
): boolean {
  return clockRunning(status) && now.getTime() > Date.parse(effectiveDeadline);
}

/** The word a given audience reads for a status. */
export function statusWord(status: IssueStatus, audience: "public" | "staff" = "public"): string {
  const token = STATUS[status];
  return audience === "staff" ? (token.staffWord ?? token.word) : token.word;
}
