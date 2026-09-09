package com.civictrack.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Deletes Cloudinary assets that no report points at.
 *
 * <p>WHY THE LEAK EXISTS. The photo is uploaded from the browser BEFORE the
 * ingest request is sent, and deliberately so: the upload is a slow
 * third-party call and it must not happen while the clustering engine holds
 * {@code pg_advisory_xact_lock}. The consequence is that every abandoned
 * submission -- the user closes the tab, validation rejects the report, the
 * network drops between upload and ingest -- leaves an asset with no row.
 * Nothing ever reads it and nothing ever removes it.
 *
 * <p><b>THIS JOB IS DRY-RUN BY DEFAULT, AND THAT IS THE POINT.</b> It deletes
 * things, using a query, against a third party, on a schedule, unattended. If
 * the query is wrong the damage is silent and unrecoverable. So the default is
 * to log what it would have deleted and delete nothing; turning it destructive
 * is an explicit act by whoever has read a few runs of that log and is
 * satisfied it is right.
 *
 * <p>Three further rails, each for a specific failure:
 * <ul>
 *   <li><b>{@code minAge}, default 24h.</b> An asset uploaded seconds ago and
 *       not yet ingested is indistinguishable from an orphan. Without a floor
 *       this job would race every live submission and win.
 *   <li><b>{@code maxDeletions}, default 200.</b> If the orphan query is ever
 *       wrong, this is the difference between losing a few assets and emptying
 *       the account before the next morning.
 *   <li><b>Batched existence check.</b> One query per asset would be one round
 *       trip per asset over a pooled connection capped at 8. It asks about a
 *       page of ids at a time instead.
 * </ul>
 *
 * <p>Absent credentials mean it does nothing at all and says so once. That is
 * the normal state locally and in any environment where uploads are still
 * going to the placeholder.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CloudinaryOrphanSweep {

    private final CloudinaryProperties props;
    private final CloudinaryAdminClient client;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record Result(int scanned, int orphans, int deleted, boolean dryRun) {
        public static Result skipped() {
            return new Result(0, 0, 0, true);
        }
    }

    public Result sweep() {
        if (!props.configured()) {
            log.debug("Cloudinary orphan sweep skipped: no Admin API credentials configured.");
            return Result.skipped();
        }

        Instant cutoff = clock.instant().minus(props.minAge());
        int scanned = 0;
        int orphanCount = 0;
        int deleted = 0;
        String cursor = null;

        do {
            CloudinaryAdminClient.Page page = client.listImages(cursor);
            cursor = page.nextCursor();
            scanned += page.assets().size();

            // Old enough to be judged. Anything newer may simply be mid-flight.
            List<String> candidates = page.assets().stream()
                    .filter(a -> a.createdAt().isBefore(cutoff))
                    .map(CloudinaryAdminClient.Asset::publicId)
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }

            List<String> orphans = orphansAmong(candidates);
            orphanCount += orphans.size();

            for (String publicId : orphans) {
                if (deleted >= props.maxDeletions()) {
                    log.warn("Cloudinary orphan sweep hit its per-run cap of {}. "
                             + "Stopping. If this is expected, raise "
                             + "civictrack.cloudinary.max-deletions; if it is not, the "
                             + "orphan query is wrong and the cap just saved the account.",
                             props.maxDeletions());
                    cursor = null;
                    break;
                }
                if (props.dryRun()) {
                    log.info("Cloudinary orphan sweep (DRY RUN) would delete {}", publicId);
                } else {
                    client.delete(publicId);
                    log.info("Cloudinary orphan sweep deleted {}", publicId);
                }
                deleted++;
            }
        } while (cursor != null);

        log.info("Cloudinary orphan sweep complete: {} scanned, {} orphaned, {} {}.",
                scanned, orphanCount, deleted, props.dryRun() ? "would be deleted (dry run)" : "deleted");
        return new Result(scanned, orphanCount, deleted, props.dryRun());
    }

    /**
     * Of these public ids, the ones no report references.
     *
     * <p>Matched with {@code LIKE '%' || id || '%'} rather than on equality,
     * because the column holds a full delivery URL and now carries a
     * transformation segment in the middle of it -- see
     * {@code withDelivery} in the frontend. Comparing the stored URL to a
     * reconstructed one would break the day either the transformation or the
     * delivery host changes, and it would break in the direction that DELETES
     * live photos. The public id is the stable part.
     */
    List<String> orphansAmong(List<String> publicIds) {
        if (publicIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT c.public_id
                  FROM unnest(?) AS c(public_id)
                 WHERE NOT EXISTS (
                       SELECT 1 FROM reports r
                        WHERE r.photo_url LIKE '%' || c.public_id || '%')
                """;
        return jdbc.query(
                sql,
                ps -> ps.setArray(1, ps.getConnection()
                        .createArrayOf("text", publicIds.toArray(String[]::new))),
                (rs, i) -> rs.getString(1));
    }
}
