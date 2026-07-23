package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class SpawnAreaValidator {
    static final int MAXIMUM_SAFE_FALL_DISTANCE = 3;

    private SpawnAreaValidator() {
    }

    static SpawnValidationResult validate(
            int configuredRadius,
            BlockPos spawnFeet,
            StructureBounds safeBounds,
            Supplier<? extends List<? extends BlockGetter>> viewsSupplier) {
        Objects.requireNonNull(spawnFeet, "spawnFeet");
        Objects.requireNonNull(safeBounds, "safeBounds");
        Objects.requireNonNull(viewsSupplier, "viewsSupplier");

        int radius = Math.max(0, configuredRadius);
        EnumMap<SpawnRejectionReason, Integer> rejectionCounts =
                new EnumMap<>(SpawnRejectionReason.class);
        if (!isHorizontalAreaInsideBounds(
                spawnFeet,
                radius,
                safeBounds.minimum(),
                safeBounds.maximum())) {
            rejectionCounts.put(
                    SpawnRejectionReason.AREA_OUTSIDE_STRUCTURE_BOUNDS,
                    1);
            return new SpawnValidationResult(
                    spawnFeet,
                    configuredRadius,
                    radius,
                    0,
                    rejectionCounts);
        }

        List<? extends BlockGetter> views =
                List.copyOf(viewsSupplier.get());
        if (views.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one final block view is required");
        }
        BlockGetter reference = Objects.requireNonNull(
                views.getFirst(),
                "block view");
        for (BlockGetter view : views) {
            Objects.requireNonNull(view, "block view");
            if (view.getMinBuildHeight()
                            != reference.getMinBuildHeight()
                    || view.getMaxBuildHeight()
                            != reference.getMaxBuildHeight()) {
                throw new IllegalArgumentException(
                        "Final block views must share one build-height range");
            }
        }

        long minimumX = (long) spawnFeet.getX() - radius;
        long maximumX = (long) spawnFeet.getX() + radius;
        long minimumZ = (long) spawnFeet.getZ() - radius;
        long maximumZ = (long) spawnFeet.getZ() + radius;
        int checkedColumns = 0;
        for (long x = minimumX; x <= maximumX; x++) {
            for (long z = minimumZ; z <= maximumZ; z++) {
                checkedColumns++;
                BlockPos initialFeet = new BlockPos(
                        Math.toIntExact(x),
                        spawnFeet.getY(),
                        Math.toIntExact(z));
                EnumMap<SpawnRejectionReason, Boolean> columnReasons =
                        new EnumMap<>(SpawnRejectionReason.class);
                for (BlockGetter view : views) {
                    validateColumn(
                            view,
                            initialFeet,
                            columnReasons);
                }
                mergeColumnReasons(
                        rejectionCounts,
                        columnReasons);
            }
        }
        return new SpawnValidationResult(
                spawnFeet,
                configuredRadius,
                radius,
                checkedColumns,
                rejectionCounts);
    }

    private static void validateColumn(
            BlockGetter view,
            BlockPos initialFeet,
            EnumMap<SpawnRejectionReason, Boolean> columnReasons) {
        if (!containsPlayerHeight(view, initialFeet)) {
            columnReasons.put(
                    SpawnRejectionReason.SPAWN_HEIGHT_OUTSIDE_BUILD_LIMITS,
                    true);
            return;
        }

        inspectBodySpace(view, initialFeet, columnReasons);
        FloorSearch floorSearch =
                findFloor(view, initialFeet, columnReasons);
        if (floorSearch.floorPosition() == null) {
            columnReasons.put(
                    SpawnRejectionReason.DANGEROUS_FALL,
                    true);
            return;
        }
        if (!floorSearch.solid()) {
            columnReasons.put(
                    SpawnRejectionReason.NON_SOLID_FLOOR,
                    true);
            return;
        }

        BlockPos landingFeet =
                floorSearch.floorPosition().above();
        inspectBodySpace(view, landingFeet, columnReasons);
        BlockState floorState =
                view.getBlockState(floorSearch.floorPosition());
        if (EntityType.PLAYER.isBlockDangerous(floorState)) {
            columnReasons.put(
                    SpawnRejectionReason.DANGEROUS_BLOCK,
                    true);
        }
    }

    private static FloorSearch findFloor(
            BlockGetter view,
            BlockPos initialFeet,
            EnumMap<SpawnRejectionReason, Boolean> columnReasons) {
        BlockPos.MutableBlockPos inspected =
                initialFeet.mutable();
        for (int fallDistance = 0;
                fallDistance <= MAXIMUM_SAFE_FALL_DISTANCE;
                fallDistance++) {
            inspected.setY(
                    initialFeet.getY() - fallDistance - 1);
            if (view.isOutsideBuildHeight(inspected)) {
                return FloorSearch.missing();
            }
            BlockState state =
                    view.getBlockState(inspected);
            if (!state.getFluidState().isEmpty()) {
                columnReasons.put(
                        SpawnRejectionReason.FLUID_PRESENT,
                        true);
            }
            VoxelShape shape = state.getCollisionShape(
                    view,
                    inspected,
                    CollisionContext.empty());
            if (!shape.isEmpty()) {
                return new FloorSearch(
                        inspected.immutable(),
                        Block.isFaceFull(shape, Direction.UP));
            }
        }
        return FloorSearch.missing();
    }

    private static void inspectBodySpace(
            BlockGetter view,
            BlockPos feet,
            EnumMap<SpawnRejectionReason, Boolean> columnReasons) {
        if (!containsPlayerHeight(view, feet)) {
            columnReasons.put(
                    SpawnRejectionReason.SPAWN_HEIGHT_OUTSIDE_BUILD_LIMITS,
                    true);
            return;
        }

        for (int verticalOffset = 0;
                verticalOffset < 2;
                verticalOffset++) {
            BlockPos position = feet.above(verticalOffset);
            BlockState state = view.getBlockState(position);
            if (!state.getFluidState().isEmpty()) {
                columnReasons.put(
                        SpawnRejectionReason.FLUID_PRESENT,
                        true);
            }
            if (state.is(BlockTags.INVALID_SPAWN_INSIDE)) {
                columnReasons.put(
                        SpawnRejectionReason.INVALID_FEET_POSITION,
                        true);
            }
            if (EntityType.PLAYER.isBlockDangerous(state)) {
                columnReasons.put(
                        SpawnRejectionReason.DANGEROUS_BLOCK,
                        true);
            }
            if (!state.getCollisionShape(
                            view,
                            position,
                            CollisionContext.empty())
                    .isEmpty()) {
                columnReasons.put(
                        SpawnRejectionReason.INSUFFICIENT_VERTICAL_SPACE,
                        true);
            }
        }

        AABB playerBounds = EntityType.PLAYER
                .getDimensions()
                .makeBoundingBox(Vec3.atBottomCenterOf(feet));
        if (hasBlockCollision(view, playerBounds)) {
            columnReasons.put(
                    SpawnRejectionReason.PLAYER_COLLISION,
                    true);
        }
    }

    private static boolean hasBlockCollision(
            BlockGetter view,
            AABB playerBounds) {
        int minimumX =
                net.minecraft.util.Mth.floor(playerBounds.minX);
        int maximumX = net.minecraft.util.Mth.floor(
                Math.nextDown(playerBounds.maxX));
        int minimumY =
                net.minecraft.util.Mth.floor(playerBounds.minY);
        int maximumY = net.minecraft.util.Mth.floor(
                Math.nextDown(playerBounds.maxY));
        int minimumZ =
                net.minecraft.util.Mth.floor(playerBounds.minZ);
        int maximumZ = net.minecraft.util.Mth.floor(
                Math.nextDown(playerBounds.maxZ));
        BlockPos.MutableBlockPos position =
                new BlockPos.MutableBlockPos();
        for (int x = minimumX; x <= maximumX; x++) {
            for (int y = minimumY; y <= maximumY; y++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    position.set(x, y, z);
                    VoxelShape shape = view.getBlockState(position)
                            .getCollisionShape(
                                    view,
                                    position,
                                    CollisionContext.empty())
                            .move(x, y, z);
                    if (Shapes.joinIsNotEmpty(
                            shape,
                            Shapes.create(playerBounds),
                            BooleanOp.AND)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsPlayerHeight(
            BlockGetter view,
            BlockPos feet) {
        return !view.isOutsideBuildHeight(feet)
                && !view.isOutsideBuildHeight(feet.above());
    }

    private static void mergeColumnReasons(
            EnumMap<SpawnRejectionReason, Integer> rejectionCounts,
            EnumMap<SpawnRejectionReason, Boolean> columnReasons) {
        for (SpawnRejectionReason reason
                : columnReasons.keySet()) {
            rejectionCounts.merge(reason, 1, Integer::sum);
        }
    }

    private static boolean isHorizontalAreaInsideBounds(
            BlockPos spawnFeet,
            int radius,
            BlockPos minimum,
            BlockPos maximum) {
        long minimumX = (long) spawnFeet.getX() - radius;
        long maximumX = (long) spawnFeet.getX() + radius;
        long minimumZ = (long) spawnFeet.getZ() - radius;
        long maximumZ = (long) spawnFeet.getZ() + radius;
        return minimumX >= minimum.getX()
                && maximumX <= maximum.getX()
                && minimumZ >= minimum.getZ()
                && maximumZ <= maximum.getZ();
    }

    private record FloorSearch(
            BlockPos floorPosition,
            boolean solid) {
        private static FloorSearch missing() {
            return new FloorSearch(null, false);
        }
    }
}
