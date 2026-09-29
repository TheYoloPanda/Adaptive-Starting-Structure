package com.typ.adaptivestartingstructure.placement;

import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

final class TerrainSurfaceClassifier {
    /* The vanilla members of the dirt tag, for where tags are not bound. */
    private static final Set<String> VANILLA_DIRT = Set.of(
            "dirt",
            "coarse_dirt",
            "rooted_dirt",
            "grass_block",
            "podzol",
            "mycelium",
            "moss_block",
            "mud",
            "muddy_mangrove_roots");

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
        BlockState above = Blocks.AIR.defaultBlockState();
        for (int y = surfaceHeight - 1;
                y >= minimumBuildHeight;
                y--) {
            BlockState state = stateAtY.apply(y);
            if (state == null) {
                throw new PlacementPreparationException(
                        "World returned a null block state while locating terrain");
            }
            if (isTerrainMaterial(state, vegetationClassifier)
                    && !hangsFromTree(
                            state,
                            above,
                            y,
                            minimumBuildHeight,
                            stateAtY,
                            vegetationClassifier)) {
                return y;
            }
            above = state;
        }
        throw new UnsuitableGeneratedSiteException(
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
                || isLightweightVegetation(state)
                || state.getBlock() instanceof HugeMushroomBlock;
    }

    /*
     * A block with a trunk or a leaf above it and no terrain under it hangs
     * from the tree. Other mods hang cocoons and fruit there, which neither
     * the vanilla tags nor a list here can name; read as ground, a cocoon
     * twenty blocks up a jungle tree rejected a site that was flat. Dirt never
     * hangs: tree features turn the ground under a trunk into dirt, and a
     * trunk on a one-block roof over a cave still stands on that roof.
     */
    private static boolean hangsFromTree(
            BlockState state,
            BlockState above,
            int y,
            int minimumBuildHeight,
            IntFunction<BlockState> stateAtY,
            Predicate<BlockState> vegetationClassifier) {
        if (!(isTreeLog(above) || isTreeLeaf(above))
                || y <= minimumBuildHeight
                || isDirt(state)) {
            return false;
        }
        BlockState below = stateAtY.apply(y - 1);
        return below != null
                && !isTerrainMaterial(below, vegetationClassifier);
    }

    private static boolean isDirt(BlockState state) {
        if (state.is(BlockTags.DIRT)) {
            return true;
        }
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals("minecraft")
                && VANILLA_DIRT.contains(id.getPath());
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
                        || state.getBlock() instanceof BushBlock
                        || isPlantColumnOrCover(state));
    }

    /*
     * Plants that stack into columns, and snow lying on the surface: none of
     * them is terrain, and the planner never sees them, since it reads the
     * ground before anything grows on it.
     */
    private static boolean isPlantColumnOrCover(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BambooStalkBlock
                || block instanceof BambooSaplingBlock
                || block instanceof CactusBlock
                || block instanceof SugarCaneBlock
                || block instanceof SnowLayerBlock;
    }

    static boolean isTreeAttachment(BlockState state) {
        return state.getBlock() instanceof VineBlock
                || state.getBlock() instanceof CocoaBlock
                || state.getBlock() instanceof BeehiveBlock;
    }
}
