import { defineConfig } from "vitest/config";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Unit tests for the logic that mirrors a server rule.
 *
 * Deliberately NOT a component-rendering suite. What is worth guarding here is
 * the handful of places where the frontend restates something the backend also
 * knows -- the SLA clock's definition of "running", the transition table, the
 * accuracy and note thresholds -- because those are the things that can drift
 * silently and be wrong in production while every screen still renders.
 *
 * Screens are verified by driving the real application against the real
 * backend, which catches a different class of problem and is recorded in the
 * phase's verification notes.
 */
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
  resolve: {
    alias: { "@": path.join(path.dirname(fileURLToPath(import.meta.url)), "src") },
  },
});
