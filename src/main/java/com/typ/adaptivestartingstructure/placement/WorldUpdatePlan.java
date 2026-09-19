package com.typ.adaptivestartingstructure.placement;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

final class WorldUpdatePlan {
    private final PlacementBounds interventionBounds;
    private final PlacementBounds bounds;
    private final Set<ChunkPos> preparedChunks;
    private final Set<ChunkPos> modifiedChunks;
    private final Set<Long> modifiedColumns;
    private final Set<BlockPos> blockUpdatePositions;
    private final Set<BlockPos> blockEntityPositions;
    private final Set<BlockPos> fluidSeedPositions;

    private WorldUpdatePlan(
            PlacementBounds interventionBounds,
            PlacementBounds bounds,
            List<ChunkPos> preparedChunks,
            Iterable<BlockPos> levelingWrites,
            Iterable<BlockPos> environmentBlendingWrites,
            Iterable<BlockPos> treeCleanupWrites,
            Iterable<BlockPos> templateWrites,
            Iterable<BlockPos> finalTemplateUpdates,
            Iterable<BlockPos> blockEntityPositions,
            Iterable<BlockPos> blendFluidSeeds,
            Iterable<BlockPos> templateFluidSeeds) {
        this.interventionBounds = Objects.requireNonNull(
                interventionBounds,
                "interventionBounds");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        if (!this.bounds.contains(this.interventionBounds)) {
            throw new IllegalArgumentException(
                    "Prepared bounds must contain intervention bounds");
        }
        this.preparedChunks = immutableChunks(preparedChunks);
        if (this.preparedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Finalization requires prepared chunks");
        }

        LinkedHashSet<BlockPos> blockWrites =
                new LinkedHashSet<>();
        addPositionsWithin(
                blockWrites,
                levelingWrites,
                this.interventionBounds);
        addPositionsWithin(
                blockWrites,
                environmentBlendingWrites,
                this.interventionBounds);
        addPositionsWithin(
                blockWrites,
                treeCleanupWrites,
                this.bounds);
        addPositionsWithin(
                blockWrites,
                templateWrites,
                this.interventionBounds);
        LinkedHashSet<BlockPos> updates =
                new LinkedHashSet<>(blockWrites);
        addPositionsWithin(
                updates,
                finalTemplateUpdates,
                this.interventionBounds);
        this.blockUpdatePositions =
                immutablePositions(updates);

        LinkedHashSet<BlockPos> entities =
                new LinkedHashSet<>();
        addPositionsWithin(
                entities,
                blockEntityPositions,
                this.interventionBounds);
        if (!this.blockUpdatePositions.containsAll(entities)) {
            throw new IllegalArgumentException(
                    "BlockEntity updates must be part of final block updates");
        }
        this.blockEntityPositions =
                immutablePositions(entities);

        LinkedHashSet<BlockPos> fluidSeeds =
                new LinkedHashSet<>();
        addPositionsWithin(
                fluidSeeds,
                blendFluidSeeds,
                this.bounds);
        addPositionsWithin(
                fluidSeeds,
                templateFluidSeeds,
                this.interventionBounds);
        this.fluidSeedPositions =
                immutablePositions(fluidSeeds);

        LinkedHashSet<ChunkPos> changedChunks =
                new LinkedHashSet<>();
        LinkedHashSet<Long> changedColumns =
                new LinkedHashSet<>();
        for (BlockPos position : this.blockUpdatePositions) {
            changedChunks.add(new ChunkPos(position));
            changedColumns.add(TerrainSnapshot.pack(
                    position.getX(),
                    position.getZ()));
        }
        this.modifiedChunks =
                Collections.unmodifiableSet(changedChunks);
        this.modifiedColumns =
                Collections.unmodifiableSet(changedColumns);
    }

    static WorldUpdatePlan create(
            PreparedPlacement prepared,
            TemplatePlacementResult placement) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(placement, "placement");
        TerrainTransformationPlan leveling =
                placement.terrain().leveling().plan();
        TerrainBlendPlan blending =
                placement.terrain().plan();
        TemplatePlacementPlan template =
                placement.plan();
        return create(
                prepared.bounds(),
                prepared.treeObservationBounds(),
                prepared.chunks(),
                leveling.writes().stream()
                        .map(TerrainWrite::position)
                        .toList(),
                blending.environmentWrites().stream()
                        .map(TerrainWrite::position)
                        .toList(),
                blending.treeCleanupPlanWrites().stream()
                        .map(TerrainWrite::position)
                        .toList(),
                template.writes().stream()
                        .map(TemplateBlockWrite::position)
                        .toList(),
                template.finalUpdatePositions(),
                template.blockEntities().stream()
                        .map(TemplateBlockEntityData::position)
                        .toList(),
                blending.fluidUpdatePositions(),
                template.fluidUpdatePositions());
    }

    static WorldUpdatePlan create(
            PlacementBounds bounds,
            List<ChunkPos> preparedChunks,
            Iterable<BlockPos> levelingWrites,
            Iterable<BlockPos> blendingWrites,
            Iterable<BlockPos> templateWrites,
            Iterable<BlockPos> finalTemplateUpdates,
            Iterable<BlockPos> blockEntityPositions,
            Iterable<BlockPos> blendFluidSeeds,
            Iterable<BlockPos> templateFluidSeeds) {
        return new WorldUpdatePlan(
                bounds,
                bounds,
                preparedChunks,
                levelingWrites,
                blendingWrites,
                List.of(),
                templateWrites,
                finalTemplateUpdates,
                blockEntityPositions,
                blendFluidSeeds,
                templateFluidSeeds);
    }

    static WorldUpdatePlan create(
            PlacementBounds interventionBounds,
            PlacementBounds preparedBounds,
            List<ChunkPos> preparedChunks,
            Iterable<BlockPos> levelingWrites,
            Iterable<BlockPos> environmentBlendingWrites,
            Iterable<BlockPos> treeCleanupWrites,
            Iterable<BlockPos> templateWrites,
            Iterable<BlockPos> finalTemplateUpdates,
            Iterable<BlockPos> blockEntityPositions,
            Iterable<BlockPos> blendFluidSeeds,
            Iterable<BlockPos> templateFluidSeeds) {
        return new WorldUpdatePlan(
                interventionBounds,
                preparedBounds,
                preparedChunks,
                levelingWrites,
                environmentBlendingWrites,
                treeCleanupWrites,
                templateWrites,
                finalTemplateUpdates,
                blockEntityPositions,
                blendFluidSeeds,
                templateFluidSeeds);
    }

    PlacementBounds bounds() {
        return bounds;
    }

    Set<ChunkPos> preparedChunks() {
        return preparedChunks;
    }

    Set<ChunkPos> modifiedChunks() {
        return modifiedChunks;
    }

    Set<Long> modifiedColumns() {
        return modifiedColumns;
    }

    Set<BlockPos> blockUpdatePositions() {
        return blockUpdatePositions;
    }

    Set<BlockPos> blockEntityPositions() {
        return blockEntityPositions;
    }

    Set<BlockPos> fluidSeedPositions() {
        return fluidSeedPositions;
    }

    private void addPositionsWithin(
            Set<BlockPos> target,
            Iterable<BlockPos> positions,
            PlacementBounds allowedBounds) {
        Objects.requireNonNull(positions, "positions");
        for (BlockPos supplied : positions) {
            BlockPos position = Objects.requireNonNull(
                            supplied,
                            "position")
                    .immutable();
            validatePosition(position, allowedBounds);
            target.add(position);
        }
    }

    private void validatePosition(
            BlockPos position,
            PlacementBounds allowedBounds) {
        if (!allowedBounds.containsHorizontal(
                        position.getX(),
                        position.getZ())
                || !preparedChunks.contains(
                        new ChunkPos(position))) {
            throw new PlacementPreparationException(
                    "Final world update lies outside prepared placement at "
                            + position);
        }
    }

    private static Set<ChunkPos> immutableChunks(
            List<ChunkPos> chunks) {
        Objects.requireNonNull(chunks, "preparedChunks");
        LinkedHashSet<ChunkPos> copy =
                new LinkedHashSet<>();
        for (ChunkPos chunk : chunks) {
            if (!copy.add(Objects.requireNonNull(
                    chunk,
                    "preparedChunk"))) {
                throw new IllegalArgumentException(
                        "Prepared chunks must be distinct");
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Set<BlockPos> immutablePositions(
            Iterable<BlockPos> positions) {
        LinkedHashSet<BlockPos> copy =
                new LinkedHashSet<>();
        addPositions(copy, positions);
        return Collections.unmodifiableSet(copy);
    }

    private static void addPositions(
            Set<BlockPos> target,
            Iterable<BlockPos> positions) {
        Objects.requireNonNull(positions, "positions");
        for (BlockPos position : positions) {
            target.add(Objects.requireNonNull(
                            position,
                            "position")
                    .immutable());
        }
    }
}
