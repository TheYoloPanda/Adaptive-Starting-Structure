package com.typ.adaptivestartingstructure.placement;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

final class HeightmapConsistencyValidator {
    static final Set<Heightmap.Types> TRACKED_TYPES =
            Set.copyOf(EnumSet.of(
                    Heightmap.Types.WORLD_SURFACE,
                    Heightmap.Types.OCEAN_FLOOR,
                    Heightmap.Types.MOTION_BLOCKING,
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));

    private HeightmapConsistencyValidator() {
    }

    static HeightmapVerification verify(
            ServerLevel level,
            Set<Long> columns) {
        return verify(new ServerWorldView(level), columns);
    }

    static HeightmapVerification verify(
            WorldView world,
            Set<Long> columns) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(columns, "columns");
        for (long packed : columns) {
            int x = (int) (packed >> 32);
            int z = (int) packed;
            verifyColumn(world, x, z);
        }
        return new HeightmapVerification(
                columns.size(),
                Math.multiplyExact(
                        columns.size(),
                        TRACKED_TYPES.size()));
    }

    private static void verifyColumn(
            WorldView world,
            int x,
            int z) {
        EnumMap<Heightmap.Types, Integer> expected =
                new EnumMap<>(Heightmap.Types.class);
        for (int y = world.maximumBuildHeight() - 1;
                y >= world.minimumBuildHeight()
                        && expected.size()
                                < TRACKED_TYPES.size();
                y--) {
            BlockState state = world.blockState(x, y, z);
            for (Heightmap.Types type : TRACKED_TYPES) {
                if (!expected.containsKey(type)
                        && type.isOpaque().test(state)) {
                    expected.put(type, y + 1);
                }
            }
        }

        for (Heightmap.Types type : TRACKED_TYPES) {
            int expectedHeight = expected.getOrDefault(
                    type,
                    world.minimumBuildHeight());
            int actualHeight =
                    world.height(type, x, z);
            if (actualHeight != expectedHeight) {
                throw new PlacementPreparationException(
                        "Heightmap "
                                + type.getSerializationKey()
                                + " is inconsistent at ["
                                + x
                                + ", "
                                + z
                                + "]: expected "
                                + expectedHeight
                                + ", found "
                                + actualHeight);
            }
        }
    }

    interface WorldView {
        int minimumBuildHeight();

        int maximumBuildHeight();

        BlockState blockState(int x, int y, int z);

        int height(Heightmap.Types type, int x, int z);
    }

    private record ServerWorldView(ServerLevel level)
            implements WorldView {
        private ServerWorldView {
            Objects.requireNonNull(level, "level");
        }

        @Override
        public int minimumBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public int maximumBuildHeight() {
            return level.getMaxBuildHeight();
        }

        @Override
        public BlockState blockState(int x, int y, int z) {
            return level.getBlockState(
                    new BlockPos(x, y, z));
        }

        @Override
        public int height(
                Heightmap.Types type,
                int x,
                int z) {
            return level.getHeight(type, x, z);
        }
    }
}
