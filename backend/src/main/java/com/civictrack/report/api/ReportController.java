package com.civictrack.report.api;

import com.civictrack.clustering.ClusterOutcome;
import com.civictrack.clustering.ClusteringService;
import com.civictrack.report.dto.ClusterResultDto;
import com.civictrack.report.dto.IngestReportRequest;
import com.civictrack.user.Actor;
import com.civictrack.user.Role;
import com.civictrack.user.auth.Actors;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * The ingest endpoint.
 *
 * <p>Phase 3 accepts a JSON body carrying an already-hosted {@code photoUrl}.
 * The multipart variant, where the server uploads to Cloudinary itself, arrives
 * with the photo pipeline -- and when it does the upload will happen
 * <em>before</em> this transaction opens, because a slow third-party call must
 * never be made while holding the spatial advisory lock.
 *
 * <p><b>Reporting stays open to anonymous submission (DD-017).</b> A civic
 * platform that demands registration before somebody can report a pothole
 * collects fewer potholes, and it collects them disproportionately from people
 * already inclined to trust the municipality -- which is the opposite of the
 * population the accountability argument is about. So this path is
 * {@code permitAll}, and identity is <em>attached when present</em> rather than
 * demanded: a caller who does hold a token gets their reports linked to their
 * account, which is what makes them eligible to verify a fix later.
 *
 * <p>The reporter id comes from the validated token and never from the request
 * body. That property predates authentication -- {@code toCommand} has taken it
 * as a parameter since phase 2 -- specifically so that adding auth could not
 * turn into a client-supplied field.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ClusteringService clusteringService;

    @PostMapping
    public ResponseEntity<ClusterResultDto> ingest(@Valid @RequestBody IngestReportRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        ClusterOutcome outcome = clusteringService.ingest(request.toCommand(reporterId(jwt)));

        // 201 with a Location header pointing at the issue, not the report: the
        // issue is the thing the citizen will come back to look at.
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/issues/{id}")
                        .buildAndExpand(outcome.issueId()).toUri())
                .body(ClusterResultDto.from(outcome));
    }

    /**
     * The authenticated citizen, or null for an anonymous report.
     *
     * <p>Staff accounts report as themselves too -- a supervisor who spots a
     * broken streetlight on the way to work is a reporter like anybody else,
     * and attributing it to them is more useful than discarding the identity.
     * The one thing that is not done here is inventing an identity for a caller
     * who does not have one.
     */
    private static UUID reporterId(Jwt jwt) {
        Actor actor = Actors.from(jwt);
        return actor == null || actor.role() == Role.SYSTEM ? null : actor.id();
    }
}
