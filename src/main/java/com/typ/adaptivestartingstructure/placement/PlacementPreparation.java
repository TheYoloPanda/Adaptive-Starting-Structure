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
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;

public final class PlacementPreparation {
    static final int TREE_OBSERVATION_MARGIN = 12;

    private PlacementPreparation() {
    }

    /** The template a plan selected, read and validated from its file again. */
    public static StructureDefinition loadSelected(
            ServerLevel level,
            StartingStructurePlan plan)
            throws IOException, StructureLoadException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        StructureSource source =
                StructurePool.resolveSelected(plan.candidate().structureId());
        return new ExternalStructureLoader(
                level.registryAccess().lookupOrThrow(Registries.BLOCK))
                .loadDefinition(source);
    }

    public static PreparedPlacement prepare(
            ServerLevel level,
            StartingStructureSavedData data,
            ConfigSnapshot config,
            StructureDefinition definition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(definition, "definition");
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
        GeneratedStructureCollisionValidator.validateTemplateVolume(
                level,
                bounds.structureBounds(),
                config.ignoredStructureCollisions());

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

    /**
     * Refuses a template that placement would refuse wherever it landed.
     * Planning commits the world spawn, so a refusal left to placement fails
     * every world created with the template instead of leaving it vanilla.
     */
    public static void validateTemplate(
            StructureDefinition definition,
            ConfigSnapshot config,
            FeatureFlagSet enabledFeatures) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(enabledFeatures, "enabledFeatures");
        for (Rotation rotation : config.allowedRotations()) {
            long observedColumns = PlacementBounds.calculate(
                            definition.view(rotation),
                            BlockPos.ZERO,
                            config.blendWidth())
                    .expandedBy(TREE_OBSERVATION_MARGIN)
                    .affectedColumnCount();
            if (observedColumns > PlacementBounds.MAX_AFFECTED_COLUMNS) {
                throw new PlacementPreparationException(
                        "Structure '"
                                + definition.source().id()
                                + "' with blendWidth "
                                + config.blendWidth()
                                + " needs terrain observed over "
                                + observedColumns
                                + " columns, including the "
                                + TREE_OBSERVATION_MARGIN
                                + "-block tree observation margin; the operational maximum is "
                                + PlacementBounds.MAX_AFFECTED_COLUMNS);
            }
        }
        TemplatePlacementPlanner.validateBlockEntityRules(definition);
        if (config.placeTemplateEntities()) {
            TemplateEntityPlacementPlanner.validateTemplate(
                    definition,
                    config.allowedRotations(),
                    enabledFeatures);
        }
    }

    /**
     * Refuses a template whose block-attached entities would fail wherever it
     * landed: two of them conflicting, or one its own blocks do not hold.
     * Unlike the rules {@link #validateTemplate} checks, these need the
     * entities themselves, and creating them needs the level.
     */
    public static void validateTemplateAttachments(
            StructureDefinition definition,
            ConfigSnapshot config,
            ServerLevel level) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(level, "level");
        if (!config.placeTemplateEntities()) {
            return;
        }
        TemplateEntityPlacementPlanner.validateAttachments(
                definition,
                config.allowedRotations(),
                level.enabledFeatures(),
                type -> type.create(level));
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
