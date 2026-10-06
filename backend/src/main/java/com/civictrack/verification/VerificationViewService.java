package com.civictrack.verification;

import com.civictrack.issue.Issue;
import com.civictrack.issue.IssueNotFoundException;
import com.civictrack.issue.IssueRepository;
import com.civictrack.issue.IssueStatus;
import com.civictrack.issue.policy.VerificationTally;
import com.civictrack.publicapi.dto.PublicIssueDto;
import com.civictrack.report.Report;
import com.civictrack.report.ReportRepository;
import com.civictrack.user.Actor;
import com.civictrack.user.Role;
import com.civictrack.verification.dto.AwaitingVerdictDto;
import com.civictrack.verification.dto.VerificationViewDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The reads behind the verify screen and the "waiting on you" list. */
@Service
@RequiredArgsConstructor
public class VerificationViewService {

    private final IssueRepository issues;
    private final ReportRepository reports;
    private final VerificationRepository verifications;
    private final VerificationQuorumService quorum;
    private final Clock clock;

    @Transactional(readOnly = true)
    public VerificationViewDto view(UUID issueId, Actor actor) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new IssueNotFoundException(issueId));
        PublicIssueDto publicIssue = issues.findPublicById(issueId).map(PublicIssueDto::from)
                .orElseThrow(() -> new IssueNotFoundException(issueId));

        List<Report> members = reports.findByIssueIdOrderByCreatedAtAsc(issueId);
        // "Before" is the caller's own photo where they have one -- the thing
        // they actually saw -- and the first report's otherwise.
        String before = members.stream()
                .filter(r -> actor.id().equals(r.getReporterId()))
                .findFirst()
                .or(() -> members.stream().findFirst())
                .map(Report::getPhotoUrl)
                .orElse(null);
        boolean reported = members.stream().anyMatch(r -> actor.id().equals(r.getReporterId()));

        String ineligible = actor.role() != Role.CITIZEN
                ? "Only citizens who reported this problem can verify the fix. Municipal accounts cannot vote on municipal work."
                : !reported
                        ? "Only the people who reported this problem can verify the fix."
                        : null;

        Verification mine = issue.getVerificationRound() == 0 ? null
                : verifications.findByIssueIdAndVerificationRoundAndCitizenId(
                        issueId, issue.getVerificationRound(), actor.id()).orElse(null);

        VerificationTally tally = quorum.tally(issue, clock.instant());
        boolean pending = issue.getStatus() == IssueStatus.PENDING_VERIFICATION;

        return new VerificationViewDto(
                publicIssue,
                before,
                pending ? issue.getClockPausedAt() : null,
                quorum.silenceDeadline(issue),
                issue.getVerificationRound(),
                ineligible == null,
                ineligible,
                mine == null ? null : mine.getVerdict(),
                mine == null ? null : mine.getComment(),
                tally.confirmations(),
                tally.rejections(),
                tally.required());
    }

    @Transactional(readOnly = true)
    public List<AwaitingVerdictDto> awaiting(Actor actor) {
        List<UUID> ids = verifications.findAwaitingVerdictFrom(actor.id());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Issue> byId = issues.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Issue::getId, Function.identity()));
        return issues.findPublicByIdIn(ids).stream()
                .map(PublicIssueDto::from)
                .map(dto -> new AwaitingVerdictDto(dto, quorum.silenceDeadline(byId.get(dto.id()))))
                .sorted(Comparator.comparing(AwaitingVerdictDto::silenceDeadline,
                        Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
                .toList();
    }
}
