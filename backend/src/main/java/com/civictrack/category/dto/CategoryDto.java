package com.civictrack.category.dto;

import com.civictrack.category.Category;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A category as the client needs it.
 *
 * <p>{@code mergeRadiusM} and {@code maxExtentMultiplier} are here because the
 * cluster inspector draws both circles from them -- the dashed merge radius and
 * the dotted extent cap at {@code multiplier x radius} (DD-001). Those numbers
 * live in the categories table under standing rule 1, so the screen that
 * visualises the clustering decision reads the same configuration the engine
 * decided with, rather than a constant the frontend hardcoded and nobody
 * updated when the radius was swept.
 *
 * <p>There is no icon. The categories table has no icon column and is not
 * getting one: standing rule 1 puts every tunable <em>number</em> in that
 * table, and an icon is a presentation asset. Changing it would need a
 * frontend redeploy whether or not it round-tripped through the database, so
 * the database round trip buys nothing and costs a migration. The client keeps
 * a map keyed by {@code code}.
 */
public record CategoryDto(
        String code,
        String displayName,
        int mergeRadiusM,
        BigDecimal maxExtentMultiplier,
        double extentCapM,
        int defaultSlaHours,
        UUID departmentId
) {
    public static CategoryDto from(Category c) {
        return new CategoryDto(
                c.getCode(), c.getDisplayName(),
                c.getMergeRadiusM(), c.getMaxExtentMultiplier(), c.extentCapM(),
                c.getDefaultSlaHours(), c.getDepartmentId());
    }
}
