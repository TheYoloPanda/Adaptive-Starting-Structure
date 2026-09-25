package com.typ.adaptivestartingstructure.planner;

import java.util.Objects;
import java.util.Optional;
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
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

public final class GeneratorPlannerQuerySource implements PlannerWorldQuerySource {
    private final LevelHeightAccessor heightAccessor;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final Climate.Sampler climateSampler;
    private final WorldBorder worldBorder;
    private final ChunkGeneratorStructureState structureState;

    private GeneratorPlannerQuerySource(
            LevelHeightAccessor heightAccessor,
            ChunkGenerator generator,
            RandomState randomState,
            BiomeSource biomeSource,
            Climate.Sampler climateSampler,
            WorldBorder worldBorder,
            ChunkGeneratorStructureState structureState) {
        this.structureState = structureState;
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
                level.getWorldBorder(),
                chunkSource.getGeneratorState());
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

    /*
     * Each height query walks the noise column down from the top until its
     * heightmap matches, so the two surfaces cost two walks of the same column.
     * A whole column costs about as much as one of those walks and answers both.
     */
    @Override
    public SurfaceHeights surfaceHeights(int x, int z) {
        TerrainColumn column = baseColumn(x, z);
        return new SurfaceHeights(
                column.surfaceHeight(Heightmap.Types.OCEAN_FLOOR_WG),
                column.surfaceHeight(Heightmap.Types.WORLD_SURFACE_WG),
                Optional.of(column));
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
    public boolean mayContainStructureStart(
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ) {
        if (structureState == null) {
            return false;
        }
        long seed = structureState.getLevelSeed();
        for (Holder<StructureSet> set : structureState.possibleStructureSets()) {
            /*
             * Only grid placements are answered. The alternative is concentric
             * rings, whose positions exist just once per world and are built by
             * a pass that samples biomes across thousands of blocks; forcing it
             * here would cost more than the filter saves, and what it places is
             * strongholds, which sit far below anything this mod levels.
             */
            if (!(set.value().placement()
                    instanceof RandomSpreadStructurePlacement placement)) {
                continue;
            }
            if (containsPotentialStart(
                    placement,
                    seed,
                    minChunkX,
                    minChunkZ,
                    maxChunkX,
                    maxChunkZ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this grid placement's candidate chunk for any cell overlapping
     * the box falls inside it.
     *
     * <p>A grid placement offers one candidate chunk per cell of {@code
     * spacing} chunks, and the box an area of this size spans only a cell or
     * two, so asking the cells is a handful of checks where testing every
     * chunk in the box against every structure set would be thousands.
     */
    static boolean containsPotentialStart(
            RandomSpreadStructurePlacement placement,
            long seed,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ) {
        int spacing = placement.spacing();
        if (spacing <= 0) {
            return false;
        }
        int firstCellX = Math.floorDiv(minChunkX, spacing);
        int lastCellX = Math.floorDiv(maxChunkX, spacing);
        int firstCellZ = Math.floorDiv(minChunkZ, spacing);
        int lastCellZ = Math.floorDiv(maxChunkZ, spacing);
        for (int cellX = firstCellX; cellX <= lastCellX; cellX++) {
            for (int cellZ = firstCellZ; cellZ <= lastCellZ; cellZ++) {
                ChunkPos candidate = placement.getPotentialStructureChunk(
                        seed,
                        cellX * spacing,
                        cellZ * spacing);
                if (candidate.x < minChunkX || candidate.x > maxChunkX
                        || candidate.z < minChunkZ || candidate.z > maxChunkZ) {
                    continue;
                }
                if (placement.applyAdditionalChunkRestrictions(
                        candidate.x,
                        candidate.z,
                        seed)) {
                    return true;
                }
            }
        }
        return false;
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
