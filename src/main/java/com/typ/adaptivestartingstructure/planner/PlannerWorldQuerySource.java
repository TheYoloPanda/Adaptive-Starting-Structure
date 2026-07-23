package com.typ.adaptivestartingstructure.planner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

public interface PlannerWorldQuerySource {
    BlockPos suggestedSpawnOrigin();

    int baseHeight(int x, int z, Heightmap.Types heightmapType);

    TerrainColumn baseColumn(int x, int z);

    BiomeSample biomeAtQuart(int quartX, int quartY, int quartZ);

    boolean isWithinWorldBorder(int x, int z);

    int minBuildHeight();

    int maxBuildHeight();
}
