package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

public record SiteCandidate(
        String structureId,
        String structureSha256,
        int centerX,
        int centerZ,
        Rotation rotation,
        FineCandidatePlan terrainPlan,
        BlockPos worldSpawn,
        FineCandidateMetrics metrics,
        double fitCost,
        long distanceSquared,
        int configuredSpawnRadius,
        int effectiveSpawnRadius,
        int validatedSpawnColumns) {

    public SiteCandidate {
        if (structureId == null || structureId.isBlank()) {
            throw new IllegalArgumentException("structureId must not be blank");
        }
        if (structureSha256 == null || structureSha256.isBlank()) {
            throw new IllegalArgumentException("structureSha256 must not be blank");
        }
        rotation = Objects.requireNonNull(rotation, "rotation");
        terrainPlan = Objects.requireNonNull(terrainPlan, "terrainPlan");
        worldSpawn = Objects.requireNonNull(worldSpawn, "worldSpawn").immutable();
        metrics = Objects.requireNonNull(metrics, "metrics");
        if (!Double.isFinite(fitCost)
                || Double.compare(fitCost, metrics.fitCost()) != 0) {
            throw new IllegalArgumentException(
                    "fitCost must equal the supplied fine metrics");
        }
        if (distanceSquared < 0L) {
            throw new IllegalArgumentException(
                    "distanceSquared must not be negative");
        }
        if (effectiveSpawnRadius != Math.max(0, configuredSpawnRadius)) {
            throw new IllegalArgumentException(
                    "effectiveSpawnRadius must match vanilla radius normalization");
        }
        if (validatedSpawnColumns <= 0) {
            throw new IllegalArgumentException(
                    "validatedSpawnColumns must be positive");
        }
        if (validatedSpawnColumns != 1) {
            long diameter = 2L * effectiveSpawnRadius + 1L;
            if (diameter > Integer.MAX_VALUE / diameter
                    || validatedSpawnColumns
                            != diameter * diameter) {
                throw new IllegalArgumentException(
                        "validatedSpawnColumns must describe either the exact "
                                + "spawn marker or the full effective-radius square");
            }
        }
        if (!terrainPlan.structureBounds().contains(worldSpawn)) {
            throw new IllegalArgumentException(
                    "worldSpawn must be inside the structure bounds");
        }
    }

    public int validatedSpawnRadius() {
        return validatedSpawnColumns == 1
                ? 0
                : effectiveSpawnRadius;
    }

    public BlockPos placementOrigin() {
        return terrainPlan.placementOrigin();
    }

    public int groundSurfaceY() {
        return terrainPlan.groundSurfaceY();
    }

    public StructureBounds structureBounds() {
        return terrainPlan.structureBounds();
    }
}
