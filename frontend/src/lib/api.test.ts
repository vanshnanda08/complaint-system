import { describe, expect, it } from "vitest";
import { ApiError, isUnknownTicket } from "./api";

/**
 * The rule that decides between two very different screens: "No such ticket",
 * which tells the reader the URL is wrong, and the error state, which tells
 * them the request did not get through and invites a retry.
 *
 * Showing the second over the first is what /issues/CT-2026-000911 did -- a
 * pasted ticket reference where an id belongs. The server understood the
 * request and refused it; the client called that a dropped connection.
 */
const MALFORMED = "https://civictrack.example/problems/malformed-parameter";

describe("isUnknownTicket", () => {
  it("is true for 404, the ordinary missing ticket", () => {
    expect(isUnknownTicket(new ApiError(404, { type: "x", detail: "no" }))).toBe(true);
  });

  it("is true for the server's malformed-parameter 400, which is a wrong URL", () => {
    expect(isUnknownTicket(new ApiError(400, { type: MALFORMED, parameter: "id" }))).toBe(true);
  });

  it("is false for any other 400, which is not a statement about existence", () => {
    expect(
      isUnknownTicket(new ApiError(400, { type: "https://civictrack.example/problems/validation-failed" })),
    ).toBe(false);
  });

  it("is false for a bodiless 400, because nothing says the ticket is absent", () => {
    expect(isUnknownTicket(new ApiError(400, null))).toBe(false);
  });

  it("is false for 500 and for a transport failure, which are retryable", () => {
    expect(isUnknownTicket(new ApiError(500, null))).toBe(false);
    expect(isUnknownTicket(new TypeError("Failed to fetch"))).toBe(false);
    expect(isUnknownTicket(undefined)).toBe(false);
  });
});
