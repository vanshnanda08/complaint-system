package com.civictrack.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "civictrack.seed")
public record SeedProperties(
    boolean enabled,
    int corpusSize,
    long randomSeed,
    String labelsFilePath,

        /**
         * Seed even when the database already holds issues.
         *
         * <p>Defaults to false. Seeding twice does not replace the first
         * corpus, it adds a second one that the clustering engine partially
         * merges into it -- which inflates report counts and invalidates the
         * ground-truth labels the evaluation depends on.
         */
        @DefaultValue("false") boolean force
) {}
