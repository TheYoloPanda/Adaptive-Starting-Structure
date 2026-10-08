package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.planner.RealSpawnValidator;
import com.typ.adaptivestartingstructure.planner.SpawnValidationResult;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * Checks the persisted spawn on the blocks the site will hold, before any of
 * them is written. Planning takes the ground under the footprint for stone,
 * so a spawn whose floor the template leaves to the ground can stand over a
 * cave, water or lava that only the generated chunks show; found here, the
 * site is skipped for the next one instead of blocking the world after its
 * writes.
 */
public final class PreWriteSpawnValidator {
    private PreWriteSpawnValidator() {
    }

    public static void validate(
            PreparedPlacement prepared,
            PreparedTerrainBlending terrain,
            ConfigSnapshot config) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(config, "config");
        ServerLevel level = prepared.level();
        var candidate = prepared.savedData().plan().candidate();
        validate(
                level::getBlockState,
                level,
                terrain,
                world -> TemplatePlacement.plan(
                        world,
                        prepared,
                        terrain.plan(),
                        config),
                candidate.worldSpawn(),
                candidate.validatedSpawnRadius(),
                prepared.bounds().structureBounds());
    }

    static void validate(
            TemplatePlacementPlanner.BlockStateReader world,
            LevelHeightAccessor heights,
            PreparedTerrainBlending terrain,
            Function<TemplatePlacementPlanner.BlockStateReader, TemplatePlacementPlan> template,
            BlockPos spawnFeet,
            int spawnRadius,
            StructureBounds structureBounds) {
        FinalBlockView blocks = new FinalBlockView(world, heights);
        blocks.writeTerrain(terrain.leveling().plan().writes());
        blocks.writeTerrain(terrain.plan().environmentWrites());
        blocks.writeTerrain(terrain.plan().treeCleanupPlanWrites());
        blocks.writeTemplate(
                template.apply(blocks::getBlockState).writes());
        SpawnValidationResult spawnValidation =
                RealSpawnValidator.validate(
                        spawnRadius,
                        spawnFeet,
                        structureBounds,
                        blocks);
        if (!spawnValidation.accepted()) {
            throw new UnsuitableGeneratedSiteException(
                    "Spawn validation on the final blocks failed before placement at "
                            + spawnValidation.spawnFeet()
                            + " with radius "
                            + spawnValidation.effectiveRadius()
                            + ": "
                            + spawnValidation.rejectionCounts()
                            + "; the spawn would be unsafe once the terrain and template are written");
        }
    }
}
