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

public final class TerrainBlendPlan {
    private final TerrainSnapshot snapshot;
    private final PlacementBounds interventionBounds;
    private final List<BlendColumnTarget> columnTargets;
    private final Map<Long, BlendColumnTarget> targetsByPosition;
    private final Map<ChunkPos, List<TerrainWrite>> writesByChunk;
    private final List<TerrainWrite> writes;
    private final List<TerrainWrite> environmentWrites;
    private final List<TerrainWrite> treeCleanupPlanWrites;
    private final Set<BlockPos> fluidUpdatePositions;
    private final int modifiedColumns;
    private final int terrainWrites;
    private final int vegetationWrites;
    private final int selectedTreeCount;
    private final int selectedTreeBlockCount;
    private final int selectedTreeAccessoryCount;
    private final int treeCleanupWrites;

    TerrainBlendPlan(
            TerrainSnapshot snapshot,
            PlacementBounds interventionBounds,
            List<BlendColumnTarget> columnTargets,
            Map<ChunkPos, List<TerrainWrite>> writesByChunk,
            Set<BlockPos> fluidUpdatePositions,
            Set<BlockPos> treeCleanupWritePositions,
            int selectedTreeCount,
            int selectedTreeBlockCount,
            int selectedTreeAccessoryCount) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.interventionBounds = Objects.requireNonNull(
                interventionBounds,
                "interventionBounds");
        if (!snapshot.bounds().contains(this.interventionBounds)) {
            throw new IllegalArgumentException(
                    "Blend intervention bounds must be inside the snapshot");
        }
        this.columnTargets = List.copyOf(columnTargets);
        if (this.columnTargets.size()
                != snapshot.columns().size()) {
            throw new IllegalArgumentException(
                    "Blend targets must cover every snapshot column");
        }

        Map<Long, BlendColumnTarget> indexedTargets =
                new LinkedHashMap<>(this.columnTargets.size());
        int changedColumns = 0;
        for (BlendColumnTarget target : this.columnTargets) {
            if (!snapshot.bounds().containsHorizontal(
                            target.x(),
                            target.z())
                    || target.modifiesHeight()
                            && !this.interventionBounds.containsHorizontal(
                                    target.x(),
                                    target.z())
                    || indexedTargets.put(
                                    TerrainSnapshot.pack(
                                            target.x(),
                                            target.z()),
                                    target)
                            != null) {
                throw new IllegalArgumentException(
                        "Blend plan contains an invalid column target");
            }
            if (target.modifiesHeight()) {
                changedColumns++;
            }
        }
        this.targetsByPosition =
                Collections.unmodifiableMap(indexedTargets);
        this.modifiedColumns = changedColumns;

        Map<ChunkPos, List<TerrainWrite>> grouped =
                new LinkedHashMap<>();
        Set<BlockPos> treeCleanupPositions =
                immutablePositions(treeCleanupWritePositions);
        List<TerrainWrite> flattened = new ArrayList<>();
        Set<BlockPos> writtenPositions = new HashSet<>();
        int terrain = 0;
        int vegetation = 0;
        for (Map.Entry<ChunkPos, List<TerrainWrite>> entry
                : writesByChunk.entrySet()) {
            ChunkPos chunk =
                    Objects.requireNonNull(entry.getKey(), "chunk");
            List<TerrainWrite> chunkWrites =
                    List.copyOf(entry.getValue());
            if (chunkWrites.isEmpty()) {
                throw new IllegalArgumentException(
                        "Blend write groups must not be empty");
            }
            for (TerrainWrite write : chunkWrites) {
                BlendColumnTarget target = target(
                        write.position().getX(),
                        write.position().getZ());
                boolean treeCleanup = treeCleanupPositions.contains(
                        write.position());
                boolean placementFootprint =
                        Double.compare(
                                target.distanceFromFootprint(),
                                0.0D) == 0;
                if (!new ChunkPos(write.position()).equals(chunk)
                        || !writtenPositions.add(write.position())
                        || !this.interventionBounds.containsHorizontal(
                                        write.position().getX(),
                                        write.position().getZ())
                                && !treeCleanup
                        || !write.originalState()
                                        .getFluidState()
                                        .isEmpty()
                                && write.targetState().isAir()) {
                    throw new IllegalArgumentException(
                            "Blend plan contains an invalid write");
                }
                switch (write.kind()) {
                    case BLEND_CUT,
                            BLEND_FILL,
                            BLEND_MATERIAL -> {
                        if (!target.modifiesHeight()) {
                            throw new IllegalArgumentException(
                                    "Terrain write targets an unchanged blend column");
                        }
                        terrain++;
                    }
                    case VEGETATION_CLEAR -> {
                        if (!target.modifiesHeight()
                                && !placementFootprint
                                && !treeCleanup) {
                            throw new IllegalArgumentException(
                                    "Vegetation write targets an unaffected column");
                        }
                        vegetation++;
                    }
                    case FOOTPRINT_CUT,
                            FOOTPRINT_FILL,
                            FOOTPRINT_RESURFACE ->
                            throw new IllegalArgumentException(
                                    "Blend plan contains a leveling write");
                }
                if (treeCleanup
                        && write.kind()
                                != TerrainWrite.Kind.VEGETATION_CLEAR) {
                    throw new IllegalArgumentException(
                            "Tree cleanup contains a non-vegetation write");
                }
            }
            grouped.put(chunk, chunkWrites);
            flattened.addAll(chunkWrites);
        }
        this.writesByChunk =
                Collections.unmodifiableMap(grouped);
        this.writes = List.copyOf(flattened);
        this.environmentWrites = this.writes.stream()
                .filter(write -> !treeCleanupPositions.contains(
                        write.position()))
                .toList();
        this.treeCleanupPlanWrites = this.writes.stream()
                .filter(write -> treeCleanupPositions.contains(
                        write.position()))
                .toList();
        this.terrainWrites = terrain;
        this.vegetationWrites = vegetation;
        if (selectedTreeCount < 0
                || selectedTreeBlockCount < 0
                || selectedTreeAccessoryCount < 0
                || selectedTreeAccessoryCount > selectedTreeBlockCount
                || !writtenPositions.containsAll(treeCleanupPositions)) {
            throw new IllegalArgumentException(
                    "Tree-cleanup metrics do not match blend writes");
        }
        this.selectedTreeCount = selectedTreeCount;
        this.selectedTreeBlockCount = selectedTreeBlockCount;
        this.selectedTreeAccessoryCount = selectedTreeAccessoryCount;
        this.treeCleanupWrites = treeCleanupPositions.size();
        this.fluidUpdatePositions =
                immutablePositions(fluidUpdatePositions);
    }

    public TerrainSnapshot snapshot() {
        return snapshot;
    }

    public PlacementBounds interventionBounds() {
        return interventionBounds;
    }

    public List<BlendColumnTarget> columnTargets() {
        return columnTargets;
    }

    public BlendColumnTarget target(int x, int z) {
        BlendColumnTarget target = targetsByPosition.get(
                TerrainSnapshot.pack(x, z));
        if (target == null) {
            throw new IllegalArgumentException(
                    "Column is outside the blend plan");
        }
        return target;
    }

    public Map<ChunkPos, List<TerrainWrite>> writesByChunk() {
        return writesByChunk;
    }

    public List<TerrainWrite> writes() {
        return writes;
    }

    List<TerrainWrite> environmentWrites() {
        return environmentWrites;
    }

    List<TerrainWrite> treeCleanupPlanWrites() {
        return treeCleanupPlanWrites;
    }

    public Set<BlockPos> fluidUpdatePositions() {
        return fluidUpdatePositions;
    }

    public int modifiedColumns() {
        return modifiedColumns;
    }

    public int unchangedColumns() {
        return columnTargets.size() - modifiedColumns;
    }

    public int terrainWrites() {
        return terrainWrites;
    }

    public int vegetationWrites() {
        return vegetationWrites;
    }

    public int selectedTreeCount() {
        return selectedTreeCount;
    }

    public int selectedTreeBlockCount() {
        return selectedTreeBlockCount;
    }

    public int selectedTreeAccessoryCount() {
        return selectedTreeAccessoryCount;
    }

    public int treeCleanupWrites() {
        return treeCleanupWrites;
    }

    public int totalWrites() {
        return writes.size();
    }

    private static Set<BlockPos> immutablePositions(
            Set<BlockPos> positions) {
        Objects.requireNonNull(positions, "fluidUpdatePositions");
        LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
        for (BlockPos position : positions) {
            copy.add(Objects.requireNonNull(
                            position,
                            "fluidUpdatePosition")
                    .immutable());
        }
        return Collections.unmodifiableSet(copy);
    }
}
