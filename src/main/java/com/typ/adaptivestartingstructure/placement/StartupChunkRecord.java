package com.typ.adaptivestartingstructure.placement;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The Overworld chunks a server loads, at any generation status, from the
 * moment it begins starting until placement is over.
 *
 * <p>A chunk keeps no record of the world spawn it was generated under, so
 * the terrain placement generated for a site it then rejects can only be
 * found by remembering what was loaded while the site was tried. That
 * includes the spawn chunks vanilla prepares before placement begins, which
 * are generated under the first site's spawn.
 */
public final class StartupChunkRecord {
    private final LongSet loaded = new LongOpenHashSet();
    private final LongSet saved = new LongOpenHashSet();
    private Object recordedServer;

    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        begin(Objects.requireNonNull(event, "event").getServer());
    }

    public void onTicketLevelUpdated(
            ChunkTicketLevelUpdatedEvent event) {
        ServerLevel level = event.getLevel();
        recordLevel(
                level.getServer(),
                level.dimension(),
                event.getChunkPos(),
                event.getNewTicketLevel());
    }

    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()
                && event.getLevel() instanceof ServerLevel level
                && event.getChunk() instanceof LevelChunk chunk) {
            recordSaved(
                    level.getServer(),
                    level.dimension(),
                    chunk.getPos().toLong());
        }
    }

    public void onServerStopped(ServerStoppedEvent event) {
        stop();
    }

    /** Every chunk recorded, including those unloaded since. */
    public synchronized LongSet loaded() {
        return new LongOpenHashSet(loaded);
    }

    /** The recorded chunks that were read complete from disk, saved by an earlier start. */
    public synchronized LongSet saved() {
        return new LongOpenHashSet(saved);
    }

    public synchronized void stop() {
        recordedServer = null;
        loaded.clear();
        saved.clear();
    }

    synchronized void begin(Object server) {
        recordedServer = Objects.requireNonNull(server, "server");
        loaded.clear();
        saved.clear();
    }

    synchronized void recordLevel(
            Object server,
            ResourceKey<Level> dimension,
            long chunk,
            int ticketLevel) {
        if (isRecorded(server, dimension)
                && ChunkLevel.isLoaded(ticketLevel)) {
            loaded.add(chunk);
        }
    }

    synchronized void recordSaved(
            Object server,
            ResourceKey<Level> dimension,
            long chunk) {
        if (isRecorded(server, dimension)) {
            saved.add(chunk);
        }
    }

    private boolean isRecorded(
            Object server,
            ResourceKey<Level> dimension) {
        return server != null
                && server == recordedServer
                && dimension == Level.OVERWORLD;
    }
}
