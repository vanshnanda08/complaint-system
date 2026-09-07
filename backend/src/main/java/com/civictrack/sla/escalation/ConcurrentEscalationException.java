package com.civictrack.sla.escalation;

import java.util.UUID;

/** The compare-and-swap found the level already moved. Rolls back the attempt. */
public class ConcurrentEscalationException extends RuntimeException {

    public ConcurrentEscalationException(UUID issueId) {
        super("Issue " + issueId + " was escalated concurrently; this attempt is discarded");
    }
}
