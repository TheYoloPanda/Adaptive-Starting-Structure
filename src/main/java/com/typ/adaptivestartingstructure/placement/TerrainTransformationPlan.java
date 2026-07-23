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

public final class TerrainTransformationPlan {
    private final TerrainSnapshot snapshot;
    private final Set<Long> footprintPositions;
    private final int modifiedColumns;
    private final Map<ChunkPos, List<TerrainWrite>> writesByChunk;
    private final List<TerrainWrite> writes;
    private final Set<BlockPos> structuralAirPositions;
    private final int cutWrites;
    private final int fillWrites;
    private final int resurfaceWrites;

    TerrainTransformationPlan(
            TerrainSnapshot snapshot,
            Set<Long> footprintPositions,
            Map<ChunkPos, List<TerrainWrite>> writesByChunk,
            Set<BlockPos> structuralAirPositions) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(
                footprintPositions,
                "footprintPositions");
        if (footprintPositions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Structure footprint must not be empty");
        }
        this.footprintPositions =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(footprintPositions));
        for (Long position : this.footprintPositions) {
            if (position == null
                    || !snapshot.bounds().containsHorizontal(
                            (int) (position >> 32),
                            position.intValue())) {
                throw new IllegalArgumentException(
                        "Structure footprint is outside the terrain snapshot");
            }
        }
        this.structuralAirPositions =
                immutablePositions(structuralAirPositions);

        Map<ChunkPos, List<TerrainWrite>> grouped =
                new LinkedHashMap<>();
        List<TerrainWrite> flattened = new ArrayList<>();
        Set<BlockPos> positions = new HashSet<>();
        Set<Long> modifiedHorizontal = new HashSet<>();
        int cuts = 0;
        int fills = 0;
        int resurfaces = 0;
        for (Map.Entry<ChunkPos, List<TerrainWrite>> entry
                : writesByChunk.entrySet()) {
            ChunkPos chunk =
                    Objects.requireNonNull(entry.getKey(), "chunk");
            List<TerrainWrite> chunkWrites =
                    List.copyOf(entry.getValue());
            if (chunkWrites.isEmpty()) {
                throw new IllegalArgumentException(
                        "Terrain write groups must not be empty");
            }
            for (TerrainWrite write : chunkWrites) {
                if (!new ChunkPos(write.position()).equals(chunk)
                        || !snapshot.bounds().containsHorizontal(
                                write.position().getX(),
                                write.position().getZ())
                        || !this.footprintPositions.contains(
                                TerrainSnapshot.pack(
                                        write.position().getX(),
                                        write.position().getZ()))
                        || !snapshot
                                .column(
                                        write.position().getX(),
                                        write.position().getZ())
                                .stateAt(write.position().getY())
                                .equals(write.originalState())
                        || !positions.add(write.position())) {
                    throw new IllegalArgumentException(
                            "Terrain plan contains an invalid write");
                }
                modifiedHorizontal.add(TerrainSnapshot.pack(
                        write.position().getX(),
                        write.position().getZ()));
                switch (write.kind()) {
                    case FOOTPRINT_CUT -> cuts++;
                    case FOOTPRINT_FILL -> fills++;
                    case FOOTPRINT_RESURFACE -> resurfaces++;
                    case BLEND_CUT,
                            BLEND_FILL,
                            BLEND_MATERIAL,
                            VEGETATION_CLEAR ->
                            throw new IllegalArgumentException(
                                    "Leveling plan contains a blend write");
                }
            }
            grouped.put(chunk, chunkWrites);
            flattened.addAll(chunkWrites);
        }
        if (modifiedHorizontal.size()
                > this.footprintPositions.size()) {
            throw new IllegalArgumentException(
                    "Modified columns exceed the structure footprint");
        }
        this.modifiedColumns = modifiedHorizontal.size();
        this.writesByChunk =
                Collections.unmodifiableMap(grouped);
        this.writes = List.copyOf(flattened);
        this.cutWrites = cuts;
        this.fillWrites = fills;
        this.resurfaceWrites = resurfaces;
    }

    public TerrainSnapshot snapshot() {
        return snapshot;
    }

    public int footprintColumns() {
        return footprintPositions.size();
    }

    public boolean isFootprintColumn(int x, int z) {
        return footprintPositions.contains(
                TerrainSnapshot.pack(x, z));
    }

    public int modifiedColumns() {
        return modifiedColumns;
    }

    public int unchangedColumns() {
        return footprintColumns() - modifiedColumns;
    }

    public Map<ChunkPos, List<TerrainWrite>> writesByChunk() {
        return writesByChunk;
    }

    public List<TerrainWrite> writes() {
        return writes;
    }

    public Set<BlockPos> structuralAirPositions() {
        return structuralAirPositions;
    }

    public int cutWrites() {
        return cutWrites;
    }

    public int fillWrites() {
        return fillWrites;
    }

    public int resurfaceWrites() {
        return resurfaceWrites;
    }

    public int totalWrites() {
        return writes.size();
    }

    private static Set<BlockPos> immutablePositions(
            Set<BlockPos> positions) {
        Objects.requireNonNull(positions, "structuralAirPositions");
        LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
        for (BlockPos position : positions) {
            if (!copy.add(Objects.requireNonNull(
                            position,
                            "structuralAirPosition")
                    .immutable())) {
                throw new IllegalArgumentException(
                        "Structural air positions must be distinct");
            }
        }
        return Collections.unmodifiableSet(copy);
    }
}
