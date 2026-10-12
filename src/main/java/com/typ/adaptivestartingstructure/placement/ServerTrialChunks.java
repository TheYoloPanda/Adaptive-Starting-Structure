package com.typ.adaptivestartingstructure.placement;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

final class ServerTrialChunks implements TrialTerrainDiscard.Chunks {
    /*
     * Worker threads wake the server thread when they hand it work, so this
     * only bounds the wait when none comes.
     */
    private static final long IDLE_PARK_NANOS =
            TimeUnit.MILLISECONDS.toNanos(1);

    private final ServerLevel level;
    private final ServerChunkCache source;
    private final Runnable releaseTickets;

    ServerTrialChunks(ServerLevel level, Runnable releaseTickets) {
        this.level = Objects.requireNonNull(level, "level");
        this.source = level.getChunkSource();
        this.releaseTickets = Objects.requireNonNull(
                releaseTickets,
                "releaseTickets");
    }

    @Override
    public boolean inMemory(long chunk) {
        return holder(chunk) != null;
    }

    @Override
    public boolean generating(long chunk) {
        ChunkHolder holder = holder(chunk);
        return holder != null && holder.getGenerationRefCount() > 0;
    }

    @Override
    public boolean held(long chunk) {
        ChunkHolder holder = holder(chunk);
        return holder != null
                && ChunkLevel.isLoaded(holder.getTicketLevel());
    }

    @Override
    public boolean full(long chunk) {
        ChunkHolder holder = holder(chunk);
        return holder != null
                && holder.getLatestChunk() instanceof LevelChunk;
    }

    @Override
    public void markSaved(long chunk) {
        ChunkHolder holder = holder(chunk);
        ChunkAccess latest = holder == null
                ? null
                : holder.getLatestChunk();
        if (latest != null) {
            latest.setUnsaved(false);
        }
    }

    @Override
    public List<TrialTerrainDiscard.TrialEntity> entitiesIn(
            LongSet chunks) {
        List<TrialTerrainDiscard.TrialEntity> entities =
                new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            long chunk = entity.chunkPosition().toLong();
            if (!(entity instanceof Player) && chunks.contains(chunk)) {
                entities.add(new LevelEntity(entity, chunk));
            }
        }
        return entities;
    }

    @Override
    public void releaseTickets() {
        releaseTickets.run();
    }

    @Override
    public void runPendingWork(long deadlineNanos) {
        boolean ran = false;
        while (System.nanoTime() < deadlineNanos
                && source.pollTask()) {
            ran = true;
        }
        if (!ran) {
            LockSupport.parkNanos(IDLE_PARK_NANOS);
        }
    }

    @Override
    public boolean tick(long sliceEndNanos) {
        source.tick(() -> System.nanoTime() < sliceEndNanos, false);
        return System.nanoTime() < sliceEndNanos;
    }

    @Override
    public CompletableFuture<Void> erase(long chunk) {
        /* A null tag clears the chunk's entry from its region file. */
        return source.chunkMap.write(new ChunkPos(chunk), null);
    }

    private ChunkHolder holder(long chunk) {
        return source.chunkMap.getVisibleChunkIfPresent(chunk);
    }

    private record LevelEntity(Entity entity, long chunk)
            implements TrialTerrainDiscard.TrialEntity {
        @Override
        public void remove() {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
    }
}
