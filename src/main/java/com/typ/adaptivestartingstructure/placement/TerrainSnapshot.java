package com.typ.adaptivestartingstructure.placement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TerrainSnapshot {
    private final PlacementBounds bounds;
    private final int minimumBuildHeight;
    private final int maximumBuildHeight;
    private final int targetGroundY;
    private final List<TerrainColumnSnapshot> columns;
    private final Map<Long, TerrainColumnSnapshot> columnsByPosition;
    private final long capturedBlocks;
    private final long fluidBlocks;
    private final long vegetationBlocks;

    TerrainSnapshot(
            PlacementBounds bounds,
            int minimumBuildHeight,
            int maximumBuildHeight,
            int targetGroundY,
            List<TerrainColumnSnapshot> columns) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.minimumBuildHeight = minimumBuildHeight;
        this.maximumBuildHeight = maximumBuildHeight;
        this.targetGroundY = targetGroundY;
        this.columns = List.copyOf(columns);
        if (maximumBuildHeight <= minimumBuildHeight
                || targetGroundY < minimumBuildHeight
                || targetGroundY >= maximumBuildHeight) {
            throw new IllegalArgumentException(
                    "Terrain snapshot build heights are invalid");
        }
        if ((long) this.columns.size()
                != bounds.affectedColumnCount()) {
            throw new IllegalArgumentException(
                    "Terrain snapshot must cover every affected column");
        }

        Map<Long, TerrainColumnSnapshot> indexed =
                new LinkedHashMap<>(this.columns.size());
        long captured = 0L;
        long fluids = 0L;
        long vegetation = 0L;
        for (TerrainColumnSnapshot column : this.columns) {
            if (!bounds.containsHorizontal(column.x(), column.z())
                    || column.minimumCapturedY() < minimumBuildHeight
                    || column.maximumCapturedYExclusive()
                            > maximumBuildHeight
                    || indexed.put(
                                    pack(column.x(), column.z()),
                                    column)
                            != null) {
                throw new IllegalArgumentException(
                        "Terrain snapshot contains an invalid column");
            }
            captured = Math.addExact(
                    captured,
                    column.capturedBlockCount());
            fluids = Math.addExact(
                    fluids,
                    column.fluidPositions().size());
            vegetation = Math.addExact(
                    vegetation,
                    column.vegetationPositions().size());
        }
        this.columnsByPosition =
                Collections.unmodifiableMap(indexed);
        this.capturedBlocks = captured;
        this.fluidBlocks = fluids;
        this.vegetationBlocks = vegetation;
    }

    public PlacementBounds bounds() {
        return bounds;
    }

    public int minimumBuildHeight() {
        return minimumBuildHeight;
    }

    public int maximumBuildHeight() {
        return maximumBuildHeight;
    }

    public int targetGroundY() {
        return targetGroundY;
    }

    public List<TerrainColumnSnapshot> columns() {
        return columns;
    }

    public TerrainColumnSnapshot column(int x, int z) {
        TerrainColumnSnapshot column =
                columnsByPosition.get(pack(x, z));
        if (column == null) {
            throw new IllegalArgumentException(
                    "Column [" + x + ", " + z
                            + "] is outside the terrain snapshot");
        }
        return column;
    }

    public long capturedBlocks() {
        return capturedBlocks;
    }

    public long fluidBlocks() {
        return fluidBlocks;
    }

    public long vegetationBlocks() {
        return vegetationBlocks;
    }

    static long pack(int x, int z) {
        return (long) x << 32 | z & 0xFFFF_FFFFL;
    }
}
