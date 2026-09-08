package com.civictrack.issue.policy;

import lombok.Getter;

/**
 * The edge exists and the role may walk it, but a precondition failed.
 * Answered with 409, carrying the guard's name so a client can react to the
 * specific rule rather than string-matching prose.
 */
@Getter
public class TransitionGuardException extends RuntimeException {

    private final String guard;

    public TransitionGuardException(Guard guard) {
        super("Transition refused: " + guard.explanation());
        this.guard = guard.key();
    }
}
