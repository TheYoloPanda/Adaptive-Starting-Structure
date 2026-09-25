package com.typ.adaptivestartingstructure.planner;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

public interface PlannerWorldQuerySource {
    BlockPos suggestedSpawnOrigin();

    int baseHeight(int x, int z, Heightmap.Types heightmapType);

    TerrainColumn baseColumn(int x, int z);

    /**
     * Both worldgen surfaces at one position. A source that can read them in
     * one pass over the column should; asking for each is the fallback.
     */
    default SurfaceHeights surfaceHeights(int x, int z) {
        return new SurfaceHeights(
                baseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG),
                baseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG),
                Optional.empty());
    }

    BiomeSample biomeAtQuart(int quartX, int quartY, int quartZ);

    boolean isWithinWorldBorder(int x, int z);

    /**
     * Whether a generated structure may start inside the given chunk box.
     *
     * <p>This is a seed-and-salt calculation over structure placements and
     * generates no chunks, which is the point: the flattest ground a search
     * can find is usually a village, and finding that out only after the area
     * has been generated in full costs seconds of worldgen per retry and can
     * move the world spawn after chunks already exist.
     *
     * <p>It is deliberately conservative. It answers for grid-placed
     * structures only, and does not check whether the structure's own biome
     * conditions would actually let it generate there, so a true answer means
     * "possible", never "certain". Callers treat it as a preference, not a
     * verdict. A source with no structure information answers false.
     */
    default boolean mayContainStructureStart(
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ) {
        return false;
    }

    int minBuildHeight();

    int maxBuildHeight();
}
