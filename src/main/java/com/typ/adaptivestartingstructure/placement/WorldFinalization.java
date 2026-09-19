package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.planner.RealSpawnValidator;
import com.typ.adaptivestartingstructure.planner.SpawnValidationResult;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class WorldFinalization {
    private WorldFinalization() {
    }

    public static WorldFinalizationResult finish(
            PreparedPlacement prepared,
            TemplatePlacementResult placement) {
        return finishPersistedPlan(prepared, placement);
    }

    public static WorldFinalizationResult finish(
            PreparedPlacement prepared,
            TemplatePlacementResult placement,
            ConfigSnapshot config) {
        Objects.requireNonNull(config, "config");
        // Reloadable config must not change the spawn-safety contract that
        // was already validated and persisted during world planning.
        return finishPersistedPlan(prepared, placement);
    }

    private static WorldFinalizationResult finishPersistedPlan(
            PreparedPlacement prepared,
            TemplatePlacementResult placement) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(placement, "placement");
        // Finalization consumes the prepared placement so its temporary
        // chunk tickets are released on both success and failure.
        try (prepared) {
            return finishPrepared(
                    prepared,
                    placement);
        }
    }

    private static WorldFinalizationResult finishPrepared(
            PreparedPlacement prepared,
            TemplatePlacementResult placement) {
        ServerLevel level = prepared.level();
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "World finalization must run on the server thread");
        }
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLACING) {
            throw new PlacementPreparationException(
                    "World finalization requires PLACING saved data");
        }
        validateContext(prepared, placement, level);

        WorldUpdatePlan updatePlan =
                WorldUpdatePlan.create(prepared, placement);
        AppliedWorldUpdates updates =
                WorldUpdateApplier.apply(level, updatePlan);
        HeightmapVerification heightmaps =
                HeightmapConsistencyValidator.verify(
                        level,
                        updatePlan.modifiedColumns());

        var candidate =
                prepared.savedData().plan().candidate();
        SpawnValidationResult spawnValidation =
                RealSpawnValidator.validate(
                        level,
                        candidate.worldSpawn(),
                        prepared.bounds().structureBounds(),
                        candidate.validatedSpawnRadius());
        if (!spawnValidation.accepted()) {
            throw new PlacementPreparationException(
                    "Real spawn validation failed after placement at "
                            + spawnValidation.spawnFeet()
                            + " with radius "
                            + spawnValidation.effectiveRadius()
                            + ": "
                            + spawnValidation.rejectionCounts());
        }
        if (spawnValidation.checkedColumns()
                != candidate.validatedSpawnColumns()) {
            throw new PlacementPreparationException(
                    "Real spawn validation scope no longer matches the persisted plan");
        }

        PreparedTemplateEntities preparedEntities =
                prepared.entities();
        TemplateEntityPlacementApplier
                .validateAttachments(
                        preparedEntities);
        TemplateEntityPlacementMetrics entityMetrics =
                TemplateEntityPlacementApplier.addAll(
                        level,
                        preparedEntities);

        TerrainLevelingResult leveling =
                placement.terrain().leveling();
        TerrainBlendingResult blending =
                placement.terrain();
        WorldFinalizationMetrics metrics =
                new WorldFinalizationMetrics(
                        updatePlan.preparedChunks().size(),
                        updatePlan.modifiedChunks().size(),
                        updatePlan.modifiedColumns().size(),
                        leveling.appliedWrites(),
                        blending.appliedWrites(),
                        placement.appliedWrites(),
                        updates.shapeCorrectionWrites(),
                        placement.plan()
                                .blockEntities()
                                .size(),
                        heightmaps.checkedColumns(),
                        heightmaps.checkedValues(),
                        updates.lightChecks(),
                        updates.neighborUpdates(),
                        updates.comparatorUpdates(),
                        updates.fluidTicks(),
                        spawnValidation.checkedColumns(),
                        entityMetrics.sourceEntities(),
                        entityMetrics.plannedEntities(),
                        entityMetrics.skippedByDisabledOption(),
                        entityMetrics.skippedUnsupportedEntities(),
                        entityMetrics.materializedEntities(),
                        entityMetrics.addedEntities());
        return new WorldFinalizationResult(
                placement,
                spawnValidation,
                metrics);
    }

    private static void validateContext(
            PreparedPlacement prepared,
            TemplatePlacementResult placement,
            ServerLevel level) {
        if (!placement.terrain()
                .leveling()
                .snapshot()
                .bounds()
                .equals(prepared.treeObservationBounds())) {
            throw new PlacementPreparationException(
                    "Finalization terrain context does not match prepared placement");
        }

        var candidate =
                prepared.savedData().plan().candidate();
        if (!candidate.structureBounds().equals(
                        prepared.bounds().structureBounds())) {
            throw new PlacementPreparationException(
                    "Final structure bounds no longer match the persisted plan");
        }
        BlockPos markerSpawn = candidate.placementOrigin()
                .offset(prepared.structure().markers().spawn());
        if (!markerSpawn.equals(candidate.worldSpawn())) {
            throw new PlacementPreparationException(
                    "Final spawn marker no longer matches the persisted plan");
        }
        if (!level.getSharedSpawnPos().equals(
                candidate.worldSpawn())) {
            throw new PlacementPreparationException(
                    "Real world spawn "
                            + level.getSharedSpawnPos()
                            + " no longer matches persisted spawn "
                            + candidate.worldSpawn());
        }
    }
}
