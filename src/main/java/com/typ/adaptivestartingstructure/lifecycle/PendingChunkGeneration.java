package com.typ.adaptivestartingstructure.lifecycle;

import java.util.UUID;
import java.util.function.LongToIntFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Lets chunk generation around the owner settle before the integrated server
 * is asked to stop.
 *
 * <p>Vanilla's shutdown drains its chunk-unload queue without yielding, and a
 * chunk still claimed by a generation task puts itself straight back on that
 * queue. When the task needs the server thread to finish, the two wait on each
 * other forever. Leaving right after joining a world whose surroundings are
 * still being generated is exactly that case.
 */
public final class PendingChunkGeneration {
    private static final int MINIMUM_VIEW_DISTANCE = 2;

    private PendingChunkGeneration() {
    }

    /**
     * Stops players from requesting any chunk beyond the smallest view
     * distance, so that no new generation starts, and returns the view
     * distance that was in force.
     */
    public static int stopRequestingChunks(MinecraftServer server) {
        int previous = server.getPlayerList().getViewDistance();
        server.getPlayerList().setViewDistance(MINIMUM_VIEW_DISTANCE);
        return previous;
    }

    public static int claimedChunksAround(
            MinecraftServer server,
            UUID playerId,
            int viewDistance) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        ChunkMap chunkMap = player.serverLevel().getChunkSource().chunkMap;
        return countClaimed(
                player.chunkPosition(),
                claimRadius(viewDistance),
                chunk -> {
                    ChunkHolder holder =
                            chunkMap.getVisibleChunkIfPresent(chunk);
                    return holder == null
                            ? 0
                            : holder.getGenerationRefCount();
                });
    }

    /*
     * A chunk inside the view distance is generated together with a ring of
     * lower-status chunks around it, and each generation task claims a ring of
     * the same width around its own chunk.
     */
    static int claimRadius(int viewDistance) {
        return viewDistance + 2 * ChunkLevel.RADIUS_AROUND_FULL_CHUNK;
    }

    static int countClaimed(
            ChunkPos center,
            int radius,
            LongToIntFunction claimsAt) {
        int claimed = 0;
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                if (claimsAt.applyAsInt(ChunkPos.asLong(x, z)) > 0) {
                    claimed++;
                }
            }
        }
        return claimed;
    }
}
