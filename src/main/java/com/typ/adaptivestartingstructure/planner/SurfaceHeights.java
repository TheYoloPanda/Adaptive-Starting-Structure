package com.typ.adaptivestartingstructure.planner;

import java.util.Objects;
import java.util.Optional;

/**
 * The ocean-floor and world-surface worldgen heights of one position, with the
 * column they were read from when the source read one.
 */
public record SurfaceHeights(
        int oceanFloor,
        int worldSurface,
        Optional<TerrainColumn> column) {
    public SurfaceHeights {
        Objects.requireNonNull(column, "column");
    }
}
