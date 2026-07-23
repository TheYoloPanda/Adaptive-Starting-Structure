package com.typ.adaptivestartingstructure.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

final class TerrainSnapshotCapture {
    private static final int MATERIAL_SAMPLE_DEPTH = 8;
    private static final int DEEP_MATERIAL_OFFSET = 4;

    private TerrainSnapshotCapture() {
    }

    static TerrainSnapshot capture(
            ServerLevel level,
            PlacementBounds bounds,
            int targetGroundY) {
        return capture(
                new ServerWorldView(level),
                bounds,
                targetGroundY);
    }

    static TerrainSnapshot capture(
            WorldView world,
            PlacementBounds bounds,
            int targetGroundY) {
        return capture(
                world,
                bounds,
                targetGroundY,
                TerrainSurfaceClassifier::isVegetation);
    }

    static TerrainSnapshot capture(
            WorldView world,
            PlacementBounds bounds,
            int targetGroundY,
            Predicate<BlockState> vegetationClassifier) {
        Objects.requireNonNull(
                vegetationClassifier,
                "vegetationClassifier");
        if (targetGroundY < world.minimumBuildHeight()
                || targetGroundY >= world.maximumBuildHeight()) {
            throw new PlacementPreparationException(
                    "Terrain contact plane is outside build height");
        }

        List<TerrainColumnSnapshot> columns = new ArrayList<>(
                Math.toIntExact(bounds.affectedColumnCount()));
        for (int x = bounds.minimumAffectedX(); ; x++) {
            for (int z = bounds.minimumAffectedZ(); ; z++) {
                if (bounds.containsHorizontal(x, z)) {
                    columns.add(captureColumn(
                            world,
                            x,
                            z,
                            targetGroundY,
                            vegetationClassifier));
                }
                if (z == bounds.maximumAffectedZ()) {
                    break;
                }
            }
            if (x == bounds.maximumAffectedX()) {
                break;
            }
        }
        return new TerrainSnapshot(
                bounds,
                world.minimumBuildHeight(),
                world.maximumBuildHeight(),
                targetGroundY,
                columns);
    }

    private static TerrainColumnSnapshot captureColumn(
            WorldView world,
            int x,
            int z,
            int targetGroundY,
            Predicate<BlockState> vegetationClassifier) {
        int terrainHeight = world.oceanFloorHeight(x, z);
        int surfaceHeight = world.worldSurfaceHeight(x, z);
        if (terrainHeight <= world.minimumBuildHeight()
                || terrainHeight > world.maximumBuildHeight()
                || surfaceHeight < terrainHeight
                || surfaceHeight > world.maximumBuildHeight()) {
            throw new PlacementPreparationException(
                    "Cannot snapshot invalid terrain heights at ["
                            + x + ", " + z + "]");
        }
        int groundY = TerrainSurfaceClassifier.findGroundY(
                world.minimumBuildHeight(),
                surfaceHeight,
                y -> world.blockState(x, y, z),
                vegetationClassifier);
        int surfaceY = surfaceHeight - 1;
        int minimumCapturedY = Math.max(
                world.minimumBuildHeight(),
                subtractClamped(
                        Math.min(groundY, targetGroundY),
                        MATERIAL_SAMPLE_DEPTH));
        int maximumCapturedYExclusive = Math.min(
                world.maximumBuildHeight(),
                Math.max(
                        surfaceHeight,
                        addClamped(targetGroundY, 1)));
        if (maximumCapturedYExclusive <= minimumCapturedY) {
            throw new PlacementPreparationException(
                    "Terrain snapshot range is empty at ["
                            + x + ", " + z + "]");
        }

        List<BlockState> states = new ArrayList<>(
                maximumCapturedYExclusive - minimumCapturedY);
        List<BlockPos> fluids = new ArrayList<>();
        List<BlockPos> vegetation = new ArrayList<>();
        for (int y = minimumCapturedY;
                y < maximumCapturedYExclusive;
                y++) {
            BlockState state = world.blockState(x, y, z);
            if (state == null) {
                throw new PlacementPreparationException(
                        "World returned a null block state at ["
                                + x + ", " + y + ", " + z + "]");
            }
            states.add(state);
            if (!state.getFluidState().isEmpty()) {
                fluids.add(new BlockPos(x, y, z));
            }
            if (y > groundY
                    && vegetationClassifier.test(state)) {
                vegetation.add(new BlockPos(x, y, z));
            }
        }

        BlockState surfaceMaterial =
                states.get(groundY - minimumCapturedY);
        if (!TerrainSurfaceClassifier.isTerrainMaterial(
                surfaceMaterial,
                vegetationClassifier)) {
            throw new PlacementPreparationException(
                    "Generated terrain has no solid surface material at ["
                            + x + ", " + groundY + ", " + z + "]");
        }
        BlockState fillerMaterial = findMaterial(
                states,
                minimumCapturedY,
                groundY - 1,
                groundY - MATERIAL_SAMPLE_DEPTH,
                surfaceMaterial,
                vegetationClassifier);
        BlockState deepMaterial = findMaterial(
                states,
                minimumCapturedY,
                groundY - DEEP_MATERIAL_OFFSET,
                minimumCapturedY,
                fillerMaterial,
                vegetationClassifier);
        return new TerrainColumnSnapshot(
                x,
                z,
                groundY,
                surfaceY,
                minimumCapturedY,
                states,
                surfaceMaterial,
                fillerMaterial,
                deepMaterial,
                fluids,
                vegetation);
    }

    private static BlockState findMaterial(
            List<BlockState> states,
            int minimumCapturedY,
            int startY,
            int endY,
            BlockState fallback,
            Predicate<BlockState> vegetationClassifier) {
        int minimumY = Math.max(minimumCapturedY, endY);
        for (int y = Math.max(startY, minimumY);
                y >= minimumY;
                y--) {
            BlockState state = states.get(y - minimumCapturedY);
            if (TerrainSurfaceClassifier.isTerrainMaterial(
                    state,
                    vegetationClassifier)) {
                return state;
            }
        }
        return fallback;
    }

    private static int subtractClamped(int value, int amount) {
        long result = (long) value - amount;
        return result < Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : (int) result;
    }

    private static int addClamped(int value, int amount) {
        long result = (long) value + amount;
        return result > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) result;
    }

    interface WorldView {
        int minimumBuildHeight();

        int maximumBuildHeight();

        int oceanFloorHeight(int x, int z);

        int worldSurfaceHeight(int x, int z);

        BlockState blockState(int x, int y, int z);
    }

    private record ServerWorldView(ServerLevel level)
            implements WorldView {
        @Override
        public int minimumBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public int maximumBuildHeight() {
            return level.getMaxBuildHeight();
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            return level.getHeight(
                    Heightmap.Types.OCEAN_FLOOR,
                    x,
                    z);
        }

        @Override
        public int worldSurfaceHeight(int x, int z) {
            return level.getHeight(
                    Heightmap.Types.WORLD_SURFACE,
                    x,
                    z);
        }

        @Override
        public BlockState blockState(int x, int y, int z) {
            return level.getBlockState(new BlockPos(x, y, z));
        }
    }
}
