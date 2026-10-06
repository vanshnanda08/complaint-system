import { describe, expect, it } from "vitest";
import { REVIEW_CAUSE, reviewCause } from "./moderation";

/**
 * The review causes mirror the review_reason strings ClusteringService writes
 * ("LOW_CONF_MERGE", "LOW_CONF_SPLIT", "EXTENT_CAP"). If one is renamed on the
 * server, the queue would silently fall back to the generic label for it.
 */
describe("review causes", () => {
  it("has a label for every reason the clustering engine writes", () => {
    expect(Object.keys(REVIEW_CAUSE).sort()).toEqual(["EXTENT_CAP", "LOW_CONF_MERGE", "LOW_CONF_SPLIT"]);
  });

  it("falls back to a generic label rather than nothing", () => {
    expect(reviewCause(null).title).toBe("Flagged for review");
    expect(reviewCause("SOMETHING_NEW").title).toBe("Flagged for review");
  });
});
