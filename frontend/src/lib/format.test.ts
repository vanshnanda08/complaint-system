import { describe, expect, it } from "vitest";
import { absoluteDateTime, ageLabel, fileSize, humaniseMs, metres, ordinal, reporters } from "./format";

/**
 * Formatting. Small, but every one of these appears in a queue row or on the
 * result screen, and getting a plural or a rounding wrong is visible to every
 * user on every page.
 */
describe("humaniseMs", () => {
  it("uses a single unit, because a queue row has no space for three", () => {
    expect(humaniseMs(2 * 24 * 3600_000)).toBe("2 days");
    expect(humaniseMs(4 * 3600_000)).toBe("4 hours");
    expect(humaniseMs(35 * 60_000)).toBe("35 minutes");
  });

  it("says one day, not 1 days", () => {
    expect(humaniseMs(24 * 3600_000)).toBe("1 day");
    expect(humaniseMs(3600_000)).toBe("1 hour");
    expect(humaniseMs(60_000)).toBe("1 minute");
  });

  it("is symmetric, so an overdue duration reads the same as a remaining one", () => {
    expect(humaniseMs(-2 * 24 * 3600_000)).toBe("2 days");
  });

  it("never renders zero minutes for a sub-minute span", () => {
    // "Overdue by 0 minutes" is a worse statement than "1 minute".
    expect(humaniseMs(5_000)).toBe("1 minute");
  });
});

describe("metres", () => {
  it("keeps a decimal below ten, where the precision is real", () => {
    expect(metres(7.44)).toBe("7.4 m");
  });

  it("rounds above ten, where it is not", () => {
    expect(metres(27.5)).toBe("28 m");
  });

  it("renders an em dash for an absent measurement rather than 0 m", () => {
    // A first report has no distance to a cluster. Zero would be a claim.
    expect(metres(null)).toBe("—");
    expect(metres(undefined)).toBe("—");
  });
});

describe("ordinal", () => {
  it("handles the ordinary cases", () => {
    expect(ordinal(1)).toBe("1st");
    expect(ordinal(2)).toBe("2nd");
    expect(ordinal(3)).toBe("3rd");
    expect(ordinal(4)).toBe("4th");
  });

  it("handles the teens, which are the ones people get wrong", () => {
    expect(ordinal(11)).toBe("11th");
    expect(ordinal(12)).toBe("12th");
    expect(ordinal(13)).toBe("13th");
    expect(ordinal(111)).toBe("111th");
    expect(ordinal(21)).toBe("21st");
  });
});

describe("reporters and fileSize", () => {
  it("says one reporter, not 1 reporters", () => {
    expect(reporters(1)).toBe("1 reporter");
    expect(reporters(4)).toBe("4 reporters");
  });

  it("shows a size a person on a weak connection can judge", () => {
    expect(fileSize(900)).toBe("900 B");
    expect(fileSize(300 * 1024)).toBe("300 KB");
    expect(fileSize(3.7 * 1024 * 1024)).toBe("3.7 MB");
  });
});

describe("date formatting never throws on bad input", () => {
  /**
   * `Intl.DateTimeFormat().format()` raises RangeError on an invalid date, and
   * a formatter crashing takes the whole page with it. This is a regression
   * test: the staff work view was typed against the wrong DTO, so
   * `effectiveDeadline` arrived undefined and the page died with
   * "RangeError: Invalid time value" -- intermittently, because whether it
   * threw depended on whether the shared clock store had ticked yet.
   */
  it("renders an em dash rather than throwing on a missing or invalid instant", () => {
    for (const bad of [undefined, null, "", "not-a-date"]) {
      expect(() => absoluteDateTime(bad as string)).not.toThrow();
      expect(absoluteDateTime(bad as string)).toBe("—");
    }
  });

  it("still formats a real instant", () => {
    expect(absoluteDateTime("2026-03-14T12:30:00Z")).toMatch(/14 March/);
  });

  it("degrades the age label rather than printing NaN", () => {
    expect(ageLabel(undefined)).toBe("Reported at an unknown time");
    expect(humaniseMs(Number.NaN)).toBe("an unknown time");
  });
});
