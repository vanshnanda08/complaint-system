package com.civictrack.verification;

import lombok.Getter;

import java.util.UUID;

/** One vote per citizen per fix. Carries the vote already recorded. */
@Getter
public class AlreadyVerifiedException extends RuntimeException {

    private final UUID issueId;
    private final Verdict verdict;

    public AlreadyVerifiedException(UUID issueId, Verdict verdict) {
        super("You have already answered for this fix: "
              + (verdict == Verdict.FIXED ? "fixed" : "not fixed") + ".");
        this.issueId = issueId;
        this.verdict = verdict;
    }
}
