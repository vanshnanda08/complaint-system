package com.civictrack.media;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The slice of Cloudinary's Admin API this project needs: list images, delete
 * one.
 *
 * <p>No Cloudinary SDK. The SDK is a transitive dependency tree for two HTTP
 * calls, and it would have to be on the BACKEND classpath -- where the rest of
 * the application deliberately knows nothing about Cloudinary at all, because
 * ingest takes a URL string and does not care where it came from. Two calls
 * with {@code RestClient} keeps that boundary intact.
 *
 * <p>Auth is HTTP Basic with the API key and secret, which is what the Admin
 * API expects. The credentials come from {@link CloudinaryProperties} and are
 * never logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CloudinaryAdminClient {

    private static final int PAGE_SIZE = 100;

    private final CloudinaryProperties props;
    private final RestClient.Builder restClientBuilder;

    public record Asset(String publicId, Instant createdAt) {}

    public record Page(List<Asset> assets, String nextCursor) {}

    private RestClient client() {
        String basic = Base64.getEncoder().encodeToString(
                (props.apiKey() + ":" + props.apiSecret()).getBytes());
        return restClientBuilder
                .baseUrl("https://api.cloudinary.com/v1_1/" + props.cloudName())
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    /** One page of image assets. Pass the previous page's cursor, or null. */
    public Page listImages(String cursor) {
        JsonNode body = client().get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/resources/image").queryParam("max_results", PAGE_SIZE);
                    if (cursor != null) {
                        uriBuilder.queryParam("next_cursor", cursor);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(JsonNode.class);

        List<Asset> assets = new ArrayList<>();
        if (body != null && body.has("resources")) {
            for (JsonNode r : body.get("resources")) {
                assets.add(new Asset(
                        r.path("public_id").asText(),
                        Instant.parse(r.path("created_at").asText())));
            }
        }
        String next = body != null && body.hasNonNull("next_cursor")
                ? body.get("next_cursor").asText()
                : null;
        return new Page(assets, next);
    }

    /**
     * Deletes one asset.
     *
     * <p>Failures are logged and swallowed rather than thrown. A single asset
     * that will not delete -- already gone, or a transient 5xx -- must not
     * abandon the rest of the sweep, and there is nothing for a caller to do
     * about it that waiting until tomorrow does not also do.
     */
    public void delete(String publicId) {
        try {
            client().delete()
                    .uri(b -> b.path("/resources/image/upload")
                              .queryParam("public_ids[]", publicId)
                              .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Could not delete Cloudinary asset {}: {}", publicId, e.getMessage());
        }
    }
}
