import { describe, expect, it } from "vitest";
import { ISSUE_STATUSES } from "./status";
import { nextAction, waitingOn, type ActorContext } from "./transitions";

/**
 * The transition table, as the work view renders it.
 *
 * These assertions are taken from `TransitionPolicy`'s constructor on the
 * server, not from what this codebase previously believed. An earlier version
 * of this file asserted that ACKNOWLEDGED offers "Start work" — which is what
 * the client did, and which the server answered 409 to every single time. The
 * test passed, because it encoded the same mistake as the code it was testing.
 *
 * The lesson is that a test written from the same assumption as the
 * implementation verifies nothing. Every expectation below was checked against
 * `TransitionPolicy` and, for the critical path, against the running server.
 */

const crew: ActorContext = { role: "STAFF", userId: "u1", assignedTo: "u1" };
const otherCrew: ActorContext = { role: "STAFF", userId: "u2", assignedTo: "u1" };
const supervisor: ActorContext = { role: "SUPERVISOR", userId: "s1", assignedTo: "u1" };

describe("nextAction mirrors TransitionPolicy", () => {
  it("offers acknowledge on a new issue", () => {
    expect(nextAction("NEW", crew)).toEqual({ verb: "acknowledge", label: "Acknowledge" });
  });

  it("offers NOTHING on an acknowledged issue, because assignment comes first", () => {
    // The server has no ACKNOWLEDGED -> IN_PROGRESS rule. Offering "Start work"
    // here produced a 409 on every click.
    expect(nextAction("ACKNOWLEDGED", crew)).toBeNull();
    expect(nextAction("ACKNOWLEDGED", supervisor)).toBeNull();
    expect(waitingOn("ACKNOWLEDGED", crew)).toMatch(/assign/i);
  });

  it("offers start work to the assignee once it is assigned", () => {
    expect(nextAction("ASSIGNED", crew)?.verb).toBe("start");
  });

  it("offers nothing to a staff member who is not the assignee", () => {
    // Guards.IS_ASSIGNEE would refuse them, so offering it would be a button
    // that exists only to fail.
    expect(nextAction("ASSIGNED", otherCrew)).toBeNull();
    expect(waitingOn("ASSIGNED", otherCrew)).toMatch(/somebody else/i);
  });

  it("lets a supervisor act regardless of assignee, as the guard does", () => {
    expect(nextAction("ASSIGNED", supervisor)?.verb).toBe("start");
    expect(nextAction("IN_PROGRESS", supervisor)?.verb).toBe("submit-for-verification");
  });

  it("offers start work on a reopened issue with no assignee check", () => {
    // REOPENED -> IN_PROGRESS carries no IS_ASSIGNEE guard on the server: a
    // reopened issue may have lost its assignee and somebody must pick it up.
    expect(nextAction("REOPENED", { role: "STAFF", userId: "u9", assignedTo: null })?.verb).toBe(
      "start",
    );
  });

  it("offers submission for citizen verification while work is under way", () => {
    expect(nextAction("IN_PROGRESS", crew)?.verb).toBe("submit-for-verification");
  });

  it("offers NOTHING once citizens hold it, or once it is finished", () => {
    // Absent, not disabled (blueprint §3.15).
    for (const s of ["PENDING_VERIFICATION", "RESOLVED", "CLOSED", "REJECTED"] as const) {
      expect(nextAction(s, supervisor), s).toBeNull();
    }
  });

  it("never offers a verb reaching RESOLVED or CLOSED, for any actor", () => {
    // The absence IS the feature: staff cannot resolve their own work.
    for (const actor of [crew, otherCrew, supervisor]) {
      const verbs = ISSUE_STATUSES.map((s) => nextAction(s, actor)?.verb).filter(Boolean);
      expect(verbs).not.toContain("resolve");
      expect(verbs).not.toContain("close");
    }
  });

  it("never labels anything 'Resolve'", () => {
    // Blueprint §9: the vocabulary difference is the project's central argument.
    for (const s of ISSUE_STATUSES) {
      expect(nextAction(s, supervisor)?.label ?? "").not.toMatch(/^resolve$/i);
    }
  });

  it("explains the wait for every status, whether or not there is an action", () => {
    for (const s of ISSUE_STATUSES) {
      expect(waitingOn(s, crew), `${s} has no explanation`).toBeTruthy();
    }
  });
});
