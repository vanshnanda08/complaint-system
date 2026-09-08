package com.civictrack.issue;

import com.civictrack.category.Category;
import com.civictrack.category.CategoryRepository;
import com.civictrack.sla.SlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DD-004: recomputes priority for every open issue, not only for issues that
 * received a new report.
 *
 * <p>The score contains a {@code 0.15 x age_hours} term, and phase 2 recomputed
 * it only on merge. An issue that receives exactly one report -- which is most
 * issues -- therefore held its creation-time score forever. That inverts the
 * intent precisely: the age term exists so that neglected issues rise, and
 * recomputing only on merge means the only issues that age are the ones already
 * receiving attention.
 *
 * <p>It runs inside the existing five-minute SLA sweep, which already scans
 * this set under a distributed lock. No new job, no new schedule, no second
 * lock to reason about.
 *
 * <p><b>On the write strategy.</b> Scores are computed in Java, from the one
 * {@link PriorityCalculator} the rest of the system uses, and then written a
 * batch at a time by a single {@code UPDATE ... FROM (VALUES ...)}. The obvious
 * alternative -- expressing the whole formula as one SQL {@code UPDATE} joined
 * to categories -- would be one statement for the entire table, but it would
 * put the scoring formula in two places, and the day they diverge the queue
 * order would silently stop matching the number displayed next to it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriorityRecomputer {

    /** Nil UUID: the starting point of the keyset pagination, before any id. */
    private static final UUID BEFORE_ALL = new UUID(0L, 0L);

    private final IssueRepository issueRepo;
    private final CategoryRepository categoryRepo;
    private final PriorityCalculator calculator;
    private final SlaService slaService;
    private final JdbcTemplate jdbc;

    /** Recomputes every open issue. Returns how many rows were rewritten. */
    public int recomputeAll(Instant now, int batchSize) {
        Map<String, Category> categories = categoryRepo.findAll().stream()
                .collect(Collectors.toMap(Category::getCode, Function.identity()));

        int total = 0;
        UUID cursor = BEFORE_ALL;
        while (true) {
            List<UUID> ids = issueRepo.findOpenIdsAfter(cursor, batchSize);
            if (ids.isEmpty()) {
                break;
            }
            total += recomputeBatch(issueRepo.findByIdIn(ids), categories, now);
            cursor = ids.get(ids.size() - 1);
            if (ids.size() < batchSize) {
                break;
            }
        }
        log.debug("Priority recompute touched {} open issues", total);
        return total;
    }

    /**
     * One batch, one statement, one transaction -- the implicit one around the
     * JDBC call. Not annotated {@code @Transactional}: it is invoked from
     * {@link #recomputeAll} in this same class, where a proxy-based annotation
     * would do nothing at all, and a batch that commits on its own is what lets
     * a sweep over ten thousand issues make progress rather than holding one
     * enormous transaction open for the duration.
     */
    int recomputeBatch(List<Issue> issues, Map<String, Category> categories, Instant now) {
        List<Object> args = new ArrayList<>();
        StringBuilder values = new StringBuilder();

        for (Issue issue : issues) {
            Category category = categories.get(issue.getCategoryCode());
            if (category == null) {
                continue;
            }
            double score = calculator.score(issue, category, now);
            Priority band = calculator.band(score);
            // The deadline the issue would have at the new band. LEAST below
            // is what makes an upgrade tighten it and a downgrade leave it
            // alone -- the asymmetry that stops a department buying time by
            // arguing an issue down a band.
            Instant candidateDue = slaService.deadlineFor(issue, category, band);

            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(?::uuid, ?::numeric, ?::varchar, ?::timestamptz)");
            args.add(issue.getId());
            args.add(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
            args.add(band.name());
            args.add(Timestamp.from(candidateDue));
        }

        if (args.isEmpty()) {
            return 0;
        }
        args.add(Timestamp.from(now));

        // One statement per batch. The version column is deliberately not
        // bumped: priority is a derived value that the next sweep recomputes
        // from scratch, so a concurrent ingest overwriting it costs nothing,
        // whereas bumping the version would make every in-flight ingest fail
        // with an optimistic-locking error for the sake of a number that is
        // about to be recalculated anyway.
        // On the CASE: once an issue has escalated, its deadline is a grant
        // tied to that escalation, not a function of the original report time,
        // and the recompute leaves it alone.
        //
        // This is not a nicety. For an issue that has already breached,
        // `first_reported_at + allowance` is by definition in the past, so
        // LEAST would pull the freshly re-armed deadline straight back behind
        // now -- and since escalation raises the score by 20 and often the
        // band with it, the recompute would undo the re-arm it had just caused.
        // The sweep would then escalate the same issue again on its next pass
        // and every pass after, climbing the ladder in minutes. The test
        // `escalationRearmsTheDeadlineRatherThanLeavingItInThePast` is what
        // found this; the deadline was landing two days in the past.
        //
        // Escalated issues still get their score and band rewritten, which is
        // what DD-004 is actually about: queue order. Their clock is governed
        // by the ladder, which halves it per rung -- a stronger tightening than
        // the band multiplier would have applied anyway.
        String sql = """
                UPDATE issues i
                   SET priority_score = v.score,
                       priority       = v.band,
                       due_at         = CASE WHEN i.last_escalated_at IS NULL
                                             THEN LEAST(i.due_at, v.due)
                                             ELSE i.due_at
                                        END,
                       updated_at     = ?
                  FROM (VALUES %s) AS v(id, score, band, due)
                 WHERE i.id = v.id
                """.formatted(values);

        // updated_at is the first placeholder in the statement text but the
        // last value appended, so it is moved to the front here.
        Object[] ordered = new Object[args.size()];
        ordered[0] = args.get(args.size() - 1);
        for (int i = 0; i < args.size() - 1; i++) {
            ordered[i + 1] = args.get(i);
        }
        return jdbc.update(sql, ordered);
    }
}
