package com.civictrack.issue;

import java.util.UUID;

/** No issue with that id. Answered with 404. */
public class IssueNotFoundException extends RuntimeException {

    public IssueNotFoundException(UUID id) {
        super("No issue with id " + id);
    }

    /**
     * The same 404 for a ticket reference that resolves to nothing. The
     * message names the reference the caller actually typed, because they
     * typed it off a piece of paper and the useful answer is "that is not a
     * ticket number here", not "no issue with id <uuid>".
     */
    public IssueNotFoundException(String publicRef) {
        super("No issue with reference " + publicRef);
    }
}
