package com.civictrack.verification;

import com.civictrack.issue.IssueStatus;
import lombok.Getter;

import java.util.UUID;

/**
 * The issue is not waiting on citizens. Either nobody has claimed a fix yet, or
 * the vote has already been settled -- by other reporters or by the timeout.
 */
@Getter
public class VerificationNotOpenException extends RuntimeException {

    private final UUID issueId;
    private final IssueStatus status;

    public VerificationNotOpenException(UUID issueId, IssueStatus status) {
        super("This issue is not waiting for verification; it is " + status + ".");
        this.issueId = issueId;
        this.status = status;
    }
}
