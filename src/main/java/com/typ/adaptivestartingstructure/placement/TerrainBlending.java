package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

public final class TerrainBlending {
    private TerrainBlending() {
    }

    public static TerrainBlendingResult blend(
            PreparedPlacement prepared,
            TerrainLevelingResult leveling,
            ConfigSnapshot config) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(leveling, "leveling");
        Objects.requireNonNull(config, "config");
        ServerLevel level = prepared.level();
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Terrain blending must run on the server thread");
        }
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLACING) {
            throw new PlacementPreparationException(
                    "Terrain blending requires PLACING saved data");
        }
        if (!leveling.snapshot().bounds().equals(
                prepared.treeObservationBounds())) {
            throw new PlacementPreparationException(
                    "Terrain leveling context does not match the prepared placement");
        }

        TerrainBlendPlan plan = TerrainBlendPlanner.plan(
                new PreparedTerrainLeveling(
                        leveling.snapshot(),
                        leveling.plan()),
                prepared.structure(),
                prepared.savedData()
                        .plan()
                        .candidate()
                        .placementOrigin(),
                config,
                SnowCoverPlanner.climateOf(level));
        int applied = applyInStages(level, plan);
        return new TerrainBlendingResult(
                leveling,
                plan,
                applied);
    }

    public static PreparedTerrainBlending prepare(
            PreparedPlacement prepared,
            PreparedTerrainLeveling leveling,
            ConfigSnapshot config) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(leveling, "leveling");
        Objects.requireNonNull(config, "config");
        ServerLevel level = prepared.level();
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Terrain blending preparation must run on the server thread");
        }
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLANNED) {
            throw new PlacementPreparationException(
                    "Terrain blending preparation requires PLANNED saved data");
        }
        if (!leveling.snapshot().bounds().equals(
                prepared.treeObservationBounds())) {
            throw new PlacementPreparationException(
                    "Prepared terrain leveling does not match the prepared placement");
        }

        TerrainBlendPlan plan = TerrainBlendPlanner.plan(
                leveling,
                prepared.structure(),
                prepared.savedData()
                        .plan()
                        .candidate()
                        .placementOrigin(),
                config,
                SnowCoverPlanner.climateOf(level));
        return new PreparedTerrainBlending(leveling, plan);
    }

    public static TerrainBlendingResult apply(
            PreparedPlacement prepared,
            TerrainLevelingResult leveling,
            PreparedTerrainBlending blending) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(leveling, "leveling");
        Objects.requireNonNull(blending, "blending");
        ServerLevel level = prepared.level();
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Terrain blending application must run on the server thread");
        }
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLACING) {
            throw new PlacementPreparationException(
                    "Terrain blending application requires PLACING saved data");
        }
        if (blending.leveling().snapshot() != leveling.snapshot()
                || blending.leveling().plan() != leveling.plan()) {
            throw new PlacementPreparationException(
                    "Prepared blend does not match the applied leveling plan");
        }

        int applied = applyInStages(
                level,
                blending.plan());
        return new TerrainBlendingResult(
                leveling,
                blending.plan(),
                applied);
    }

    private static int applyInStages(
            ServerLevel level,
            TerrainBlendPlan plan) {
        int environmentWrites = TerrainPlanApplier.apply(
                level,
                plan.environmentWrites());
        int treeWrites = TerrainPlanApplier.apply(
                level,
                plan.treeCleanupPlanWrites());
        return Math.addExact(environmentWrites, treeWrites);
    }
}
