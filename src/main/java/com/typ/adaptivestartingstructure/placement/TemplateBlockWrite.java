package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record TemplateBlockWrite(
        BlockPos position,
        BlockState originalState,
        BlockState targetState,
        Kind kind) {

    public TemplateBlockWrite {
        position = Objects.requireNonNull(position, "position").immutable();
        originalState =
                Objects.requireNonNull(originalState, "originalState");
        targetState = Objects.requireNonNull(targetState, "targetState");
        kind = Objects.requireNonNull(kind, "kind");
        if (originalState.equals(targetState)) {
            throw new IllegalArgumentException(
                    "Template writes must change the block state");
        }
    }

    public enum Kind {
        BLOCK,
        EXPLICIT_AIR,
        MARKER_CLEAR
    }
}
