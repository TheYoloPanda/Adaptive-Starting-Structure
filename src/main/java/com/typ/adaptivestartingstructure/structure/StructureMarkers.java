package com.typ.adaptivestartingstructure.structure;

import java.util.Objects;
import net.minecraft.core.BlockPos;

public record StructureMarkers(BlockPos spawn, BlockPos groundLevel) {
    public StructureMarkers {
        spawn = Objects.requireNonNull(spawn, "spawn").immutable();
        groundLevel = Objects.requireNonNull(groundLevel, "groundLevel").immutable();
        if (spawn.equals(groundLevel)) {
            throw new IllegalArgumentException("Structure markers must occupy distinct positions");
        }
    }
}
