package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;

public record PreparedTerrainLeveling(
        TerrainSnapshot snapshot,
        TerrainTransformationPlan plan) {

    public PreparedTerrainLeveling {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        plan = Objects.requireNonNull(plan, "plan");
        if (plan.snapshot() != snapshot) {
            throw new IllegalArgumentException(
                    "Prepared terrain plan must use its captured snapshot");
        }
    }
}
