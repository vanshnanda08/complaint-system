import { describe, expect, it } from "vitest";
import { ISSUE_STATUSES, STATUS, clockRunning, isOverdue, statusWord } from "./status";

/**
 * The status vocabulary, and the client's copy of the SLA clock rule.
 *
 * `clockRunning` is the one function here that restates a server rule --
 * `IssueStatus.clockRunning()` in Java. If the two drift, a queue shows a
 * ticking countdown for a department that is not being charged for the time, or
 * a paused one for a department that is. Both are misrepresentations in a
 * system whose purpose is measuring departments fairly.
 */
describe("clockRunning mirrors IssueStatus.clockRunning() on the server", () => {
  it("runs in the five open states", () => {
    for (const s of ["NEW", "ACKNOWLEDGED", "ASSIGNED", "IN_PROGRESS", "REOPENED"] as const) {
      expect(clockRunning(s), `${s} should be on the clock`).toBe(true);
    }
  });

  it("stops during PENDING_VERIFICATION, because the department waits on citizens", () => {
    expect(clockRunning("PENDING_VERIFICATION")).toBe(false);
  });

  it("stops in every terminal state", () => {
    for (const s of ["RESOLVED", "CLOSED", "REJECTED"] as const) {
      expect(clockRunning(s), `${s} should not be on the clock`).toBe(false);
    }
  });

  it("accounts for all nine statuses, so a new one cannot be forgotten", () => {
    expect(ISSUE_STATUSES).toHaveLength(9);
    const running = ISSUE_STATUSES.filter(clockRunning);
    expect(running).toHaveLength(5);
  });
});

describe("isOverdue", () => {
  const past = new Date("2026-03-01T00:00:00Z").toISOString();
  const future = new Date("2027-03-01T00:00:00Z").toISOString();
  const now = new Date("2026-06-01T00:00:00Z");

  it("is true only when the clock runs and the effective deadline has passed", () => {
    expect(isOverdue("IN_PROGRESS", past, now)).toBe(true);
    expect(isOverdue("IN_PROGRESS", future, now)).toBe(false);
  });

  it("is false for a paused clock even long past the deadline", () => {
    // The whole point of the pause: a department is not overdue for time it is
    // not being charged for.
    expect(isOverdue("PENDING_VERIFICATION", past, now)).toBe(false);
  });

  it("is false for resolved and closed work whatever the deadline said", () => {
    expect(isOverdue("RESOLVED", past, now)).toBe(false);
    expect(isOverdue("CLOSED", past, now)).toBe(false);
  });
});

describe("the status vocabulary", () => {
  it("gives every status a colour, a shape and a word", () => {
    for (const s of ISSUE_STATUSES) {
      expect(STATUS[s].color, s).toMatch(/^var\(--st-/);
      expect(STATUS[s].shape, s).toBeTruthy();
      expect(STATUS[s].word, s).toBeTruthy();
      expect(STATUS[s].glyph, s).toBeTruthy();
    }
  });

  it("never encodes a status by colour alone: no two share both colour and glyph", () => {
    const seen = new Map<string, string>();
    for (const s of ISSUE_STATUSES) {
      const key = `${STATUS[s].color}|${STATUS[s].glyph}`;
      expect(seen.has(key), `${s} is indistinguishable from ${seen.get(key)}`).toBe(false);
      seen.set(key, s);
    }
  });

  it("lets the three active statuses share a colour but never a glyph or a word", () => {
    const active = ["ACKNOWLEDGED", "ASSIGNED", "IN_PROGRESS"] as const;
    const colours = new Set(active.map((s) => STATUS[s].color));
    expect(colours.size, "the three active statuses share one colour by design").toBe(1);
    expect(new Set(active.map((s) => STATUS[s].glyph)).size).toBe(3);
    expect(new Set(active.map((s) => statusWord(s, "staff"))).size).toBe(3);
  });

  it("gives citizens plain language and staff the domain terms", () => {
    // Blueprint §9: a citizen never sees internal vocabulary.
    expect(statusWord("PENDING_VERIFICATION", "public")).toBe("Waiting to be checked");
    expect(statusWord("PENDING_VERIFICATION", "staff")).toBe("Pending verification");
  });
});
