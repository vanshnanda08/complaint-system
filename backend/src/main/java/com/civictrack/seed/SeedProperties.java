package com.civictrack.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "civictrack.seed")
public record SeedProperties(
    boolean enabled,
    int corpusSize,
    long randomSeed,
    String labelsFilePath
) {}
