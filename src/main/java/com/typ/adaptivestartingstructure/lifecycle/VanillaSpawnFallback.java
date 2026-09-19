package com.typ.adaptivestartingstructure.lifecycle;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

public final class VanillaSpawnFallback {
    private static final int SPAWN_SEARCH_DIAMETER = 11;

    private VanillaSpawnFallback() {
    }

    public static BlockPos resolve(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return resolve(new LevelSpawnEnvironment(level));
    }

    static BlockPos resolve(SpawnEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.isDebugWorld()) {
            return BlockPos.ZERO.above(80);
        }

        ChunkPos initialChunk =
                environment.initialChunk();
        int spawnHeight = environment.spawnHeight();
        if (spawnHeight
                < environment.minBuildHeight()) {
            BlockPos chunkOrigin =
                    initialChunk.getWorldPosition();
            spawnHeight = environment.surfaceHeight(
                    chunkOrigin.getX() + 8,
                    chunkOrigin.getZ() + 8);
        }

        BlockPos fallback = initialChunk
                .getWorldPosition()
                .offset(8, spawnHeight, 8);
        int offsetX = 0;
        int offsetZ = 0;
        int directionX = 0;
        int directionZ = -1;
        for (int index = 0;
                index < Mth.square(SPAWN_SEARCH_DIAMETER);
                index++) {
            if (offsetX >= -5
                    && offsetX <= 5
                    && offsetZ >= -5
                    && offsetZ <= 5) {
                BlockPos safe =
                        environment.findSpawnInChunk(
                                new ChunkPos(
                                        initialChunk.x
                                                + offsetX,
                                        initialChunk.z
                                                + offsetZ));
                if (safe != null) {
                    return safe.immutable();
                }
            }

            if (offsetX == offsetZ
                    || offsetX < 0
                            && offsetX == -offsetZ
                    || offsetX > 0
                            && offsetX == 1 - offsetZ) {
                int previousX = directionX;
                directionX = -directionZ;
                directionZ = previousX;
            }
            offsetX += directionX;
            offsetZ += directionZ;
        }
        return fallback.immutable();
    }

    interface SpawnEnvironment {
        boolean isDebugWorld();

        ChunkPos initialChunk();

        int spawnHeight();

        int minBuildHeight();

        int surfaceHeight(int x, int z);

        BlockPos findSpawnInChunk(ChunkPos chunk);
    }

    public static void applySpawn(
            ServerLevel level,
            BlockPos spawn) {
        SpawnRelocation.apply(level, spawn);
    }

    public static void placeBonusChest(
            ServerLevel level,
            BlockPos spawn) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(spawn, "spawn");
        if (level.getServer()
                .getWorldData()
                .isDebugWorld()) {
            return;
        }
        level.registryAccess()
                .registry(Registries.CONFIGURED_FEATURE)
                .flatMap(registry -> registry.getHolder(
                        MiscOverworldFeatures.BONUS_CHEST))
                .ifPresent(holder -> holder.value().place(
                        level,
                        level.getChunkSource().getGenerator(),
                        level.random,
                        spawn));
    }

    private static final class LevelSpawnEnvironment
            implements SpawnEnvironment {
        private final ServerLevel level;
        private final ServerChunkCache chunks;

        private LevelSpawnEnvironment(ServerLevel level) {
            this.level = Objects.requireNonNull(
                    level,
                    "level");
            this.chunks = level.getChunkSource();
        }

        @Override
        public boolean isDebugWorld() {
            return level.getServer()
                    .getWorldData()
                    .isDebugWorld();
        }

        @Override
        public ChunkPos initialChunk() {
            return new ChunkPos(
                    chunks.randomState()
                            .sampler()
                            .findSpawnPosition());
        }

        @Override
        public int spawnHeight() {
            return chunks.getGenerator()
                    .getSpawnHeight(level);
        }

        @Override
        public int minBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public int surfaceHeight(int x, int z) {
            return level.getHeight(
                    Heightmap.Types.WORLD_SURFACE,
                    x,
                    z);
        }

        @Override
        public BlockPos findSpawnInChunk(
                ChunkPos chunk) {
            return PlayerRespawnLogic
                    .getSpawnPosInChunk(
                            level,
                            chunk);
        }
    }
}
