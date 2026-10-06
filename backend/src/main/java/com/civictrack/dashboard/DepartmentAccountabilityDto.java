package com.civictrack.dashboard;

import java.util.UUID;

/**
 * One department's verification record, as published.
 *
 * <p>The two rates are null, not zero, when their denominator is zero. A
 * department that has resolved nothing has not achieved a 0% unverified rate;
 * it has no rate. The dashboard says so in words.
 */
public record DepartmentAccountabilityDto(
        UUID departmentId,
        String departmentName,
        long resolved,
        long resolvedWithoutVerification,
        Double unverifiedRate,
        long fixesClaimed,
        long reopened,
        Double reopenRate
) {
    static DepartmentAccountabilityDto from(com.civictrack.issue.IssueRepository.DepartmentAccountabilityRow r) {
        return new DepartmentAccountabilityDto(
                r.getDepartmentId(), r.getDepartmentName(),
                r.getResolved(), r.getUnverified(), rate(r.getUnverified(), r.getResolved()),
                r.getFixesClaimed(), r.getReopened(), rate(r.getReopened(), r.getFixesClaimed()));
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
