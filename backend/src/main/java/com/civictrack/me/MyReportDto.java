package com.civictrack.me;

import com.civictrack.publicapi.dto.PublicIssueDto;
import com.civictrack.report.Report;

import java.time.Instant;
import java.util.UUID;

/**
 * One of the caller's own reports, with the issue it landed in.
 *
 * <p>The grain is the report, not the issue, because blueprint 3.11 asks for
 * "what the user reported" and "whether their report merged into an existing
 * issue" -- both facts about the submission rather than about the ticket. A
 * citizen who reported the same pothole twice, weeks apart, has two rows here
 * and one ticket, and collapsing them would hide the second submission from
 * the only person entitled to see it.
 *
 * <p>{@code merged} is the honest reading of the cluster decision for a
 * citizen audience: it says their report joined an existing case, without
 * exposing which band or policy produced that (blueprint 9 -- a citizen never
 * sees "MERGED_LOW_CONF").
 */
public record MyReportDto(
        UUID reportId,
        Instant reportedAt,
        String description,
        String landmark,
        String photoUrl,
        boolean merged,
        PublicIssueDto issue
) {
    public static MyReportDto from(Report r, PublicIssueDto issue) {
        return new MyReportDto(
                r.getId(), r.getCreatedAt(),
                r.getDescription(), r.getLandmark(), r.getPhotoUrl(),
                switch (r.getClusterDecision()) {
                    case MERGED, MERGED_LOW_CONF -> true;
                    case NEW_ISSUE, SPLIT_LOW_CONF, SPLIT_EXTENT_CAPPED, MANUAL -> false;
                },
                issue);
    }
}
