package com.civictrack.issue;

import java.util.UUID;

/** No issue with that id. Answered with 404. */
public class IssueNotFoundException extends RuntimeException {

    public IssueNotFoundException(UUID id) {
        super("No issue with id " + id);
    }
}
