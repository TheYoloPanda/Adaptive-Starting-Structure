package com.typ.adaptivestartingstructure.planner;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

public final class GeneratorPlannerQuerySource implements PlannerWorldQuerySource {
    private final LevelHeightAccessor heightAccessor;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final Climate.Sampler climateSampler;
    private final WorldBorder worldBorder;

    private GeneratorPlannerQuerySource(
            LevelHeightAccessor heightAccessor,
            ChunkGenerator generator,
            RandomState randomState,
            BiomeSource biomeSource,
            Climate.Sampler climateSampler,
            WorldBorder worldBorder) {
        this.heightAccessor = Objects.requireNonNull(heightAccessor, "heightAccessor");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.randomState = Objects.requireNonNull(randomState, "randomState");
        this.biomeSource = Objects.requireNonNull(biomeSource, "biomeSource");
        this.climateSampler = Objects.requireNonNull(climateSampler, "climateSampler");
        this.worldBorder = Objects.requireNonNull(worldBorder, "worldBorder");
    }

    public static GeneratorPlannerQuerySource from(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        return new GeneratorPlannerQuerySource(
                level,
                generator,
                randomState,
                generator.getBiomeSource(),
                randomState.sampler(),
                level.getWorldBorder());
    }

    @Override
    public BlockPos suggestedSpawnOrigin() {
        return centerOfVanillaSpawnChunk(climateSampler.findSpawnPosition());
    }

    @Override
    public int baseHeight(int x, int z, Heightmap.Types heightmapType) {
        return generator.getBaseHeight(x, z, heightmapType, heightAccessor, randomState);
    }

    @Override
    public TerrainColumn baseColumn(int x, int z) {
        NoiseColumn column = generator.getBaseColumn(x, z, heightAccessor, randomState);
        return TerrainColumn.copyOf(
                column,
                heightAccessor.getMinBuildHeight(),
                heightAccessor.getMaxBuildHeight());
    }

    @Override
    public BiomeSample biomeAtQuart(int quartX, int quartY, int quartZ) {
        Holder<Biome> biome = biomeSource.getNoiseBiome(
                quartX,
                quartY,
                quartZ,
                climateSampler);
        return BiomeSample.fromHolder(biome);
    }

    @Override
    public boolean isWithinWorldBorder(int x, int z) {
        return worldBorder.isWithinBounds(x, z);
    }

    @Override
    public int minBuildHeight() {
        return heightAccessor.getMinBuildHeight();
    }

    @Override
    public int maxBuildHeight() {
        return heightAccessor.getMaxBuildHeight();
    }

    static BlockPos centerOfVanillaSpawnChunk(BlockPos samplerPosition) {
        ChunkPos chunkPosition = new ChunkPos(samplerPosition);
        return chunkPosition.getMiddleBlockPosition(0);
    }
}
