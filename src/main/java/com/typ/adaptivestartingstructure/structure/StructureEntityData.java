package com.typ.adaptivestartingstructure.structure;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

public record StructureEntityData(
        int sourceIndex,
        Vec3 position,
        BlockPos blockPosition,
        CompoundTag nbt,
        Optional<String> authoredId) {

    public StructureEntityData {
        if (sourceIndex < 0) {
            throw new IllegalArgumentException(
                    "Entity source index must not be negative");
        }
        position = Objects.requireNonNull(
                position,
                "position");
        if (!Double.isFinite(position.x)
                || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)) {
            throw new IllegalArgumentException(
                    "Entity position must be finite");
        }
        blockPosition = Objects.requireNonNull(
                        blockPosition,
                        "blockPosition")
                .immutable();
        nbt = Objects.requireNonNull(nbt, "nbt").copy();
        authoredId = Objects.requireNonNull(
                authoredId,
                "authoredId");
    }

    @Override
    public CompoundTag nbt() {
        return nbt.copy();
    }

    public CompoundTag copyNbt() {
        return nbt.copy();
    }
}
