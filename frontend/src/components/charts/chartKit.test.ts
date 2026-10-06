import { describe, expect, it } from "vitest";
import { niceTicks, shortDay } from "./chartKit";

describe("niceTicks", () => {
  it("steps in 1, 2 or 5 times a power of ten, from zero, covering the max", () => {
    expect(niceTicks(7)).toEqual([0, 2, 4, 6, 8]);
    expect(niceTicks(38)).toEqual([0, 10, 20, 30, 40]);
    expect(niceTicks(1)).toEqual([0, 0.5, 1]);
  });

  it("never draws an axis with nothing on it", () => {
    expect(niceTicks(0)).toEqual([0, 1]);
  });
});

/**
 * The server buckets days in Asia/Kolkata and sends them as plain ISO dates.
 * Labelling one must not shift it through the browser's zone: parsed as a
 * local midnight, "2026-03-02" would read "1 Mar" anywhere west of UTC.
 */
describe("shortDay", () => {
  it("labels the date it was given, whatever the browser's zone", () => {
    expect(shortDay("2026-03-02")).toBe("2 Mar");
    expect(shortDay("2026-12-31")).toBe("31 Dec");
  });
});
