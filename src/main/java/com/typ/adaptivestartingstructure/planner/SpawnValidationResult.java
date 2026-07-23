package com.typ.adaptivestartingstructure.planner;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

public final class SpawnValidationResult {
    private final BlockPos spawnFeet;
    private final int configuredRadius;
    private final int effectiveRadius;
    private final int checkedColumns;
    private final Map<SpawnRejectionReason, Integer> rejectionCounts;

    SpawnValidationResult(
            BlockPos spawnFeet,
            int configuredRadius,
            int effectiveRadius,
            int checkedColumns,
            Map<SpawnRejectionReason, Integer> rejectionCounts) {
        this.spawnFeet = Objects.requireNonNull(spawnFeet, "spawnFeet").immutable();
        this.configuredRadius = configuredRadius;
        this.effectiveRadius = effectiveRadius;
        if (effectiveRadius < 0) {
            throw new IllegalArgumentException("effectiveRadius must not be negative");
        }
        if (checkedColumns < 0) {
            throw new IllegalArgumentException("checkedColumns must not be negative");
        }
        EnumMap<SpawnRejectionReason, Integer> counts =
                new EnumMap<>(SpawnRejectionReason.class);
        for (Map.Entry<SpawnRejectionReason, Integer> entry :
                Objects.requireNonNull(rejectionCounts, "rejectionCounts").entrySet()) {
            SpawnRejectionReason reason =
                    Objects.requireNonNull(entry.getKey(), "rejection reason");
            Integer count = Objects.requireNonNull(entry.getValue(), "rejection count");
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Rejection counts must be positive");
            }
            counts.put(reason, count);
        }
        this.checkedColumns = checkedColumns;
        this.rejectionCounts = Collections.unmodifiableMap(counts);
    }

    public BlockPos spawnFeet() {
        return spawnFeet;
    }

    public int configuredRadius() {
        return configuredRadius;
    }

    public int effectiveRadius() {
        return effectiveRadius;
    }

    public int checkedColumns() {
        return checkedColumns;
    }

    public Map<SpawnRejectionReason, Integer> rejectionCounts() {
        return rejectionCounts;
    }

    public boolean accepted() {
        return rejectionCounts.isEmpty();
    }
}
