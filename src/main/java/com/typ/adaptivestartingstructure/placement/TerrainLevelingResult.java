package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;

public record TerrainLevelingResult(
        TerrainSnapshot snapshot,
        TerrainTransformationPlan plan,
        int appliedWrites) {

    public TerrainLevelingResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        plan = Objects.requireNonNull(plan, "plan");
        if (plan.snapshot() != snapshot
                || appliedWrites != plan.totalWrites()) {
            throw new IllegalArgumentException(
                    "Terrain leveling result does not match its plan");
        }
    }
}
