import { describe, expect, it } from "vitest";
import { withDelivery } from "./uploadPhoto";

/**
 * The delivery transformation is string surgery on a URL that gets stored in
 * the database, so the cases that matter most are the ones where it must NOT
 * act. A slightly larger photo is a much better failure than a broken one.
 */
describe("withDelivery", () => {
  const CLOUDINARY =
    "https://res.cloudinary.com/fjavyddc/image/upload/v1788976355/msszf5bzq5m4zttooovk.jpg";

  it("inserts the transformation after /image/upload/", () => {
    expect(withDelivery(CLOUDINARY)).toBe(
      "https://res.cloudinary.com/fjavyddc/image/upload/f_auto,q_auto,w_1600,c_limit/" +
        "v1788976355/msszf5bzq5m4zttooovk.jpg",
    );
  });

  it("keeps the version and public id intact, in that order", () => {
    const out = withDelivery(CLOUDINARY);
    expect(out.endsWith("/v1788976355/msszf5bzq5m4zttooovk.jpg")).toBe(true);
    // Exactly one /image/upload/ -- a naive replace could duplicate it.
    expect(out.match(/\/image\/upload\//g)).toHaveLength(1);
  });

  it("leaves a URL alone when it is not shaped like a Cloudinary upload", () => {
    // The one that actually occurs: a report carrying an arbitrary photoUrl,
    // and the seed corpus's placeholder before Cloudinary was configured.
    for (const url of [
      "https://example.test/photo.jpg",
      "https://res.cloudinary.com/demo/video/upload/v1/sample.mp4",
      "not-a-url",
      "",
    ]) {
      expect(withDelivery(url)).toBe(url);
    }
  });

  it("does not stack a second transformation onto a URL that already has one", () => {
    // Re-running this over a stored URL must be idempotent, because a stored
    // URL is exactly what a retry or a re-render would hand it.
    const once = withDelivery(CLOUDINARY);
    expect(withDelivery(once)).toBe(once);
  });

  it("does not transform the placeholder used when Cloudinary is unconfigured", () => {
    // The placeholder lives on Cloudinary's public demo account, which this
    // project does not own -- so a transformation on it is a request against
    // somebody else's quota for no benefit.
    const placeholder = "https://res.cloudinary.com/demo/image/upload/sample.jpg";
    expect(withDelivery(placeholder)).toBe(placeholder);
  });
});
