package com.typ.adaptivestartingstructure.structure;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class StructureTransforms {
    private StructureTransforms() {
    }

    public static BlockPos transform(BlockPos position, Vec3i sourceSize, Rotation rotation) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(sourceSize, "sourceSize");
        Objects.requireNonNull(rotation, "rotation");
        BlockPos transformed = StructureTemplate.transform(
                position,
                Mirror.NONE,
                rotation,
                BlockPos.ZERO);
        BlockPos zeroOffset = StructureTemplate.getZeroPositionWithTransform(
                BlockPos.ZERO,
                Mirror.NONE,
                rotation,
                sourceSize.getX(),
                sourceSize.getZ());
        return transformed.offset(zeroOffset).immutable();
    }

    public static Vec3i transformedSize(Vec3i sourceSize, Rotation rotation) {
        Objects.requireNonNull(sourceSize, "sourceSize");
        Objects.requireNonNull(rotation, "rotation");
        return switch (rotation) {
            case CLOCKWISE_90, COUNTERCLOCKWISE_90 ->
                    new Vec3i(sourceSize.getZ(), sourceSize.getY(), sourceSize.getX());
            case NONE, CLOCKWISE_180 -> sourceSize;
        };
    }
}
