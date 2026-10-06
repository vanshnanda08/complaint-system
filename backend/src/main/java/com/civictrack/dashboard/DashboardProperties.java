package com.civictrack.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The public dashboard's windows and its notion of a day.
 *
 * <p>{@code zone} exists because every daily series is a statement about
 * calendar days, and the database clock is UTC. Bucketed in UTC, a Ludhiana
 * day would end at 05:30 local time, and a report filed at breakfast would be
 * counted against the previous day.
 */
@ConfigurationProperties(prefix = "civictrack.dashboard")
public record DashboardProperties(
        @DefaultValue("Asia/Kolkata") String zone,
        /** Length of the reported-against-resolved series. Blueprint 3.8: 90 days. */
        @DefaultValue("90") int trendDays,
        /** Length of the SLA-compliance trend. Blueprint 3.8: 30 days. */
        @DefaultValue("30") int complianceDays,
        @DefaultValue("20") int breachingLimit,
        @DefaultValue("10") int topClusters,
        /**
         * Below this many resolved issues a median or a compliance rate is
         * withheld, and the tile says how many it needs (blueprint 3.8, "Empty").
         * A median of three issues is an anecdote with a decimal point.
         */
        @DefaultValue("5") int minimumSample
) {
}
