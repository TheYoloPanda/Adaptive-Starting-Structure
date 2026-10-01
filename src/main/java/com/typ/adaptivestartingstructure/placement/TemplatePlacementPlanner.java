package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import com.typ.adaptivestartingstructure.structure.StructureElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

final class TemplatePlacementPlanner {
    private static final Comparator<ChunkPos> CHUNK_ORDER =
            Comparator.comparingInt((ChunkPos chunk) -> chunk.x)
                    .thenComparingInt(chunk -> chunk.z);
    private static final Comparator<TemplateBlockWrite> WRITE_ORDER =
            Comparator.comparingInt(
                            (TemplateBlockWrite write) ->
                                    write.position().getX())
                    .thenComparingInt(write ->
                            write.position().getZ())
                    .thenComparingInt(write ->
                            write.position().getY())
                    .thenComparingInt(write ->
                            write.kind().ordinal());
    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt(
                            (BlockPos position) ->
                                    position.getX())
                    .thenComparingInt(BlockPos::getZ)
                    .thenComparingInt(BlockPos::getY);

    private TemplatePlacementPlanner() {
    }

    static void validateBlockEntities(
            StartingStructurePlan savedPlan,
            StructureDefinition definition,
            RotatedStructureView structure) {
        int paletteIndex = TemplatePlacementSeeds.paletteIndex(
                savedPlan,
                definition.paletteCount());
        BlockPos origin =
                savedPlan.candidate().placementOrigin();
        for (StructureElement element
                : structure.placementElements()) {
            CompoundTag nbt = element.blockEntityNbt()
                    .orElse(null);
            if (nbt == null) {
                continue;
            }
            BlockPos position = offsetExact(
                    origin,
                    element.position());
            if (element.kind()
                    != StructureElement.Kind.BLOCK) {
                throw new PlacementPreparationException(
                        "Explicit air contains BlockEntity NBT at "
                                + position);
            }
            prepareBlockEntity(
                    savedPlan,
                    position,
                    element.stateForPalette(paletteIndex),
                    paletteIndex,
                    nbt);
        }
    }

    static TemplatePlacementPlan plan(
            BlockStateReader world,
            StartingStructurePlan savedPlan,
            StructureDefinition definition,
            RotatedStructureView structure,
            PlacementBounds bounds,
            TemplateSiteAdaptation.Site site) {
        validateBlockEntities(
                savedPlan,
                definition,
                structure);
        int paletteIndex = TemplatePlacementSeeds.paletteIndex(
                savedPlan,
                definition.paletteCount());
        BlockPos origin =
                savedPlan.candidate().placementOrigin();
        Map<BlockPos, TemplateBlockWrite> writes =
                new LinkedHashMap<>();
        List<TemplateBlockEntityData> blockEntities =
                new ArrayList<>(definition.blockEntityCount());
        Set<BlockPos> finalUpdates = new LinkedHashSet<>();
        Set<BlockPos> fluidUpdates = new LinkedHashSet<>();

        for (StructureElement element
                : structure.placementElements()) {
            BlockPos position = offsetExact(
                    origin,
                    element.position());
            BlockState target =
                    element.kind()
                                    == StructureElement.Kind.EXPLICIT_AIR
                            ? Blocks.AIR.defaultBlockState()
                            : element.stateForPalette(paletteIndex);
            BlockState original = world.blockState(position);
            TemplateBlockWrite.Kind kind =
                    element.kind()
                                    == StructureElement.Kind.EXPLICIT_AIR
                            ? TemplateBlockWrite.Kind.EXPLICIT_AIR
                            : TemplateBlockWrite.Kind.BLOCK;
            addWrite(
                    writes,
                    position,
                    original,
                    target,
                    kind);
            if (!original.equals(target)
                    && (!original.getFluidState().isEmpty()
                            || !target.getFluidState().isEmpty())) {
                fluidUpdates.add(position);
                finalUpdates.add(position);
            }

            element.blockEntityNbt().ifPresent(nbt -> {
                if (element.kind()
                        != StructureElement.Kind.BLOCK) {
                    throw new PlacementPreparationException(
                            "Explicit air contains BlockEntity NBT at "
                                    + position);
                }
                blockEntities.add(prepareBlockEntity(
                        savedPlan,
                        position,
                        target,
                        paletteIndex,
                        nbt));
                finalUpdates.add(position);
            });
        }

        clearMarker(
                world,
                writes,
                finalUpdates,
                fluidUpdates,
                offsetExact(origin, structure.markers().spawn()));
        clearMarker(
                world,
                writes,
                finalUpdates,
                fluidUpdates,
                offsetExact(
                        origin,
                        structure.markers().groundLevel()));
        TemplateSiteAdaptation.Summary siteAdaptation =
                TemplateSiteAdaptation.adapt(
                        writes,
                        world,
                        structure,
                        origin,
                        bounds.structureBounds(),
                        site);
        for (TemplateBlockWrite write : writes.values()) {
            finalUpdates.add(write.position());
        }

        Map<ChunkPos, List<TemplateBlockWrite>> grouped =
                new TreeMap<>(CHUNK_ORDER);
        for (TemplateBlockWrite write : writes.values()) {
            grouped.computeIfAbsent(
                            new ChunkPos(write.position()),
                            ignored -> new ArrayList<>())
                    .add(write);
        }
        Map<ChunkPos, List<TemplateBlockWrite>> ordered =
                new LinkedHashMap<>();
        for (Map.Entry<ChunkPos, List<TemplateBlockWrite>> entry
                : grouped.entrySet()) {
            entry.getValue().sort(WRITE_ORDER);
            ordered.put(entry.getKey(), entry.getValue());
        }
        Set<BlockPos> orderedUpdates =
                orderedPositions(finalUpdates);
        Set<BlockPos> orderedFluidUpdates =
                orderedPositions(fluidUpdates);
        return new TemplatePlacementPlan(
                paletteIndex,
                definition.paletteCount(),
                definition.blockCount(),
                definition.explicitAirCount(),
                ordered,
                blockEntities,
                orderedUpdates,
                orderedFluidUpdates,
                bounds,
                siteAdaptation);
    }

    private static TemplateBlockEntityData prepareBlockEntity(
            StartingStructurePlan plan,
            BlockPos position,
            BlockState state,
            int paletteIndex,
            CompoundTag sourceNbt) {
        if (!state.hasBlockEntity()
                || !sourceNbt.contains(
                        "id",
                        CompoundTag.TAG_STRING)) {
            throw new PlacementPreparationException(
                    "Template BlockEntity NBT does not match block state at "
                            + position);
        }
        ResourceLocation typeId = ResourceLocation.tryParse(
                sourceNbt.getString("id"));
        BlockEntityType<?> type = typeId == null
                ? null
                : BuiltInRegistries.BLOCK_ENTITY_TYPE
                        .getOptional(typeId)
                        .orElse(null);
        if (type == null || !type.isValid(state)) {
            throw new PlacementPreparationException(
                    "Template BlockEntity type is invalid for "
                            + state + " at " + position);
        }

        CompoundTag nbt = sourceNbt.copy();
        Long lootSeed = null;
        if (nbt.contains(
                "LootTable",
                CompoundTag.TAG_STRING)) {
            if (ResourceLocation.tryParse(
                    nbt.getString("LootTable")) == null) {
                throw new PlacementPreparationException(
                        "Template BlockEntity has an invalid loot table at "
                                + position);
            }
            lootSeed = TemplatePlacementSeeds.lootSeed(
                    plan,
                    position,
                    paletteIndex);
            nbt.putLong("LootTableSeed", lootSeed);
        }
        return new TemplateBlockEntityData(
                position,
                state,
                typeId,
                nbt,
                lootSeed);
    }

    private static void clearMarker(
            BlockStateReader world,
            Map<BlockPos, TemplateBlockWrite> writes,
            Set<BlockPos> finalUpdates,
            Set<BlockPos> fluidUpdates,
            BlockPos position) {
        BlockState original = world.blockState(position);
        addWrite(
                writes,
                position,
                original,
                Blocks.AIR.defaultBlockState(),
                TemplateBlockWrite.Kind.MARKER_CLEAR);
        if (!original.isAir()
                && !original.getFluidState().isEmpty()) {
            fluidUpdates.add(position);
            finalUpdates.add(position);
        }
    }

    private static void addWrite(
            Map<BlockPos, TemplateBlockWrite> writes,
            BlockPos position,
            BlockState original,
            BlockState target,
            TemplateBlockWrite.Kind kind) {
        if (original.equals(target)) {
            return;
        }
        TemplateBlockWrite previous = writes.put(
                position,
                new TemplateBlockWrite(
                        position,
                        original,
                        target,
                        kind));
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Multiple template writes target " + position);
        }
    }

    private static BlockPos offsetExact(
            BlockPos origin,
            BlockPos relative) {
        try {
            return new BlockPos(
                    Math.addExact(
                            origin.getX(),
                            relative.getX()),
                    Math.addExact(
                            origin.getY(),
                            relative.getY()),
                    Math.addExact(
                            origin.getZ(),
                            relative.getZ()));
        } catch (ArithmeticException exception) {
            throw new PlacementPreparationException(
                    "Template position exceeds world coordinates");
        }
    }

    private static Set<BlockPos> orderedPositions(
            Set<BlockPos> positions) {
        return positions.stream()
                .sorted(POSITION_ORDER)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll);
    }

    interface BlockStateReader {
        BlockState blockState(BlockPos position);
    }
}
