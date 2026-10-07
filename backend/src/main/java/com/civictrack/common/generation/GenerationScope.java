package com.civictrack.common.generation;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;

/** Thread-confined, explicitly opened only by a reproducible seed run. */
public final class GenerationScope implements AutoCloseable {
    private static final ThreadLocal<GenerationScope> CURRENT = new ThreadLocal<>();
    private final String namespace;
    private final Instant referenceTime;
    private long idSequence;
    private long ticketSequence;

    private GenerationScope(long seed, Instant referenceTime) {
        this.namespace = "civictrack-seed-v1:" + seed + ":" + referenceTime;
        this.referenceTime = referenceTime;
    }

    public static GenerationScope open(long seed, Instant referenceTime) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested seed scope");
        GenerationScope scope = new GenerationScope(seed, referenceTime);
        CURRENT.set(scope);
        return scope;
    }

    public static UUID nextUuid() {
        GenerationScope scope = CURRENT.get();
        return scope == null ? UUID.randomUUID() : UUID.nameUUIDFromBytes(
                (scope.namespace + ":" + ++scope.idSequence).getBytes(StandardCharsets.UTF_8));
    }

    public static String publicRef(Supplier<String> normal) {
        GenerationScope scope = CURRENT.get();
        // 'S' keeps seed tickets distinct from the database's ordinary sequence.
        return scope == null ? normal.get() : "CT-" + scope.referenceTime.atZone(ZoneId.of("UTC")).getYear()
                + "-S" + String.format(java.util.Locale.ROOT, "%06d", ++scope.ticketSequence);
    }

    public static Clock clock(Clock normal) {
        return new Clock() {
            @Override public ZoneId getZone() { return normal.getZone(); }
            @Override public Clock withZone(ZoneId zone) { return clock(normal.withZone(zone)); }
            @Override public Instant instant() {
                GenerationScope scope = CURRENT.get();
                return scope == null ? normal.instant() : scope.referenceTime;
            }
        };
    }

    @Override public void close() { CURRENT.remove(); }
}
