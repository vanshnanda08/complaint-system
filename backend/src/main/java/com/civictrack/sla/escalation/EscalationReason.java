package com.civictrack.sla.escalation;

/** Why an issue moved up the ladder. Stored on the event, never inferred. */
public enum EscalationReason {

    /** The effective deadline passed while the clock was running. */
    SLA_BREACH,

    /** Citizens rejected a fix, or the problem recurred inside the window. */
    REOPEN,

    /** A supervisor escalated by hand. */
    MANUAL
}
