package com.typ.adaptivestartingstructure.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class TemplatePlacementPlan {
    private final int paletteIndex;
    private final int paletteCount;
    private final int templateBlocks;
    private final int explicitAir;
    private final Map<ChunkPos, List<TemplateBlockWrite>> writesByChunk;
    private final List<TemplateBlockWrite> writes;
    private final List<TemplateBlockEntityData> blockEntities;
    private final Set<BlockPos> finalUpdatePositions;
    private final Set<BlockPos> fluidUpdatePositions;

    TemplatePlacementPlan(
            int paletteIndex,
            int paletteCount,
            int templateBlocks,
            int explicitAir,
            Map<ChunkPos, List<TemplateBlockWrite>> writesByChunk,
            List<TemplateBlockEntityData> blockEntities,
            Set<BlockPos> finalUpdatePositions,
            Set<BlockPos> fluidUpdatePositions,
            PlacementBounds bounds) {
        if (paletteCount <= 0
                || paletteIndex < 0
                || paletteIndex >= paletteCount
                || templateBlocks < 0
                || explicitAir < 0) {
            throw new IllegalArgumentException(
                    "Template placement counts or palette are invalid");
        }
        this.paletteIndex = paletteIndex;
        this.paletteCount = paletteCount;
        this.templateBlocks = templateBlocks;
        this.explicitAir = explicitAir;
        this.blockEntities = List.copyOf(blockEntities);
        this.finalUpdatePositions =
                immutablePositions(finalUpdatePositions);
        this.fluidUpdatePositions =
                immutablePositions(fluidUpdatePositions);

        Map<ChunkPos, List<TemplateBlockWrite>> grouped =
                new LinkedHashMap<>();
        List<TemplateBlockWrite> flattened =
                new ArrayList<>();
        Set<BlockPos> positions = new HashSet<>();
        for (Map.Entry<ChunkPos, List<TemplateBlockWrite>> entry
                : writesByChunk.entrySet()) {
            ChunkPos chunk =
                    Objects.requireNonNull(entry.getKey(), "chunk");
            List<TemplateBlockWrite> chunkWrites =
                    List.copyOf(entry.getValue());
            if (chunkWrites.isEmpty()) {
                throw new IllegalArgumentException(
                        "Template write groups must not be empty");
            }
            for (TemplateBlockWrite write : chunkWrites) {
                if (!new ChunkPos(write.position()).equals(chunk)
                        || !bounds.containsHorizontal(
                                write.position().getX(),
                                write.position().getZ())
                        || !bounds.structureBounds().contains(
                                write.position())
                        || !positions.add(write.position())) {
                    throw new IllegalArgumentException(
                            "Template plan contains an invalid write");
                }
            }
            grouped.put(chunk, chunkWrites);
            flattened.addAll(chunkWrites);
        }
        for (TemplateBlockEntityData blockEntity
                : this.blockEntities) {
            if (!bounds.structureBounds().contains(
                    blockEntity.position())
                    || !this.finalUpdatePositions.contains(
                            blockEntity.position())) {
                throw new IllegalArgumentException(
                        "Template BlockEntity lies outside final updates");
            }
        }
        if (!this.finalUpdatePositions.containsAll(
                        positions)
                || !this.finalUpdatePositions.containsAll(
                        this.fluidUpdatePositions)) {
            throw new IllegalArgumentException(
                    "Template update sets do not cover all changed positions");
        }
        this.writesByChunk =
                Collections.unmodifiableMap(grouped);
        this.writes = List.copyOf(flattened);
    }

    public int paletteIndex() {
        return paletteIndex;
    }

    public int paletteCount() {
        return paletteCount;
    }

    public int templateBlocks() {
        return templateBlocks;
    }

    public int explicitAir() {
        return explicitAir;
    }

    public Map<ChunkPos, List<TemplateBlockWrite>> writesByChunk() {
        return writesByChunk;
    }

    public List<TemplateBlockWrite> writes() {
        return writes;
    }

    public List<TemplateBlockEntityData> blockEntities() {
        return blockEntities;
    }

    public Set<BlockPos> finalUpdatePositions() {
        return finalUpdatePositions;
    }

    public Set<BlockPos> fluidUpdatePositions() {
        return fluidUpdatePositions;
    }

    public int totalWrites() {
        return writes.size();
    }

    private static Set<BlockPos> immutablePositions(
            Set<BlockPos> positions) {
        Objects.requireNonNull(positions, "positions");
        LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
        for (BlockPos position : positions) {
            copy.add(Objects.requireNonNull(position, "position")
                    .immutable());
        }
        return Collections.unmodifiableSet(copy);
    }
}
