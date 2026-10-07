package com.civictrack.common.generation;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidValueGenerator;
import java.util.UUID;

/** Random UUIDs normally; stable UUIDs only inside the seed runner's scope. */
public class EntityUuidGenerator implements UuidValueGenerator {
    @Override public UUID generateUuid(SharedSessionContractImplementor session) {
        return GenerationScope.nextUuid();
    }
}
