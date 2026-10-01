package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record TerrainWrite(
        BlockPos position,
        BlockState originalState,
        BlockState targetState,
        Kind kind) {

    public TerrainWrite {
        position = Objects.requireNonNull(position, "position").immutable();
        originalState =
                Objects.requireNonNull(originalState, "originalState");
        targetState = Objects.requireNonNull(targetState, "targetState");
        kind = Objects.requireNonNull(kind, "kind");
        if (originalState.equals(targetState)) {
            throw new IllegalArgumentException(
                    "Terrain writes must change the block state");
        }
    }

    public enum Kind {
        FOOTPRINT_CUT,
        FOOTPRINT_FILL,
        FOOTPRINT_RESURFACE,
        BLEND_CUT,
        BLEND_FILL,
        BLEND_MATERIAL,
        VEGETATION_CLEAR,
        SNOW_COVER
    }
}
