package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.planner.SpawnValidationResult;
import java.util.Objects;

public record WorldFinalizationResult(
        TemplatePlacementResult placement,
        SpawnValidationResult spawnValidation,
        WorldFinalizationMetrics metrics) {

    public WorldFinalizationResult {
        placement = Objects.requireNonNull(
                placement,
                "placement");
        spawnValidation = Objects.requireNonNull(
                spawnValidation,
                "spawnValidation");
        metrics = Objects.requireNonNull(metrics, "metrics");
        if (!spawnValidation.accepted()
                || spawnValidation.checkedColumns()
                        != metrics.spawnColumnsChecked()) {
            throw new IllegalArgumentException(
                    "Finalization result requires an accepted real spawn validation");
        }
    }
}
