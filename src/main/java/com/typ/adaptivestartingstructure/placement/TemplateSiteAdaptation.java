package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import com.typ.adaptivestartingstructure.structure.StructureElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Fits the template's own landscape to the site it lands on.
 *
 * <p>A template carries its ground with it, so its grass would sit as a green
 * patch in a desert or a badlands, while the terrain the mod writes around it
 * already takes each column's own materials. The material comes from the
 * terrain found rather than from the biome: Minecraft has no surface block per
 * biome, only a noise-driven rule that runs while chunks generate. In a cold
 * biome the template's open tops also get the snow the land around them has.
 */
final class TemplateSiteAdaptation {
    /*
     * Below this share the site has no surface of its own, such as on a
     * biome border, and the template keeps the grass it was drawn with.
     */
    private static final double MINIMUM_SURFACE_SHARE = 0.5D;
    /* What the overworld surface rule puts on a ceiling in place of a block that would fall. */
    private static final Map<Block, Block> CEILINGS = Map.of(
            Blocks.SAND, Blocks.SANDSTONE,
            Blocks.RED_SAND, Blocks.RED_SANDSTONE,
            Blocks.GRAVEL, Blocks.STONE);
    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt((BlockPos position) -> position.getX())
                    .thenComparingInt(BlockPos::getZ)
                    .thenComparingInt(BlockPos::getY);

    private TemplateSiteAdaptation() {
    }

    record Site(SiteMaterials materials, SnowCoverPlanner.Climate climate) {
        static final Site AS_AUTHORED =
                new Site(SiteMaterials.NONE, position -> false);
    }

    record SiteMaterials(
            Block surface,
            Block filler,
            int surfaceColumns,
            int columns) {
        static final SiteMaterials NONE =
                new SiteMaterials(Blocks.GRASS_BLOCK, Blocks.DIRT, 0, 0);

        boolean replacesGrass() {
            return surface != Blocks.GRASS_BLOCK
                    && surfaceColumns >= MINIMUM_SURFACE_SHARE * columns
                    && isSolidGround(surface)
                    && isSolidGround(filler);
        }
    }

    record Summary(
            SiteMaterials materials,
            int grass,
            int dirt,
            int ceilings,
            int keptStacks,
            int plants,
            int snowLayers) {
        String describe() {
            String surface = BuiltInRegistries.BLOCK.getKey(
                    materials.surface()).toString();
            String share = materials.columns() == 0
                    ? "no columns"
                    : Math.round(100.0D * materials.surfaceColumns()
                                    / materials.columns())
                            + "% of " + materials.columns() + " columns";
            String ground = materials.replacesGrass()
                    ? "surface " + surface + " (" + share + "), filler "
                            + BuiltInRegistries.BLOCK.getKey(materials.filler())
                            + ": " + grass + " grass and " + dirt
                            + " dirt blocks replaced, " + ceilings
                            + " stacks ending on a ceiling block, "
                            + keptStacks + " stacks kept as authored, "
                            + plants + " plants removed"
                    : "grass kept, the most common surface being "
                            + surface + " (" + share + ")";
            return ground + "; " + snowLayers + " snow layers";
        }
    }

    /** The most common surface and filler over the footprint and the blend ring. */
    static SiteMaterials materials(
            TerrainSnapshot snapshot,
            PlacementBounds bounds) {
        Map<Block, Integer> surfaces = new HashMap<>();
        Map<Block, Map<Block, Integer>> fillers = new HashMap<>();
        int columns = 0;
        for (TerrainColumnSnapshot column : snapshot.columns()) {
            if (!bounds.containsHorizontal(column.x(), column.z())) {
                continue;
            }
            columns++;
            Block surface = column.surfaceMaterial().getBlock();
            surfaces.merge(surface, 1, Integer::sum);
            fillers.computeIfAbsent(surface, ignored -> new HashMap<>())
                    .merge(column.fillerMaterial().getBlock(), 1, Integer::sum);
        }
        if (columns == 0) {
            return SiteMaterials.NONE;
        }
        Block surface = mostFrequent(surfaces);
        return new SiteMaterials(
                surface,
                mostFrequent(fillers.get(surface)),
                surfaces.get(surface),
                columns);
    }

    static Summary adapt(
            Map<BlockPos, TemplateBlockWrite> writes,
            TemplatePlacementPlanner.BlockStateReader world,
            RotatedStructureView structure,
            BlockPos origin,
            StructureBounds box,
            Site site) {
        Writes plan = new Writes(writes, world);
        Set<BlockPos> templateBlocks = new HashSet<>();
        Set<Long> templateColumns = new LinkedHashSet<>();
        for (StructureElement element : structure.placementElements()) {
            BlockPos position = origin.offset(element.position());
            templateColumns.add(TerrainSnapshot.pack(
                    position.getX(),
                    position.getZ()));
            if (element.kind() == StructureElement.Kind.BLOCK) {
                templateBlocks.add(position);
            }
        }

        int grass = 0;
        int dirt = 0;
        int ceilings = 0;
        int keptStacks = 0;
        int plants = 0;
        SiteMaterials materials = site.materials();
        if (materials.replacesGrass()) {
            List<BlockPos> grassBlocks = new ArrayList<>();
            for (BlockPos position : templateBlocks) {
                if (plan.finalState(position).is(Blocks.GRASS_BLOCK)) {
                    grassBlocks.add(position);
                }
            }
            grassBlocks.sort(POSITION_ORDER);
            for (BlockPos top : grassBlocks) {
                Map<BlockPos, BlockState> stack = new LinkedHashMap<>();
                stack.put(top, materials.surface().defaultBlockState());
                BlockPos below = top.below();
                while (templateBlocks.contains(below)
                        && plan.finalState(below).is(Blocks.DIRT)) {
                    stack.put(below, materials.filler().defaultBlockState());
                    below = below.below();
                }
                BlockPos lowest = below.above();
                BlockState lowestState = stack.get(lowest);
                if (lowestState.getBlock() instanceof FallingBlock
                        && FallingBlock.isFree(plan.finalState(below))) {
                    Block ceiling = CEILINGS.get(lowestState.getBlock());
                    if (ceiling == null) {
                        keptStacks++;
                        continue;
                    }
                    stack.put(lowest, ceiling.defaultBlockState());
                    ceilings++;
                }
                stack.forEach(plan::set);
                grass++;
                dirt += stack.size() - 1;
                plants += removeUnheldPlant(
                        plan,
                        top.above(),
                        stack.get(top),
                        box);
            }
        }

        int snowLayers = 0;
        List<SnowCoverPlanner.LightSource> lights =
                SnowCoverPlanner.lightSources(structure, origin);
        for (long column : templateColumns) {
            if (cover(plan, column, box, lights, site.climate())) {
                snowLayers++;
            }
        }
        return new Summary(
                materials,
                grass,
                dirt,
                ceilings,
                keptStacks,
                plants,
                snowLayers);
    }

    /*
     * A plant on the template's grass needs the dirt or farmland that bushes
     * root in; left on sand it would turn to air during the final shape pass,
     * where nothing counts it.
     */
    private static int removeUnheldPlant(
            Writes plan,
            BlockPos position,
            BlockState surface,
            StructureBounds box) {
        BlockState plant = plan.finalState(position);
        if (!box.contains(position)
                || !(plant.getBlock() instanceof BushBlock)
                || !plant.getFluidState().isEmpty()
                || surface.is(BlockTags.DIRT)
                || surface.is(Blocks.FARMLAND)) {
            return 0;
        }
        plan.set(position, Blocks.AIR.defaultBlockState());
        BlockPos upper = position.above();
        if (plant.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && box.contains(upper)
                && plan.finalState(upper).is(plant.getBlock())) {
            plan.set(upper, Blocks.AIR.defaultBlockState());
        }
        return 1;
    }

    private static boolean cover(
            Writes plan,
            long packedColumn,
            StructureBounds box,
            List<SnowCoverPlanner.LightSource> lights,
            SnowCoverPlanner.Climate climate) {
        int x = (int) (packedColumn >> 32);
        int z = (int) packedColumn;
        int maximumY = box.maximum().getY();
        if (!plan.finalState(new BlockPos(x, maximumY + 1, z)).isAir()) {
            return false;
        }
        for (int y = maximumY; y >= box.minimum().getY(); y--) {
            BlockPos top = new BlockPos(x, y, z);
            BlockState topState = plan.finalState(top);
            if (!SnowCoverPlanner.blocksMotion(topState)) {
                continue;
            }
            BlockPos snow = top.above();
            if (y == maximumY
                    || !plan.finalState(snow).isAir()
                    || !SnowCoverPlanner.holdsSnow(topState)
                    || SnowCoverPlanner.lit(snow, lights)
                    || !climate.coldEnoughToSnow(snow)) {
                return false;
            }
            plan.set(
                    snow,
                    Blocks.SNOW.defaultBlockState(),
                    TemplateBlockWrite.Kind.SNOW_COVER);
            if (topState.hasProperty(BlockStateProperties.SNOWY)
                    && !topState.getValue(BlockStateProperties.SNOWY)) {
                plan.set(
                        top,
                        topState.setValue(BlockStateProperties.SNOWY, true));
            }
            return true;
        }
        return false;
    }

    private static boolean isSolidGround(Block block) {
        return block.defaultBlockState().isCollisionShapeFullBlock(
                EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO);
    }

    private static Block mostFrequent(Map<Block, Integer> counts) {
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<Block, Integer>>comparingInt(
                                Map.Entry::getValue)
                        .thenComparing(entry -> BuiltInRegistries.BLOCK
                                .getKey(entry.getKey())
                                .toString(),
                                Comparator.reverseOrder()))
                .orElseThrow()
                .getKey();
    }

    /* The template's writes as they will land, over the world they land on. */
    private record Writes(
            Map<BlockPos, TemplateBlockWrite> writes,
            TemplatePlacementPlanner.BlockStateReader world) {
        BlockState finalState(BlockPos position) {
            TemplateBlockWrite write = writes.get(position);
            return write != null
                    ? write.targetState()
                    : world.blockState(position);
        }

        void set(BlockPos position, BlockState target) {
            TemplateBlockWrite existing = writes.get(position);
            set(
                    position,
                    target,
                    existing != null
                            ? existing.kind()
                            : TemplateBlockWrite.Kind.BLOCK);
        }

        void set(
                BlockPos position,
                BlockState target,
                TemplateBlockWrite.Kind kind) {
            TemplateBlockWrite existing = writes.get(position);
            BlockState original = existing != null
                    ? existing.originalState()
                    : world.blockState(position);
            if (original.equals(target)) {
                writes.remove(position);
                return;
            }
            writes.put(position, new TemplateBlockWrite(
                    position,
                    original,
                    target,
                    kind));
        }
    }
}
