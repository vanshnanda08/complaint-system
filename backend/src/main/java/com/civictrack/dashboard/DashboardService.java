package com.civictrack.dashboard;

import com.civictrack.issue.IssueRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * The public accountability numbers.
 *
 * <p>Two properties of this class are deliberate and worth stating, because
 * both look like mistakes.
 *
 * <p><b>Each figure is computed independently and a failure degrades to null
 * rather than propagating.</b> Ordinarily swallowing an exception is how a bug
 * becomes invisible, and the logging here exists so that it does not. But the
 * overdue count and the total resolved answer different questions, and the
 * landing page's stated fallback (blueprint 3.1) is to show the second when
 * the first is unavailable. That fallback is unimplementable if one failing
 * aggregate takes the document down with it.
 *
 * <p><b>Time comes from the injected {@link Clock}.</b> "Overdue" is a
 * statement about now, so the number this returns is a function of the clock;
 * taking it from {@code Instant.now()} would leave the dashboard's definition
 * of breach untestable while the sweep's is testable, and the two would drift
 * without anything going red.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final IssueRepository issues;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DashboardSummaryDto summary() {
        Instant now = clock.instant();

        Long overdue = tile("overdue count", () -> issues.countBreached(now));
        long resolved = tile("total resolved", issues::countResolved, 0L);

        return new DashboardSummaryDto(
                overdue,
                resolved,
                tile("top overdue ward", () -> issues.findTopBreachedWard(now)
                        .map(r -> new DashboardSummaryDto.NamedCount(r.getName(), r.getTotal()))
                        .orElse(null)),
                tile("top overdue department", () -> issues.findTopBreachedDepartment(now)
                        .map(r -> new DashboardSummaryDto.NamedCount(r.getName(), r.getTotal()))
                        .orElse(null)),
                now);
    }

    private <T> T tile(String name, Supplier<T> query) {
        return tile(name, query, null);
    }

    private <T> T tile(String name, Supplier<T> query, T fallback) {
        try {
            return query.get();
        } catch (RuntimeException ex) {
            // Logged at warn with the exception, because a tile that has been
            // silently null for a week is a broken dashboard nobody noticed.
            log.warn("Dashboard tile '{}' failed; serving fallback", name, ex);
            return fallback;
        }
    }
}
