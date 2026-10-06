package com.civictrack.moderation;

/**
 * A split, merge, confirmation or recategorisation that cannot be made, with
 * the sentence explaining why. Rendered verbatim by the client (409), like a
 * refused transition: the server is the authority on why, and its reason is
 * the only useful thing a supervisor can be shown.
 */
public class ModerationRefusedException extends RuntimeException {
    public ModerationRefusedException(String detail) {
        super(detail);
    }
}
