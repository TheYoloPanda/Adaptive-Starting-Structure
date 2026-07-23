package com.typ.adaptivestartingstructure.placement;

import java.util.function.IntFunction;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

final class TerrainSurfaceClassifier {
    private TerrainSurfaceClassifier() {
    }

    static int findGroundY(
            int minimumBuildHeight,
            int surfaceHeight,
            IntFunction<BlockState> stateAtY) {
        return findGroundY(
                minimumBuildHeight,
                surfaceHeight,
                stateAtY,
                TerrainSurfaceClassifier::isVegetation);
    }

    static int findGroundY(
            int minimumBuildHeight,
            int surfaceHeight,
            IntFunction<BlockState> stateAtY,
            Predicate<BlockState> vegetationClassifier) {
        for (int y = surfaceHeight - 1;
                y >= minimumBuildHeight;
                y--) {
            BlockState state = stateAtY.apply(y);
            if (state == null) {
                throw new PlacementPreparationException(
                        "World returned a null block state while locating terrain");
            }
            if (isTerrainMaterial(state, vegetationClassifier)) {
                return y;
            }
        }
        throw new PlacementPreparationException(
                "Generated column contains no solid terrain surface");
    }

    static boolean isTerrainMaterial(
            BlockState state,
            Predicate<BlockState> vegetationClassifier) {
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && !vegetationClassifier.test(state);
    }

    static boolean isVegetation(BlockState state) {
        return isTreeLog(state)
                || isTreeLeaf(state)
                || isTreeAttachment(state)
                || isLightweightVegetation(state);
    }

    static boolean isTreeLog(BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            return true;
        }
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!id.getNamespace().equals("minecraft")) {
            return false;
        }
        String path = id.getPath();
        return path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_stem")
                || path.endsWith("_hyphae")
                || path.equals("bamboo_block")
                || path.equals("stripped_bamboo_block");
    }

    static boolean isTreeLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.getBlock() instanceof LeavesBlock;
    }

    static boolean isLightweightVegetation(BlockState state) {
        return !isTreeLog(state)
                && !isTreeLeaf(state)
                && !isTreeAttachment(state)
                && (state.is(BlockTags.REPLACEABLE_BY_TREES)
                        || state.getBlock() instanceof BushBlock);
    }

    static boolean isTreeAttachment(BlockState state) {
        return state.getBlock() instanceof VineBlock
                || state.getBlock() instanceof CocoaBlock
                || state.getBlock() instanceof BeehiveBlock;
    }
}
