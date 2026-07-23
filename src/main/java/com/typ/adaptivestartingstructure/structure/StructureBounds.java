package com.typ.adaptivestartingstructure.structure;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

public record StructureBounds(BlockPos minimum, BlockPos maximum) {
    public StructureBounds {
        minimum = Objects.requireNonNull(minimum, "minimum").immutable();
        maximum = Objects.requireNonNull(maximum, "maximum").immutable();
        if (minimum.getX() > maximum.getX()
                || minimum.getY() > maximum.getY()
                || minimum.getZ() > maximum.getZ()) {
            throw new IllegalArgumentException("Structure bounds minimum must not exceed maximum");
        }
    }

    static StructureBounds relative(Vec3i size) {
        Objects.requireNonNull(size, "size");
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            throw new IllegalArgumentException("Structure bounds size must be positive");
        }
        return new StructureBounds(
                BlockPos.ZERO,
                new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
    }

    public boolean contains(BlockPos position) {
        return position.getX() >= minimum.getX() && position.getX() <= maximum.getX()
                && position.getY() >= minimum.getY() && position.getY() <= maximum.getY()
                && position.getZ() >= minimum.getZ() && position.getZ() <= maximum.getZ();
    }

    public Vec3i size() {
        return new Vec3i(
                maximum.getX() - minimum.getX() + 1,
                maximum.getY() - minimum.getY() + 1,
                maximum.getZ() - minimum.getZ() + 1);
    }
}
