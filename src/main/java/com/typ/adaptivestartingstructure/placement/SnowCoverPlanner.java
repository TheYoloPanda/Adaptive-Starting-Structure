package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Puts back the snow worldgen would have placed on the columns the mod
 * reshapes or clears around the structure.
 *
 * <p>Blending clears the snow wherever the ground moves, and tree cleanup
 * uncovers ground that lay under a canopy, where worldgen never put any. In a
 * cold biome both read as a bare band around the structure. The rule is the
 * one {@code SnowAndFreezeFeature} applies: a layer on top of the
 * motion-blocking column where the biome is cold enough at that height, the
 * top face is full and the block light stays under 10. No ice: open water is
 * left as it is.
 */
final class SnowCoverPlanner {
    /* Snow forms only where the block light is below this. */
    private static final int SNOW_LIGHT_LIMIT = 10;

    interface Climate {
        boolean coldEnoughToSnow(BlockPos position);
    }

    record LightSource(BlockPos position, int emission) {
    }

    private SnowCoverPlanner() {
    }

    /**
     * Adds the snow layers to {@code writes}, replacing a write that would
     * leave air where the snow goes.
     */
    static void cover(
            Map<BlockPos, TerrainWrite> writes,
            Collection<TerrainColumnSnapshot> columns,
            List<LightSource> lightSources,
            Climate climate) {
        for (TerrainColumnSnapshot column : columns) {
            int topY = topBlockingY(column, writes);
            if (topY < column.minimumCapturedY()) {
                continue;
            }
            BlockPos top = new BlockPos(column.x(), topY, column.z());
            BlockPos snow = top.above();
            BlockState topState = finalState(column, writes, top);
            if (!finalState(column, writes, snow).isAir()
                    || !holdsSnow(topState)
                    || lit(snow, lightSources)
                    || !climate.coldEnoughToSnow(snow)) {
                continue;
            }
            setFinalState(
                    writes,
                    column,
                    snow,
                    Blocks.SNOW.defaultBlockState());
            if (topState.hasProperty(BlockStateProperties.SNOWY)
                    && !topState.getValue(BlockStateProperties.SNOWY)) {
                setFinalState(
                        writes,
                        column,
                        top,
                        topState.setValue(BlockStateProperties.SNOWY, true));
            }
        }
    }

    /*
     * Light is only computed after the writes, so it is estimated from the
     * template's own light sources, as emission minus distance with nothing
     * in the way. That overestimates it, which never places snow the light
     * would then melt.
     */
    static List<LightSource> lightSources(
            RotatedStructureView structure,
            BlockPos origin) {
        List<LightSource> sources = new ArrayList<>();
        for (StructureElement element : structure.placementElements()) {
            int emission = 0;
            for (BlockState state : element.paletteStates()) {
                emission = Math.max(
                        emission,
                        state.getLightEmission(
                                EmptyBlockGetter.INSTANCE,
                                BlockPos.ZERO));
            }
            if (emission >= SNOW_LIGHT_LIMIT) {
                sources.add(new LightSource(
                        origin.offset(element.position()),
                        emission));
            }
        }
        return List.copyOf(sources);
    }

    private static boolean lit(
            BlockPos position,
            List<LightSource> lightSources) {
        for (LightSource source : lightSources) {
            if (source.emission() - position.distManhattan(source.position())
                    >= SNOW_LIGHT_LIMIT) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsSnow(BlockState state) {
        if (state.is(BlockTags.SNOW_LAYER_CANNOT_SURVIVE_ON)) {
            return false;
        }
        return state.is(BlockTags.SNOW_LAYER_CAN_SURVIVE_ON)
                || Block.isFaceFull(
                        state.getCollisionShape(
                                EmptyBlockGetter.INSTANCE,
                                BlockPos.ZERO),
                        Direction.UP);
    }

    private static int topBlockingY(
            TerrainColumnSnapshot column,
            Map<BlockPos, TerrainWrite> writes) {
        for (int y = column.maximumCapturedYExclusive() - 1;
                y >= column.minimumCapturedY();
                y--) {
            BlockState state = finalState(
                    column,
                    writes,
                    new BlockPos(column.x(), y, column.z()));
            if (Heightmap.Types.MOTION_BLOCKING.isOpaque().test(state)) {
                return y;
            }
        }
        return column.minimumCapturedY() - 1;
    }

    private static BlockState finalState(
            TerrainColumnSnapshot column,
            Map<BlockPos, TerrainWrite> writes,
            BlockPos position) {
        TerrainWrite write = writes.get(position);
        return write != null
                ? write.targetState()
                : originalState(column, position);
    }

    /*
     * The capture reaches the world-surface height, so everything above it
     * is air.
     */
    private static BlockState originalState(
            TerrainColumnSnapshot column,
            BlockPos position) {
        return position.getY() < column.maximumCapturedYExclusive()
                ? column.stateAt(position.getY())
                : Blocks.AIR.defaultBlockState();
    }

    private static void setFinalState(
            Map<BlockPos, TerrainWrite> writes,
            TerrainColumnSnapshot column,
            BlockPos position,
            BlockState target) {
        TerrainWrite existing = writes.get(position);
        BlockState original = existing != null
                ? existing.originalState()
                : originalState(column, position);
        if (original.equals(target)) {
            writes.remove(position);
            return;
        }
        writes.put(position, new TerrainWrite(
                position,
                original,
                target,
                TerrainWrite.Kind.SNOW_COVER));
    }
}
