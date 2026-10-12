package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;

/**
 * Sets the world spawn, saying so when it moves.
 *
 * <p>Planning commits the spawn before any Overworld chunk is generated, which
 * is what lets other mods derive positions from it. Placement then tries one
 * site at a time, each under its own spawn, because a site is judged on its
 * generated terrain; before the spawn leaves a rejected site, that site's
 * terrain is discarded, and only the site that is kept gets the chunks vanilla
 * keeps loaded around the spawn. Those moves leave nothing generated under the
 * old spawn.
 *
 * <p>Applying a fallback decision's spawn again when a later start recovers a
 * world is different: the chunks already saved around the old spawn keep what
 * they derived from it, and nothing here can undo that. What it can do is make
 * it visible, and say how far, so a report of inconsistent world generation
 * near the old spawn has an entry to point at.
 */
final class SpawnRelocation {
    private SpawnRelocation() {
    }

    static void apply(ServerLevel level, BlockPos spawn) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(spawn, "spawn");
        BlockPos previous = level.getSharedSpawnPos();
        if (!previous.equals(spawn)) {
            AdaptiveStartingStructure.LOGGER.warn(message(previous, spawn));
        }
        level.setDefaultSpawnPos(spawn, 0.0F);
    }

    /**
     * Moves the spawn once the terrain generated under the old one is
     * discarded. The chunks vanilla keeps loaded around a spawn are left out:
     * they would be generated now, for a site that may still be rejected.
     */
    static void moveAfterDiscard(ServerLevel level, BlockPos spawn) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(spawn, "spawn");
        BlockPos previous = level.getLevelData().getSpawnPos();
        if (!previous.equals(spawn)) {
            AdaptiveStartingStructure.LOGGER.info(
                    discardedMessage(previous, spawn));
        }
        level.getServer()
                .getWorldData()
                .overworldData()
                .setSpawn(spawn, 0.0F);
    }

    /** Lets the chunks vanilla keeps loaded around the current spawn unload. */
    static void releaseSpawnChunks(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        int radius = level.getGameRules()
                .getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) + 1;
        /* ServerLevel.setDefaultSpawnPos tickets nothing for a spawnChunkRadius of 0. */
        if (radius > 1) {
            level.getChunkSource().removeRegionTicket(
                    TicketType.START,
                    new ChunkPos(level.getLevelData().getSpawnPos()),
                    radius,
                    Unit.INSTANCE);
        }
    }

    static String message(BlockPos previous, BlockPos next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        return "World spawn moved from " + previous + " to " + next
                + " (" + distance(previous, next) + " blocks) after the chunks around the old spawn "
                + "were already generated. Mods that derive world generation from "
                + "the world spawn, such as distance-based ore gating, keep the old "
                + "spawn in that already-generated area.";
    }

    static String discardedMessage(BlockPos previous, BlockPos next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        return "World spawn moved from " + previous + " to " + next
                + " (" + distance(previous, next) + " blocks); the terrain generated "
                + "under the old spawn was discarded first and generates again "
                + "under the new one";
    }

    private static long distance(BlockPos previous, BlockPos next) {
        long deltaX = (long) next.getX() - previous.getX();
        long deltaZ = (long) next.getZ() - previous.getZ();
        return (long) Math.sqrt(
                (double) deltaX * deltaX + (double) deltaZ * deltaZ);
    }
}
