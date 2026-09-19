package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Sets the world spawn, saying so when it moves.
 *
 * <p>Planning commits the spawn before any Overworld chunk is generated, which
 * is what lets other mods derive positions from it. The paths that run at
 * server start are different: by then vanilla has already generated the chunks
 * around the committed spawn, so a retry onto another site, or a fall back to
 * the vanilla spawn, moves the world spawn out from under work that is already
 * on disk. Anything those chunks derived from the old spawn keeps deriving
 * from it, and no later pass revisits them.
 *
 * <p>Nothing here can undo that. What it can do is make it visible, and say
 * how far, so a report of inconsistent world generation near the old spawn has
 * an entry to point at.
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

    static String message(BlockPos previous, BlockPos next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        long deltaX = (long) next.getX() - previous.getX();
        long deltaZ = (long) next.getZ() - previous.getZ();
        long distance = (long) Math.sqrt(
                (double) deltaX * deltaX + (double) deltaZ * deltaZ);
        return "World spawn moved from " + previous + " to " + next
                + " (" + distance + " blocks) after the chunks around the old spawn "
                + "were already generated. Mods that derive world generation from "
                + "the world spawn, such as distance-based ore gating, keep the old "
                + "spawn in that already-generated area.";
    }
}
