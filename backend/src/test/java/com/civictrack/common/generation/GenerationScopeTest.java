package com.civictrack.common.generation;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationScopeTest {
    private final Instant normal = Instant.parse("2027-01-01T00:00:00Z");
    private final Instant seed = Instant.parse("2026-10-01T00:00:00Z");
    private final Clock clock = GenerationScope.clock(Clock.fixed(normal, ZoneOffset.UTC));

    @Test
    void seedScopeDoesNotAffectOtherThreadsOrSubsequentRequests() throws Exception {
        try (GenerationScope ignored = GenerationScope.open(42, seed)) {
            assertThat(clock.instant()).isEqualTo(seed);
            assertThat(GenerationScope.nextUuid().version()).isEqualTo(3);
            assertThat(CompletableFuture.supplyAsync(clock::instant).get()).isEqualTo(normal);
            assertThat(CompletableFuture.supplyAsync(GenerationScope::nextUuid).get().version()).isEqualTo(4);
        }
        assertThat(clock.instant()).isEqualTo(normal);
        assertThat(GenerationScope.nextUuid().version()).isEqualTo(4);
        assertThat(GenerationScope.publicRef(() -> "ordinary-ticket")).isEqualTo("ordinary-ticket");
    }

    @Test
    void failedSeedRunRestoresNormalClockAndIdentityGeneration() {
        assertThatThrownBy(() -> {
            try (GenerationScope ignored = GenerationScope.open(42, seed)) {
                throw new IllegalStateException("simulated ingest failure");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(clock.instant()).isEqualTo(normal);
        assertThat(GenerationScope.nextUuid().version()).isEqualTo(4);
    }
}
