package com.civictrack.report.api;

import com.civictrack.clustering.ClusterOutcome;
import com.civictrack.clustering.ClusteringService;
import com.civictrack.report.dto.ClusterResultDto;
import com.civictrack.report.dto.IngestReportRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The ingest endpoint.
 *
 * <p>Phase 2 accepts a JSON body carrying an already-hosted {@code photoUrl}.
 * The multipart variant, where the server uploads to Cloudinary itself, arrives
 * in phase 6 -- and when it does the upload will happen <em>before</em> this
 * transaction opens, because a slow third-party call must never be made while
 * holding the spatial advisory lock.
 *
 * <p>Authentication arrives in phase 5. Until then every report is anonymous
 * and identified by {@code deviceId}, which is what the schema's
 * reporter-or-device check already requires.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ClusteringService clusteringService;

    @PostMapping
    public ResponseEntity<ClusterResultDto> ingest(@Valid @RequestBody IngestReportRequest request) {
        ClusterOutcome outcome = clusteringService.ingest(request.toCommand(null));

        // 201 with a Location header pointing at the issue, not the report: the
        // issue is the thing the citizen will come back to look at.
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/issues/{id}")
                        .buildAndExpand(outcome.issueId()).toUri())
                .body(ClusterResultDto.from(outcome));
    }
}
