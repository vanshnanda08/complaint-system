package com.civictrack.sla.escalation;

/** What one attempt at escalating a single issue actually did. */
public enum EscalationOutcome {

    /** The level advanced and an event row was written. */
    ESCALATED,

    /** Re-checked under the row lock and the issue is not late after all. */
    NOT_BREACHED,

    /** Already at the terminal rung. Appears in the chronic-breach list instead. */
    TERMINAL,

    /** Another worker recorded this level first. The unique constraint said so. */
    ALREADY_RECORDED,

    /** Another worker advanced the level between the lock and the update. */
    RACE_LOST,

    /** The issue disappeared between selection and processing. */
    MISSING
}
