package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.structure.FootprintColumn;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class GeneratedSiteValidator {
    private GeneratedSiteValidator() {
    }

    static GeneratedSiteValidation validate(
            ServerLevel level,
            StartingStructurePlan plan,
            RotatedStructureView structure,
            PlacementBounds bounds,
            ConfigSnapshot config) {
        return validate(
                new ServerWorldView(level),
                plan,
                structure,
                bounds,
                config);
    }

    static GeneratedSiteValidation validate(
            WorldView world,
            StartingStructurePlan plan,
            RotatedStructureView structure,
            PlacementBounds bounds,
            ConfigSnapshot config) {
        if (bounds.structureBounds().minimum().getY()
                        < world.minimumBuildHeight()
                || bounds.structureBounds().maximum().getY()
                        >= world.maximumBuildHeight()) {
            throw new UnsuitableGeneratedSiteException(
                    "Persisted structure bounds exceed the generated world's build height");
        }

        BlockPos origin = plan.candidate().placementOrigin();
        Set<Long> footprint = new HashSet<>(structure.footprint().size());
        for (FootprintColumn column : structure.footprint()) {
            footprint.add(pack(
                    Math.addExact(origin.getX(), column.x()),
                    Math.addExact(origin.getZ(), column.z())));
        }
        Map<Long, Set<Integer>> explicitAirY = new HashMap<>();
        for (BlockPos relative : structure.explicitAirPositions()) {
            int absoluteX = Math.addExact(origin.getX(), relative.getX());
            int absoluteY = Math.addExact(origin.getY(), relative.getY());
            int absoluteZ = Math.addExact(origin.getZ(), relative.getZ());
            explicitAirY
                    .computeIfAbsent(
                            pack(absoluteX, absoluteZ),
                            ignored -> new HashSet<>())
                    .add(absoluteY);
        }

        long affectedColumns = 0L;
        long waterColumns = 0L;
        int footprintColumns = 0;
        int minimumGroundY = Integer.MAX_VALUE;
        int maximumGroundY = Integer.MIN_VALUE;
        int maximumPerimeterError = 0;
        int maximumCutDepth = 0;
        int maximumFillDepth = 0;
        MeasuredColumn lowest = null;
        MeasuredColumn highest = null;
        int targetGroundY = plan.candidate().groundSurfaceY();

        for (int x = bounds.minimumAffectedX(); ; x++) {
            for (int z = bounds.minimumAffectedZ(); ; z++) {
                if (bounds.containsHorizontal(x, z)) {
                    affectedColumns++;
                    if (!world.isInsideWorldBorder(x, z)) {
                        throw new UnsuitableGeneratedSiteException(
                                "Generated placement area is outside the world border at ["
                                        + x + ", " + z + "]");
                    }
                    int reportedTerrainHeight =
                            world.oceanFloorHeight(x, z);
                    int surfaceHeight = world.worldSurfaceHeight(x, z);
                    if (reportedTerrainHeight
                                    <= world.minimumBuildHeight()
                            || reportedTerrainHeight
                                    > world.maximumBuildHeight()
                            || surfaceHeight <= world.minimumBuildHeight()
                            || surfaceHeight > world.maximumBuildHeight()
                            || surfaceHeight
                                    < reportedTerrainHeight) {
                        throw new UnsuitableGeneratedSiteException(
                                "Generated terrain has invalid heights at ["
                                        + x + ", " + z + "]");
                    }
                    int columnX = x;
                    int columnZ = z;
                    int groundY =
                            TerrainSurfaceClassifier.findGroundY(
                                    world.minimumBuildHeight(),
                                    surfaceHeight,
                                    y -> world.blockState(
                                            columnX,
                                            y,
                                            columnZ));

                    long key = pack(x, z);
                    FluidKind fluid = inspectFluidRange(
                            world,
                            x,
                            z,
                            groundY + 1,
                            surfaceHeight);
                    Set<Integer> airY = explicitAirY.get(key);
                    if (airY != null) {
                        for (int y : airY) {
                            if (y >= world.minimumBuildHeight()
                                    && y < world.maximumBuildHeight()) {
                                fluid = combine(
                                        fluid,
                                        classify(world.blockState(x, y, z)));
                            }
                        }
                    }
                    if (fluid == FluidKind.UNSUPPORTED) {
                        throw new UnsuitableGeneratedSiteException(
                                "Generated placement area contains lava or an unsupported fluid at ["
                                        + x + ", " + z + "]");
                    }
                    if (fluid == FluidKind.WATER) {
                        waterColumns++;
                    }

                    if (footprint.contains(key)) {
                        footprintColumns++;
                        if (groundY < minimumGroundY) {
                            minimumGroundY = groundY;
                            lowest = new MeasuredColumn(x, z, groundY, surfaceHeight);
                        }
                        if (groundY > maximumGroundY) {
                            maximumGroundY = groundY;
                            highest = new MeasuredColumn(x, z, groundY, surfaceHeight);
                        }
                        int signedError = groundY - targetGroundY;
                        maximumCutDepth = Math.max(
                                maximumCutDepth,
                                Math.max(0, signedError));
                        maximumFillDepth = Math.max(
                                maximumFillDepth,
                                Math.max(0, -signedError));
                        if (isFootprintPerimeter(footprint, x, z)) {
                            maximumPerimeterError = Math.max(
                                    maximumPerimeterError,
                                    Math.abs(signedError));
                        }
                    }
                }
                if (z == bounds.maximumAffectedZ()) {
                    break;
                }
            }
            if (x == bounds.maximumAffectedX()) {
                break;
            }
        }

        if (footprintColumns != footprint.size()
                || affectedColumns != bounds.affectedColumnCount()) {
            throw new PlacementPreparationException(
                    "Generated-site validation did not cover the exact planned area");
        }
        GeneratedSiteValidation validation = new GeneratedSiteValidation(
                affectedColumns,
                footprintColumns,
                waterColumns,
                minimumGroundY,
                maximumGroundY,
                maximumPerimeterError,
                maximumCutDepth,
                maximumFillDepth);
        if ((long) maximumGroundY - minimumGroundY
                        > config.maximumElevationRange()
                || maximumCutDepth > config.maximumCutDepth()
                || maximumFillDepth > config.maximumFillDepth()
                || maximumPerimeterError
                        > config.maximumPerimeterError()
                || validation.waterFraction()
                        > config.maximumWaterFraction()) {
            throw new UnsuitableGeneratedSiteException(
                    "Generated terrain no longer satisfies placement thresholds: "
                            + "actual[elevationRange="
                            + ((long) maximumGroundY - minimumGroundY)
                            + ", maximumCutDepth="
                            + maximumCutDepth
                            + ", maximumFillDepth="
                            + maximumFillDepth
                            + ", maximumPerimeterError="
                            + maximumPerimeterError
                            + ", waterFraction="
                            + validation.waterFraction()
                            + "], configured[elevationRange="
                            + config.maximumElevationRange()
                            + ", maximumCutDepth="
                            + config.maximumCutDepth()
                            + ", maximumFillDepth="
                            + config.maximumFillDepth()
                            + ", maximumPerimeterError="
                            + config.maximumPerimeterError()
                            + ", waterFraction="
                            + config.maximumWaterFraction()
                            + "]"
                            + describeExtremes(world, highest, lowest));
        }
        return validation;
    }

    private static String describeExtremes(
            WorldView world,
            MeasuredColumn highest,
            MeasuredColumn lowest) {
        if (highest == null || lowest == null) {
            return "";
        }
        return "; highest " + highest.describe(world)
                + "; lowest " + lowest.describe(world);
    }

    private record MeasuredColumn(int x, int z, int groundY, int surfaceHeight) {
        String describe(WorldView world) {
            BlockState above = groundY + 1 < world.maximumBuildHeight()
                    ? world.blockState(x, groundY + 1, z)
                    : Blocks.AIR.defaultBlockState();
            return "at [" + x + ", " + z + "]: "
                    + UnsuitableGeneratedSiteException.describeGround(
                            groundY,
                            world.blockState(x, groundY, z),
                            above,
                            surfaceHeight - 1);
        }
    }

    private static FluidKind inspectFluidRange(
            WorldView world,
            int x,
            int z,
            int lowerYInclusive,
            int upperYExclusive) {
        FluidKind result = FluidKind.NONE;
        for (int y = lowerYInclusive; y < upperYExclusive; y++) {
            result = combine(
                    result,
                    classify(world.blockState(x, y, z)));
            if (result == FluidKind.UNSUPPORTED) {
                return result;
            }
        }
        return result;
    }

    private static FluidKind classify(BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return FluidKind.NONE;
        }
        return fluid.is(FluidTags.WATER)
                        || fluid.is(Fluids.WATER)
                        || fluid.is(Fluids.FLOWING_WATER)
                ? FluidKind.WATER
                : FluidKind.UNSUPPORTED;
    }

    private static FluidKind combine(
            FluidKind first,
            FluidKind second) {
        if (first == FluidKind.UNSUPPORTED
                || second == FluidKind.UNSUPPORTED) {
            return FluidKind.UNSUPPORTED;
        }
        return first == FluidKind.WATER || second == FluidKind.WATER
                ? FluidKind.WATER
                : FluidKind.NONE;
    }

    private static boolean isFootprintPerimeter(
            Set<Long> footprint,
            int x,
            int z) {
        return !footprint.contains(pack(x - 1, z))
                || !footprint.contains(pack(x + 1, z))
                || !footprint.contains(pack(x, z - 1))
                || !footprint.contains(pack(x, z + 1));
    }

    private static long pack(int x, int z) {
        return (long) x << 32 | z & 0xFFFF_FFFFL;
    }

    interface WorldView {
        int minimumBuildHeight();

        int maximumBuildHeight();

        boolean isInsideWorldBorder(int x, int z);

        int oceanFloorHeight(int x, int z);

        int worldSurfaceHeight(int x, int z);

        BlockState blockState(int x, int y, int z);
    }

    private record ServerWorldView(ServerLevel level) implements WorldView {
        @Override
        public int minimumBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public int maximumBuildHeight() {
            return level.getMaxBuildHeight();
        }

        @Override
        public boolean isInsideWorldBorder(int x, int z) {
            return level.getWorldBorder().isWithinBounds(x, z);
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        }

        @Override
        public int worldSurfaceHeight(int x, int z) {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        }

        @Override
        public BlockState blockState(int x, int y, int z) {
            return level.getBlockState(new BlockPos(x, y, z));
        }
    }

    private enum FluidKind {
        NONE,
        WATER,
        UNSUPPORTED
    }
}
