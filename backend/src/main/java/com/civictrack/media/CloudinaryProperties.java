package com.civictrack.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cloudinary credentials for the orphan sweep, and its safety rails.
 *
 * <p>The upload itself needs none of this. The browser uploads directly with an
 * unsigned preset, so the only secret in that path is a preset name that is
 * public by construction (DD-053). The Admin API is a different matter: it can
 * list and DELETE, so it needs a real key and secret, and those belong in the
 * environment and nowhere else.
 *
 * @param cloudName    the account. Same value the frontend uses.
 * @param apiKey       Admin API key. Absent means the sweep does not run.
 * @param apiSecret    Admin API secret. Absent means the sweep does not run.
 * @param dryRun       <b>Defaults to true.</b> See the note in
 *                     {@link CloudinaryOrphanSweep} -- this job deletes things,
 *                     and the default for a job that deletes things should be
 *                     to tell you what it would have done.
 * @param minAge       assets younger than this are never touched. This is the
 *                     race the phase-5 brief names: the photo is uploaded
 *                     BEFORE the ingest transaction opens, so between those two
 *                     moments a perfectly good asset has no report row. A sweep
 *                     with no floor would delete the photo of a report being
 *                     submitted at that instant.
 * @param maxDeletions a per-run cap. If the orphan query is ever wrong, this is
 *                     the difference between losing a few assets and losing the
 *                     account's contents before anyone notices.
 */
@ConfigurationProperties(prefix = "civictrack.cloudinary")
public record CloudinaryProperties(
        String cloudName,
        String apiKey,
        String apiSecret,
        @DefaultValue("true") boolean dryRun,
        @DefaultValue("PT24H") Duration minAge,
        @DefaultValue("200") int maxDeletions
) {

    /** True only when every credential needed to call the Admin API is present. */
    public boolean configured() {
        return notBlank(cloudName) && notBlank(apiKey) && notBlank(apiSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
