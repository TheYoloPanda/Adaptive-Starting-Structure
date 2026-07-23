package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;

public record TemplatePlacementResult(
        TerrainBlendingResult terrain,
        TemplatePlacementPlan plan,
        int appliedWrites) {

    public TemplatePlacementResult {
        terrain = Objects.requireNonNull(terrain, "terrain");
        plan = Objects.requireNonNull(plan, "plan");
        if (appliedWrites != plan.totalWrites()) {
            throw new IllegalArgumentException(
                    "Template placement result does not match its plan");
        }
    }
}
