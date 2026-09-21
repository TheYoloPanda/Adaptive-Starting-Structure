package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

public final class PlannerQueryContext {
    private final PlannerWorldQuerySource source;
    private final BiomeClassifier biomeClassifier;
    private final int maximumQueries;
    private int phaseLimit;
    private final BlockPos suggestedSpawnOrigin;
    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final Map<HeightQuery, Integer> heightCache = new HashMap<>();
    private final Map<ColumnQuery, TerrainColumn> columnCache = new HashMap<>();
    private final Map<BiomeQuery, BiomeSample> biomeCache = new HashMap<>();
    private final Map<StructureQuery, Boolean> structureCache = new HashMap<>();
    private int queriesUsed;
    private int heightCalls;
    private int heightHits;
    private long heightNanos;
    private int columnCalls;
    private int columnHits;
    private long columnNanos;
    private int biomeCalls;
    private int biomeHits;
    private long biomeNanos;
    private int structureCalls;
    private int structureHits;
    private long structureNanos;

    public PlannerQueryContext(
            PlannerWorldQuerySource source,
            BiomeClassifier biomeClassifier,
            int maximumQueries) {
        this.source = Objects.requireNonNull(source, "source");
        this.biomeClassifier = Objects.requireNonNull(biomeClassifier, "biomeClassifier");
        if (maximumQueries <= 0) {
            throw new IllegalArgumentException("maximumQueries must be positive");
        }
        this.maximumQueries = maximumQueries;
        this.phaseLimit = maximumQueries;
        this.minBuildHeight = source.minBuildHeight();
        this.maxBuildHeight = source.maxBuildHeight();
        if (maxBuildHeight <= minBuildHeight) {
            throw new IllegalArgumentException(
                    "World build-height range must be positive");
        }
        consumeQuery("suggested spawn origin");
        this.suggestedSpawnOrigin = Objects.requireNonNull(
                        source.suggestedSpawnOrigin(),
                        "suggestedSpawnOrigin")
                .immutable();
    }

    public static PlannerQueryContext create(ServerLevel level, ConfigSnapshot config) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(config, "config");
        Registry<Biome> biomeRegistry =
                level.registryAccess().registryOrThrow(Registries.BIOME);
        return new PlannerQueryContext(
                GeneratorPlannerQuerySource.from(level),
                BiomeClassifier.resolve(config, biomeRegistry),
                config.maximumGeneratorQueries());
    }

    public BlockPos suggestedSpawnOrigin() {
        return suggestedSpawnOrigin;
    }

    public int baseHeight(int x, int z, Heightmap.Types heightmapType) {
        Objects.requireNonNull(heightmapType, "heightmapType");
        HeightQuery key = new HeightQuery(x, z, heightmapType);
        Integer cached = heightCache.get(key);
        if (cached != null) {
            heightHits++;
            return cached;
        }
        consumeQuery("base height at [" + x + ", " + z + "] using " + heightmapType);
        long startedAt = System.nanoTime();
        int height = source.baseHeight(x, z, heightmapType);
        heightNanos += elapsedSince(startedAt);
        heightCalls++;
        heightCache.put(key, height);
        return height;
    }

    public TerrainColumn baseColumn(int x, int z) {
        ColumnQuery key = new ColumnQuery(x, z);
        TerrainColumn cached = columnCache.get(key);
        if (cached != null) {
            columnHits++;
            return cached;
        }
        consumeQuery("base column at [" + x + ", " + z + "]");
        long startedAt = System.nanoTime();
        TerrainColumn column = Objects.requireNonNull(
                source.baseColumn(x, z),
                "baseColumn");
        columnNanos += elapsedSince(startedAt);
        columnCalls++;
        columnCache.put(key, column);
        return column;
    }

    public BiomeSample biomeAt(int blockX, int blockY, int blockZ) {
        BiomeQuery key = new BiomeQuery(
                QuartPos.fromBlock(blockX),
                QuartPos.fromBlock(blockY),
                QuartPos.fromBlock(blockZ));
        BiomeSample cached = biomeCache.get(key);
        if (cached != null) {
            biomeHits++;
            return cached;
        }
        consumeQuery("noise biome at quart ["
                + key.quartX + ", " + key.quartY + ", " + key.quartZ + "]");
        long startedAt = System.nanoTime();
        BiomeSample biome = Objects.requireNonNull(
                source.biomeAtQuart(key.quartX, key.quartY, key.quartZ),
                "biomeAtQuart");
        biomeNanos += elapsedSince(startedAt);
        biomeCalls++;
        biomeCache.put(key, biome);
        return biome;
    }

    public BiomeClassifier.Classification classifyBiomeAt(
            int blockX,
            int blockY,
            int blockZ) {
        return biomeClassifier.classify(biomeAt(blockX, blockY, blockZ));
    }

    public boolean isWithinWorldBorder(int x, int z) {
        return source.isWithinWorldBorder(x, z);
    }

    /**
     * Whether a generated structure may start inside the given chunk box.
     *
     * <p>Unlike the other queries this one reads no terrain: it is seed and
     * salt arithmetic over structure placements. It is therefore not charged
     * to the generator budget, which exists to bound how much of the world's
     * noise a planning run samples.
     */
    public boolean mayContainStructureStart(
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ) {
        StructureQuery key = new StructureQuery(
                minChunkX,
                minChunkZ,
                maxChunkX,
                maxChunkZ);
        Boolean cached = structureCache.get(key);
        if (cached != null) {
            structureHits++;
            return cached;
        }
        long startedAt = System.nanoTime();
        boolean present = source.mayContainStructureStart(
                minChunkX,
                minChunkZ,
                maxChunkX,
                maxChunkZ);
        structureNanos += elapsedSince(startedAt);
        structureCalls++;
        structureCache.put(key, present);
        return present;
    }

    public int structureCacheSize() {
        return structureCache.size();
    }

    public int minBuildHeight() {
        return minBuildHeight;
    }

    public int maxBuildHeight() {
        return maxBuildHeight;
    }

    public int maximumQueries() {
        return maximumQueries;
    }

    /**
     * Caps the phase that runs next at {@code phaseLimit} total queries,
     * keeping the rest of the budget available for the phases after it.
     * A planning phase that stops on its own limit can still hand its
     * partial result to the next one, which is what turns an exhausted
     * budget into a smaller plan instead of no plan at all.
     */
    public void limitNextPhase(int phaseLimit) {
        if (phaseLimit <= 0) {
            throw new IllegalArgumentException("phaseLimit must be positive");
        }
        this.phaseLimit = Math.min(maximumQueries, phaseLimit);
    }

    public int phaseLimit() {
        return phaseLimit;
    }

    public int queriesUsed() {
        return queriesUsed;
    }

    /** What has been asked of the world so far, by kind of question. */
    public QuerySnapshot snapshot() {
        return new QuerySnapshot(
                new QuerySnapshot.Count(heightCalls, heightHits, heightNanos),
                new QuerySnapshot.Count(columnCalls, columnHits, columnNanos),
                new QuerySnapshot.Count(biomeCalls, biomeHits, biomeNanos),
                new QuerySnapshot.Count(
                        structureCalls,
                        structureHits,
                        structureNanos));
    }

    private static long elapsedSince(long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }

    public int heightCacheSize() {
        return heightCache.size();
    }

    public int columnCacheSize() {
        return columnCache.size();
    }

    public int biomeCacheSize() {
        return biomeCache.size();
    }

    private void consumeQuery(String attemptedQuery) {
        if (queriesUsed >= phaseLimit) {
            throw new GeneratorQueryBudgetExceededException(
                    maximumQueries,
                    phaseLimit,
                    queriesUsed,
                    attemptedQuery);
        }
        queriesUsed++;
    }

    private record HeightQuery(int x, int z, Heightmap.Types heightmapType) {
    }

    private record ColumnQuery(int x, int z) {
    }

    private record BiomeQuery(int quartX, int quartY, int quartZ) {
    }

    private record StructureQuery(
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ) {
    }
}
