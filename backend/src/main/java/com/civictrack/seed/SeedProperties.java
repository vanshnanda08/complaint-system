package com.civictrack.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "civictrack.seed")
public record SeedProperties(
    boolean enabled,
    int corpusSize,
    long randomSeed,
    String labelsFilePath,
    @DefaultValue("2026-10-01T00:00:00Z") java.time.Instant referenceTime,

    /** Legacy option: now fails explicitly on a populated database instead of appending. */
    @DefaultValue("false") boolean force
) {}
