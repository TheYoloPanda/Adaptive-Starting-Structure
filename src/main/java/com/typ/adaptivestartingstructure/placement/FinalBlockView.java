package com.typ.adaptivestartingstructure.placement;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The blocks a site will hold once its planned writes land: the world as it
 * is, with each write laid over it in the order placement applies them, so a
 * later write to a block wins as it does in the world.
 */
final class FinalBlockView implements BlockGetter {
    private final TemplatePlacementPlanner.BlockStateReader world;
    private final LevelHeightAccessor heights;
    private final Map<BlockPos, BlockState> written = new HashMap<>();

    FinalBlockView(
            TemplatePlacementPlanner.BlockStateReader world,
            LevelHeightAccessor heights) {
        this.world = Objects.requireNonNull(world, "world");
        this.heights = Objects.requireNonNull(heights, "heights");
    }

    void writeTerrain(Iterable<TerrainWrite> writes) {
        for (TerrainWrite write : writes) {
            written.put(write.position(), write.targetState());
        }
    }

    void writeTemplate(Iterable<TemplateBlockWrite> writes) {
        for (TemplateBlockWrite write : writes) {
            written.put(write.position(), write.targetState());
        }
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos position) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos position) {
        BlockState state = written.get(position);
        return state != null ? state : world.blockState(position);
    }

    @Override
    public FluidState getFluidState(BlockPos position) {
        return getBlockState(position).getFluidState();
    }

    @Override
    public int getHeight() {
        return heights.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return heights.getMinBuildHeight();
    }
}
