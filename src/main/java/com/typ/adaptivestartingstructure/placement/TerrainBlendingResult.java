package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;

public record TerrainBlendingResult(
        TerrainLevelingResult leveling,
        TerrainBlendPlan plan,
        int appliedWrites) {

    public TerrainBlendingResult {
        leveling = Objects.requireNonNull(leveling, "leveling");
        plan = Objects.requireNonNull(plan, "plan");
        if (plan.snapshot() != leveling.snapshot()
                || appliedWrites != plan.totalWrites()) {
            throw new IllegalArgumentException(
                    "Terrain blending result does not match its context");
        }
    }
}
