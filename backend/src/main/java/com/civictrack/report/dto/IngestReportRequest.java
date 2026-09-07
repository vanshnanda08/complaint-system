package com.civictrack.report.dto;

import com.civictrack.clustering.IngestReportCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The ingest request body.
 *
 * <p>Bounds here are structural -- a latitude outside [-90, 90] is a malformed
 * request, not a business decision. The accuracy threshold that decides whether
 * a report is usable is deliberately NOT enforced here: it is category-adjacent
 * policy that belongs with the clustering engine, where it produces a problem
 * response carrying the threshold and a remedy rather than a bare 400.
 */
public record IngestReportRequest(

        @NotBlank(message = "categoryCode is required")
        String categoryCode,

        @NotNull(message = "lat is required")
        @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
        Double lat,

        @NotNull(message = "lng is required")
        @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
        Double lng,

        @NotNull(message = "accuracyM is required")
        @Positive(message = "accuracyM must be positive")
        Double accuracyM,

        boolean manualPin,

        @Size(max = 2000) String description,
        @Size(max = 300) String addressText,
        @Size(max = 200) String landmark,

        @NotBlank(message = "photoUrl is required")
        String photoUrl,

        @Size(max = 64) String photoHash,
        @Size(max = 64) String deviceId
) {
    /**
     * Builds the service command. The reporter id comes from the authenticated
     * principal rather than the body -- a client must never be able to attribute
     * a report to another user by naming them.
     */
    public IngestReportCommand toCommand(UUID reporterId) {
        return new IngestReportCommand(
                categoryCode, lat, lng, accuracyM, manualPin,
                description, addressText, landmark,
                photoUrl, photoHash, reporterId, deviceId);
    }
}
