import type { IssueStatus } from "./status";
import type { Role } from "./auth";

/**
 * The one action a staff member may take, given a status and who they are.
 *
 * A client-side mirror of `TransitionPolicy` on the server. It decides what to
 * RENDER, never what is permitted — `@PreAuthorize`, `@issueGuard` and the
 * policy's own guards are the authority, and every one of them still runs.
 *
 * THE TABLE, copied from TransitionPolicy's constructor rather than from
 * memory:
 *
 *   NEW           -> ACKNOWLEDGED          STAFF, SUPERVISOR, ADMIN
 *   ACKNOWLEDGED  -> ASSIGNED              SUPERVISOR, ADMIN   (needs an assignee)
 *   ASSIGNED      -> IN_PROGRESS           STAFF, SUPERVISOR, ADMIN  (guard: IS_ASSIGNEE)
 *   REOPENED      -> IN_PROGRESS           STAFF, SUPERVISOR, ADMIN
 *   IN_PROGRESS   -> PENDING_VERIFICATION  STAFF, SUPERVISOR, ADMIN
 *                                          (guards: IS_ASSIGNEE, photo, 20-char note)
 *
 * NOTE WHAT IS NOT THERE: `ACKNOWLEDGED -> IN_PROGRESS`. An acknowledged issue
 * has to be assigned to somebody before work can start, and assignment is a
 * supervisor's move. An earlier version of this file offered "Start work" on an
 * ACKNOWLEDGED issue and the server answered 409 every time — and the unit test
 * did not catch it, because the test asserted the table this file believed in
 * rather than the one the server enforces.
 *
 * There is deliberately no entry reaching RESOLVED or CLOSED. Staff cannot
 * resolve; they submit for citizen verification. The absence is the feature,
 * and `TransitionPolicy` refuses to construct if the server table ever
 * violates it.
 */
export interface StaffAction {
  /** The endpoint segment: POST /api/v1/issues/{id}/{verb} */
  verb: "acknowledge" | "start" | "submit-for-verification";
  label: string;
}

export interface ActorContext {
  role: Role;
  userId: string;
  /** The issue's current assignee, or null. */
  assignedTo: string | null;
}

export function nextAction(status: IssueStatus, actor: ActorContext): StaffAction | null {
  switch (status) {
    case "NEW":
      return { verb: "acknowledge", label: "Acknowledge" };

    case "ASSIGNED":
      // Guards.IS_ASSIGNEE: supervisors and admins pass regardless; a staff
      // member must be the assignee. Offering it to somebody who would be
      // refused is exactly the "disabled button" problem in another form.
      return canActAsAssignee(actor)
        ? { verb: "start", label: "Start work" }
        : null;

    case "REOPENED":
      // No IS_ASSIGNEE guard on this one: a reopened issue may have lost its
      // assignee, and somebody has to be able to pick it up.
      return { verb: "start", label: "Start work" };

    case "IN_PROGRESS":
      return canActAsAssignee(actor)
        ? // Never "Resolve". The label is the transition table made visible to
          // the person governed by it (blueprint §9).
          { verb: "submit-for-verification", label: "Submit for citizen verification" }
        : null;

    case "ACKNOWLEDGED":
    case "PENDING_VERIFICATION":
    case "RESOLVED":
    case "CLOSED":
    case "REJECTED":
      return null;
  }
}

function canActAsAssignee(actor: ActorContext): boolean {
  if (actor.role === "SUPERVISOR" || actor.role === "ADMIN") return true;
  return actor.assignedTo !== null && actor.assignedTo === actor.userId;
}

/**
 * What the issue is waiting on, when there is nothing for this person to do.
 *
 * Every state that yields no action gets a sentence. "There is no action" on
 * its own tells somebody nothing about whether they are blocked, finished, or
 * looking at somebody else's work.
 */
export function waitingOn(status: IssueStatus, actor: ActorContext): string {
  switch (status) {
    case "ACKNOWLEDGED":
      return "Acknowledged, and waiting for a supervisor to assign it to a crew member. Work cannot start until it is assigned.";
    case "ASSIGNED":
      return canActAsAssignee(actor)
        ? "Assigned and ready to start."
        : "Assigned to somebody else in your department.";
    case "IN_PROGRESS":
      return canActAsAssignee(actor)
        ? "Work is under way."
        : "Somebody else in your department is working on this.";
    case "PENDING_VERIFICATION":
      return "Waiting for the people who reported this to confirm the fix. The SLA clock is paused while they decide.";
    case "RESOLVED":
      return "Citizens confirmed this was fixed. Nothing further is needed from the department.";
    case "CLOSED":
      return "Closed. Only an administrator can reopen it.";
    case "REJECTED":
      return "Rejected by a supervisor.";
    case "NEW":
      return "Not yet acknowledged.";
    case "REOPENED":
      return "Reopened. The fix did not hold.";
  }
}
