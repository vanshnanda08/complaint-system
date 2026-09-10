import { describe, expect, it } from "vitest";
import {
  MAX_ACCURACY_M,
  MIN_PROOF_NOTE,
  composerSchema,
  ingestSchema,
  proofSchema,
  registerSchema,
} from "./schemas";

/**
 * The client-side rules that mirror server rules.
 *
 * Each of these exists to turn a server rejection into something a person can
 * fix before spending a photo upload and a round trip on it. A test failing
 * here means the client would let through something the server refuses -- or,
 * worse, refuse something the server would have accepted.
 */

const valid = {
  categoryCode: "POTHOLE",
  lat: 30.93,
  lng: 75.82,
  accuracyM: 12.5,
  manualPin: false,
  description: "Deep pothole across the lane",
  landmark: "Near the bus stop",
  hasPhoto: true as const,
};

describe("composerSchema", () => {
  it("accepts a complete report", () => {
    expect(composerSchema.safeParse(valid).success).toBe(true);
  });

  it("rejects GPS accuracy worse than the server's threshold, and says what to do", () => {
    // civictrack.clustering.max-accuracy-m = 150. Above it the server answers a
    // ProblemDetail; catching it here saves the upload.
    const r = composerSchema.safeParse({ ...valid, accuracyM: MAX_ACCURACY_M + 0.1 });
    expect(r.success).toBe(false);
    if (!r.success) {
      expect(r.error.issues[0].message).toMatch(/Place the pin on the map/);
    }
  });

  it("accepts accuracy exactly at the threshold", () => {
    // The server's check is "> max", so the boundary itself is valid. Being
    // stricter here would refuse reports the system would have accepted.
    expect(composerSchema.safeParse({ ...valid, accuracyM: MAX_ACCURACY_M }).success).toBe(true);
  });

  it("requires a photo", () => {
    const r = composerSchema.safeParse({ ...valid, hasPhoto: false });
    expect(r.success).toBe(false);
    if (!r.success) expect(r.error.issues[0].message).toMatch(/photo is required/i);
  });

  it("requires a category", () => {
    expect(composerSchema.safeParse({ ...valid, categoryCode: "" }).success).toBe(false);
  });

  it("requires a location", () => {
    expect(composerSchema.safeParse({ ...valid, lat: undefined }).success).toBe(false);
    expect(composerSchema.safeParse({ ...valid, lng: undefined }).success).toBe(false);
  });

  it("rejects coordinates outside the globe, which is how a lat/lng swap shows up", () => {
    // A swapped pair puts a Ludhiana longitude of 75.82 into lat, which is
    // in range -- but 175 is not, and this is the cheap structural catch the
    // server's @DecimalMin/@DecimalMax also make.
    expect(composerSchema.safeParse({ ...valid, lat: 175 }).success).toBe(false);
    expect(composerSchema.safeParse({ ...valid, lng: 200 }).success).toBe(false);
  });

  it("treats description and landmark as optional", () => {
    expect(composerSchema.safeParse({ ...valid, description: "", landmark: "" }).success).toBe(true);
  });

  it("enforces the server's length caps", () => {
    expect(composerSchema.safeParse({ ...valid, description: "x".repeat(2001) }).success).toBe(false);
    expect(composerSchema.safeParse({ ...valid, landmark: "x".repeat(201) }).success).toBe(false);
  });
});

describe("ingestSchema", () => {
  const body = { ...valid, photoUrl: "https://example.test/a.jpg", deviceId: "d1" };

  it("accepts the real payload once the photo has a URL", () => {
    const { hasPhoto: _unused, ...rest } = body;
    void _unused;
    expect(ingestSchema.safeParse(rest).success).toBe(true);
  });

  it("refuses a blank photoUrl, which the server rejects as @NotBlank", () => {
    const { hasPhoto: _unused, ...rest } = body;
    void _unused;
    expect(ingestSchema.safeParse({ ...rest, photoUrl: "" }).success).toBe(false);
  });
});

describe("proofSchema", () => {
  const longEnough = "Filled the pothole and resurfaced the patch.";

  it("accepts a real account of the work", () => {
    expect(proofSchema.safeParse({ note: longEnough, hasPhoto: true }).success).toBe(true);
  });

  it("rejects a note shorter than the server's minimum", () => {
    // ResolveRequest.note is @Size(min = 20). "Done" is not an account of what
    // was done, and every reporter reads it.
    const r = proofSchema.safeParse({ note: "Done", hasPhoto: true });
    expect(r.success).toBe(false);
    if (!r.success) expect(r.error.issues[0].message).toMatch(new RegExp(String(MIN_PROOF_NOTE)));
  });

  it("does not let whitespace pad a note to the minimum", () => {
    expect(proofSchema.safeParse({ note: " ".repeat(40), hasPhoto: true }).success).toBe(false);
  });

  it("requires proof photography", () => {
    expect(proofSchema.safeParse({ note: longEnough, hasPhoto: false }).success).toBe(false);
  });
});

describe("registerSchema", () => {
  it("requires a password of at least TEN characters, matching the server", () => {
    // The boundary, not a value either side of it. This test previously used
    // "short" (5) and "longenough" (10), which behave identically whether the
    // minimum is 8 or 10 -- so it passed while the client said 8 and the
    // server said 10, and an eight-character password failed with a 400 after
    // the form had told the user it was fine.
    //
    // If RegisterRequest's @Size(min = 10) ever changes, this is the test that
    // should go red.
    const base = { fullName: "A Citizen", email: "a@b.test" };
    expect(registerSchema.safeParse({ ...base, password: "123456789" }).success).toBe(false);
    expect(registerSchema.safeParse({ ...base, password: "1234567890" }).success).toBe(true);
  });

  it("rejects a malformed email", () => {
    expect(
      registerSchema.safeParse({ fullName: "A", email: "not-an-email", password: "longenough" })
        .success,
    ).toBe(false);
  });
});
