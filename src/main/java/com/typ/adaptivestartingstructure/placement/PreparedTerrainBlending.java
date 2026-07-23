package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;

public record PreparedTerrainBlending(
        PreparedTerrainLeveling leveling,
        TerrainBlendPlan plan) {

    public PreparedTerrainBlending {
        leveling = Objects.requireNonNull(leveling, "leveling");
        plan = Objects.requireNonNull(plan, "plan");
        if (plan.snapshot() != leveling.snapshot()) {
            throw new IllegalArgumentException(
                    "Prepared blend plan must use the leveling snapshot");
        }
    }
}
