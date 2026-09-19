package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.structure.ExternalStructureLoader;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import com.typ.adaptivestartingstructure.structure.StructureLoadException;
import com.typ.adaptivestartingstructure.structure.StructurePool;
import com.typ.adaptivestartingstructure.structure.StructureSource;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class PlacementPreparation {
    static final int TREE_OBSERVATION_MARGIN = 12;

    private PlacementPreparation() {
    }

    public static PreparedPlacement prepare(
            ServerLevel level,
            StartingStructureSavedData data,
            ConfigSnapshot config)
            throws IOException, StructureLoadException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(config, "config");
        if (data.state() != StartingStructureSavedData.State.PLANNED) {
            throw new PlacementPreparationException(
                    "Placement preparation requires PLANNED saved data, found "
                            + data.state());
        }
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Placement preparation must run on the server thread");
        }

        StartingStructurePlan plan = data.plan();
        StructureSource source =
                StructurePool.resolveSelected(plan.candidate().structureId());
        StructureDefinition definition = new ExternalStructureLoader(
                level.registryAccess().lookupOrThrow(Registries.BLOCK))
                .loadDefinition(source);
        RotatedStructureView structure =
                validateAssetAndGeometry(plan, definition, config);
        TemplatePlacementPlanner.validateBlockEntities(
                plan,
                definition,
                structure);
        PlacementBounds bounds = PlacementBounds.calculate(
                structure,
                plan.candidate().placementOrigin(),
                config.blendWidth());
        validatePersistedBounds(plan, bounds);
        bounds.validateAffectedColumnLimit();
        PlacementBounds treeObservationBounds =
                bounds.expandedBy(TREE_OBSERVATION_MARGIN);
        List<ChunkPos> chunks =
                treeObservationBounds.requiredChunks();

        ChunkTicketLease lease = null;
        PreparedTemplateEntities entities = null;
        try {
            lease = ChunkTicketLease.acquire(level, chunks);
            GeneratedSiteValidation validation =
                    GeneratedSiteValidator.validate(
                            level,
                            plan,
                            structure,
                            bounds,
                            config);
            entities =
                    TemplateEntityPlacementPlanner.prepare(
                            level,
                            plan,
                            definition,
                            structure,
                            bounds,
                            chunks,
                            config);
            return new PreparedPlacement(
                    level,
                    data,
                    definition,
                    structure,
                    bounds,
                    treeObservationBounds,
                    chunks,
                    validation,
                    entities,
                    lease);
        } catch (RuntimeException | Error failure) {
            if (entities != null) {
                try {
                    entities.close();
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            if (lease != null) {
                try {
                    lease.close();
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            throw failure;
        }
    }

    static RotatedStructureView validateAssetAndGeometry(
            StartingStructurePlan plan,
            StructureDefinition definition,
            ConfigSnapshot config) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(config, "config");
        if (!plan.candidate().structureId().equals(
                        definition.source().id())
                || !plan.candidate().structureSha256().equals(
                        definition.placementSha256())) {
            throw new PlacementPreparationException(
                    "Selected structure identity or SHA-256 changed after planning");
        }
        if (!config.allowedRotations().contains(
                plan.candidate().rotation())) {
            throw new PlacementPreparationException(
                    "Persisted structure rotation is no longer allowed by config");
        }

        RotatedStructureView structure =
                definition.view(plan.candidate().rotation());
        BlockPos origin = plan.candidate().placementOrigin();
        long expectedOriginY =
                (long) plan.candidate().groundSurfaceY()
                        + 1L
                        - structure.markers().groundLevel().getY();
        if (expectedOriginY != origin.getY()
                || !origin.offset(structure.markers().spawn())
                        .equals(plan.candidate().worldSpawn())) {
            throw new PlacementPreparationException(
                    "Persisted placement origin or world spawn no longer matches the selected asset");
        }
        return structure;
    }

    static void validatePersistedBounds(
            StartingStructurePlan plan,
            PlacementBounds calculated) {
        var persisted = plan.candidate().terrainPlan();
        if (!calculated.structureBounds().equals(
                        persisted.structureBounds())
                || calculated.minimumBlendX()
                        != persisted.minimumBlendX()
                || calculated.maximumBlendX()
                        != persisted.maximumBlendX()
                || calculated.minimumBlendZ()
                        != persisted.minimumBlendZ()
                || calculated.maximumBlendZ()
                        != persisted.maximumBlendZ()) {
            throw new PlacementPreparationException(
                    "Persisted structure or blend bounds no longer match the selected asset and config");
        }
    }
}
