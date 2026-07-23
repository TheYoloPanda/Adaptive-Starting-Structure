package com.typ.adaptivestartingstructure.placement;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

final class WorldUpdateApplier {
    private static final long LIGHT_WAIT_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(30);
    private static final int SHAPE_UPDATE_FLAGS =
            Block.UPDATE_CLIENTS
                    | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS;

    private WorldUpdateApplier() {
    }

    static AppliedWorldUpdates apply(
            ServerLevel level,
            WorldUpdatePlan plan) {
        return apply(new ServerWorldAccess(level), plan);
    }

    static AppliedWorldUpdates apply(
            WorldAccess world,
            WorldUpdatePlan plan) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(plan, "plan");

        int shapeCorrections = 0;
        int neighborUpdates = 0;
        for (BlockPos position
                : plan.blockUpdatePositions()) {
            requireLoaded(world, position);
            if (world.reconcileShape(position)) {
                shapeCorrections++;
            }
            world.updateNeighbors(position);
            neighborUpdates++;
        }

        int comparatorUpdates = 0;
        for (BlockPos position
                : plan.blockEntityPositions()) {
            requireLoaded(world, position);
            world.updateComparator(position);
            comparatorUpdates++;
        }

        int lightChecks = 0;
        for (BlockPos position
                : plan.blockUpdatePositions()) {
            requireLoaded(world, position);
            world.checkLight(position);
            lightChecks++;
        }
        world.awaitLightUpdates(plan.modifiedChunks());

        Set<BlockPos> fluidTicks =
                collectFinalFluidTicks(world, plan);
        for (BlockPos position : fluidTicks) {
            world.scheduleFluidTick(position);
        }

        return new AppliedWorldUpdates(
                shapeCorrections,
                lightChecks,
                neighborUpdates,
                comparatorUpdates,
                fluidTicks.size());
    }

    private static Set<BlockPos> collectFinalFluidTicks(
            WorldAccess world,
            WorldUpdatePlan plan) {
        LinkedHashSet<BlockPos> candidates =
                new LinkedHashSet<>();
        for (BlockPos seed : plan.fluidSeedPositions()) {
            candidates.add(seed);
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = seed.relative(direction);
                if (plan.bounds().containsHorizontal(
                                adjacent.getX(),
                                adjacent.getZ())
                        && !world.isOutsideBuildHeight(adjacent)) {
                    candidates.add(adjacent);
                }
            }
        }

        LinkedHashSet<BlockPos> fluidTicks =
                new LinkedHashSet<>();
        for (BlockPos candidate : candidates) {
            requireLoaded(world, candidate);
            if (!world.fluidState(candidate).isEmpty()) {
                fluidTicks.add(candidate);
            }
        }
        return Set.copyOf(fluidTicks);
    }

    private static void requireLoaded(
            WorldAccess world,
            BlockPos position) {
        if (!world.isLoaded(position)) {
            throw new PlacementPreparationException(
                    "Final world update reached an unloaded chunk at "
                            + position);
        }
    }

    interface WorldAccess {
        boolean isLoaded(BlockPos position);

        boolean isOutsideBuildHeight(BlockPos position);

        boolean reconcileShape(BlockPos position);

        void updateNeighbors(BlockPos position);

        void updateComparator(BlockPos position);

        void checkLight(BlockPos position);

        void awaitLightUpdates(Set<ChunkPos> chunks);

        FluidState fluidState(BlockPos position);

        void scheduleFluidTick(BlockPos position);
    }

    private record ServerWorldAccess(ServerLevel level)
            implements WorldAccess {
        private ServerWorldAccess {
            Objects.requireNonNull(level, "level");
        }

        @Override
        public boolean isLoaded(BlockPos position) {
            return level.isLoaded(position);
        }

        @Override
        public boolean isOutsideBuildHeight(
                BlockPos position) {
            return level.isOutsideBuildHeight(position);
        }

        @Override
        public boolean reconcileShape(BlockPos position) {
            BlockState original =
                    level.getBlockState(position);
            BlockState corrected =
                    Block.updateFromNeighbourShapes(
                            original,
                            level,
                            position);
            if (original.equals(corrected)) {
                return false;
            }
            if (!level.setBlock(
                    position,
                    corrected,
                    SHAPE_UPDATE_FLAGS)) {
                throw new PlacementPreparationException(
                        "Failed to reconcile final block shape at "
                                + position);
            }
            return true;
        }

        @Override
        public void updateNeighbors(BlockPos position) {
            level.blockUpdated(
                    position,
                    level.getBlockState(position).getBlock());
        }

        @Override
        public void updateComparator(BlockPos position) {
            BlockState state =
                    level.getBlockState(position);
            if (!state.isAir()) {
                level.updateNeighbourForOutputSignal(
                        position,
                        state.getBlock());
            }
        }

        @Override
        public void checkLight(BlockPos position) {
            level.getChunkSource()
                    .getLightEngine()
                    .checkBlock(position);
        }

        @Override
        public void awaitLightUpdates(Set<ChunkPos> chunks) {
            if (chunks.isEmpty()) {
                return;
            }
            var chunkSource = level.getChunkSource();
            var lightEngine = chunkSource.getLightEngine();
            CompletableFuture<?> completion =
                    CompletableFuture.allOf(
                            chunks.stream()
                                    .map(chunk -> lightEngine
                                            .waitForPendingTasks(
                                                    chunk.x,
                                                    chunk.z))
                                    .toArray(
                                            CompletableFuture[]::new));
            long start = System.nanoTime();
            while (!completion.isDone()) {
                if (!chunkSource.pollTask()) {
                    LockSupport.parkNanos(
                            TimeUnit.MICROSECONDS.toNanos(100));
                }
                if (System.nanoTime() - start
                        >= LIGHT_WAIT_TIMEOUT_NANOS) {
                    throw new PlacementPreparationException(
                            "Timed out while waiting for bounded final light updates");
                }
            }
            try {
                completion.join();
            } catch (RuntimeException exception) {
                throw new PlacementPreparationException(
                        "Final light updates failed",
                        exception);
            }
        }

        @Override
        public FluidState fluidState(BlockPos position) {
            return level.getFluidState(position);
        }

        @Override
        public void scheduleFluidTick(BlockPos position) {
            FluidState state =
                    level.getFluidState(position);
            if (state.isEmpty()) {
                throw new PlacementPreparationException(
                        "Final fluid update lost its fluid at "
                                + position);
            }
            var fluid = state.getType();
            level.scheduleTick(
                    position,
                    fluid,
                    fluid.getTickDelay(level));
        }
    }
}
