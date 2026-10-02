package com.typ.adaptivestartingstructure.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class TerrainPlanApplier {
    private static final int PLACEMENT_FLAGS =
            Block.UPDATE_CLIENTS
                    | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS;

    private TerrainPlanApplier() {
    }

    static int apply(
            ServerLevel level,
            TerrainTransformationPlan plan) {
        return apply(
                new ServerWorldAccess(level),
                plan.writes());
    }

    static int apply(
            ServerLevel level,
            TerrainBlendPlan plan) {
        return apply(
                new ServerWorldAccess(level),
                plan.writes());
    }

    static int apply(
            ServerLevel level,
            Iterable<TerrainWrite> writes) {
        return apply(new ServerWorldAccess(level), writes);
    }

    static int apply(
            WorldAccess world,
            TerrainTransformationPlan plan) {
        return apply(world, plan.writes());
    }

    static int apply(
            WorldAccess world,
            TerrainBlendPlan plan) {
        return apply(world, plan.writes());
    }

    private static int apply(
            WorldAccess world,
            Iterable<TerrainWrite> writes) {
        for (TerrainWrite write : writes) {
            BlockState current =
                    world.blockState(write.position());
            if (!current.equals(write.originalState())) {
                throw new PlacementPreparationException(
                        "Terrain changed after snapshot at "
                                + write.position());
            }
        }

        int applied = 0;
        for (TerrainWrite write : writes) {
            /*
             * Minecraft refuses a write that changes nothing. A block an
             * earlier write of this pass took away, such as a vine whose
             * support went first, already holds its planned state; only a
             * refusal that leaves anything else fails the placement.
             */
            if (!world.setBlock(
                            write.position(),
                            write.targetState())
                    && !world.blockState(write.position())
                            .equals(write.targetState())) {
                throw new PlacementPreparationException(
                        "World rejected terrain write at "
                                + write.position()
                                + ": planned " + write.originalState()
                                + " -> " + write.targetState()
                                + ", found " + world.blockState(write.position()));
            }
            applied++;
        }
        return applied;
    }

    interface WorldAccess {
        BlockState blockState(BlockPos position);

        boolean setBlock(
                BlockPos position,
                BlockState targetState);
    }

    private record ServerWorldAccess(ServerLevel level)
            implements WorldAccess {
        @Override
        public BlockState blockState(BlockPos position) {
            return level.getBlockState(position);
        }

        @Override
        public boolean setBlock(
                BlockPos position,
                BlockState targetState) {
            return level.setBlock(
                    position,
                    targetState,
                    PLACEMENT_FLAGS);
        }
    }
}
