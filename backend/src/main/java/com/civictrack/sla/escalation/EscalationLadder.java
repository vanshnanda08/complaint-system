package com.civictrack.sla.escalation;

import com.civictrack.issue.Issue;
import com.civictrack.sla.SlaProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The ordered ladder, assembled from the resolver beans.
 *
 * <p>Assembling it from the beans rather than hard-coding the order means the
 * ladder cannot silently acquire a gap: the constructor refuses to start if any
 * rung from 1 to {@code maxEscalationLevel} has no resolver, or if two
 * resolvers claim the same rung. Both are the kind of mistake that would
 * otherwise surface as an issue that escalates into nothing.
 */
@Component
public class EscalationLadder {

    private final Map<Integer, EscalationResolver> byLevel;
    private final int maxLevel;

    public EscalationLadder(List<EscalationResolver> resolvers, SlaProperties props) {
        this.maxLevel = props.maxEscalationLevel();
        this.byLevel = resolvers.stream().collect(Collectors.toMap(
                EscalationResolver::level, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Two escalation resolvers claim level " + a.level() + ": "
                            + a.getClass().getSimpleName() + " and " + b.getClass().getSimpleName());
                }));

        for (int level = 1; level <= maxLevel; level++) {
            if (!byLevel.containsKey(level)) {
                throw new IllegalStateException(
                        "The escalation ladder has no resolver for level " + level
                        + "; an issue reaching it would escalate to nobody");
            }
        }
    }

    /** The rung above {@code currentLevel}, or empty when the ladder is exhausted. */
    public Optional<Integer> nextLevel(int currentLevel) {
        return currentLevel >= maxLevel ? Optional.empty() : Optional.of(currentLevel + 1);
    }

    public boolean isTerminal(int level) {
        return level >= maxLevel;
    }

    public EscalationResolver resolverFor(int level) {
        EscalationResolver resolver = byLevel.get(level);
        if (resolver == null) {
            throw new IllegalArgumentException("No escalation resolver for level " + level);
        }
        return resolver;
    }

    /** Who owns this issue at {@code level}, if that rung is staffed. */
    public Optional<UUID> ownerAt(Issue issue, int level) {
        return resolverFor(level).resolve(issue);
    }

    public List<EscalationResolver> rungs() {
        return byLevel.values().stream()
                .sorted(Comparator.comparingInt(EscalationResolver::level)).toList();
    }
}
