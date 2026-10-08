package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ModConfig;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

/**
 * Puts arriving players on the structure's spawn marker.
 *
 * <p>Setting the world spawn to the marker is not enough to arrive there.
 * Vanilla picks a column within {@code spawnRadius} of the world spawn and
 * returns that column's surface, so for a structure with a roof the arrival
 * point is the roof, and the marker inside the building is reached only when
 * every candidate column happens to have no surface at all. The gamerule does
 * not help either: at radius zero the one column it considers is still
 * resolved to its surface.
 *
 * <p>The move is therefore made after the fact, and only for a player the
 * vanilla search actually placed: one who has no respawn block of their own
 * and who arrived inside the area that search could have chosen from. A login
 * settles it once per player, moved or not; a respawn that falls back to the
 * world spawn is moved every time.
 */
public final class SpawnMarkerPlacement {
    static final String PLACED_KEY = "AdaptiveStartingStructurePlaced";
    static final String REFUSED_SESSION_KEY =
            "AdaptiveStartingStructureRefusedSession";

    private SpawnMarkerPlacement() {
    }

    /**
     * Whether a player standing at {@code playerPosition} was put there by the
     * vanilla spawn search and should be moved onto the marker.
     */
    static boolean shouldMoveToMarker(
            BlockPos marker,
            BlockPos playerPosition,
            int vanillaSpawnRadius,
            boolean usedOwnRespawnBlock) {
        if (usedOwnRespawnBlock) {
            return false;
        }
        if (marker.getX() == playerPosition.getX()
                && marker.getY() == playerPosition.getY()
                && marker.getZ() == playerPosition.getZ()) {
            return false;
        }
        /*
         * One block wider than the search, because its final fallback walks the
         * chosen column up or down until the player fits.
         */
        int reach = Math.max(0, vanillaSpawnRadius) + 1;
        return Math.abs(playerPosition.getX() - marker.getX()) <= reach
                && Math.abs(playerPosition.getZ() - marker.getZ()) <= reach;
    }

    /**
     * Whether a player let into a session that was refused before the
     * structure stood arrives again as a new player. Only once it stands:
     * until then there is nothing to arrive at, and the next start may still
     * build it anywhere.
     */
    static boolean arrivesAgain(
            CompoundTag persistentData,
            Optional<StartingStructureSavedData.State> state) {
        return persistentData.getCompound(Player.PERSISTED_NBT_TAG)
                        .getBoolean(REFUSED_SESSION_KEY)
                && state.filter(current -> current
                                == StartingStructureSavedData.State.COMPLETE)
                        .isPresent();
    }

    /**
     * Marks a player let into a session that is about to be refused. The game
     * saves them where it put them on the terrain as it was, and the start
     * that builds the structure may build it at another site, beyond the
     * reach of the move onto the marker.
     */
    static void markRefusedSession(ServerPlayer player) {
        markRefusedSession(player.getPersistentData());
    }

    static void markRefusedSession(CompoundTag persistentData) {
        CompoundTag persisted =
                persistentData.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putBoolean(REFUSED_SESSION_KEY, true);
        persistentData.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    static void clearRefusedSession(CompoundTag persistentData) {
        persistentData.getCompound(Player.PERSISTED_NBT_TAG)
                .remove(REFUSED_SESSION_KEY);
    }

    public static void onPlayerLoggedIn(
            PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            arriveAgainAfterRefusedSession(player);
        } catch (RuntimeException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to bring a player from a refused session to the world spawn",
                    failure);
        }
        if (hasBeenPlaced(player)) {
            return;
        }
        try {
            markerFor(player).ifPresent(marker -> {
                markPlaced(player);
                if (!shouldMoveToMarker(
                        marker,
                        player.blockPosition(),
                        spawnRadius(player),
                        player.getRespawnPosition() != null)) {
                    return;
                }
                player.teleportTo(
                        player.serverLevel(),
                        marker.getX() + 0.5D,
                        marker.getY(),
                        marker.getZ() + 0.5D,
                        player.getYRot(),
                        player.getXRot());
                AdaptiveStartingStructure.LOGGER.info(
                        "Moved {} onto the starting structure's spawn marker at {}",
                        player.getGameProfile().getName(),
                        marker);
            });
        } catch (RuntimeException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to place a player on the starting structure's spawn marker",
                    failure);
        }
    }

    public static void onRespawnPosition(PlayerRespawnPositionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DimensionTransition transition = event.getDimensionTransition();
        if (transition.newLevel().dimension() != Level.OVERWORLD) {
            return;
        }
        /*
         * A respawn block that actually worked wins. A destroyed bed does not:
         * the transition then falls back to the world spawn, which is the case
         * this exists for.
         */
        boolean usedOwnRespawnBlock = !transition.missingRespawnBlock()
                && player.getRespawnPosition() != null;
        try {
            markerFor(player).ifPresent(marker -> {
                if (!shouldMoveToMarker(
                        marker,
                        BlockPos.containing(transition.pos()),
                        spawnRadius(player),
                        usedOwnRespawnBlock)) {
                    return;
                }
                event.setDimensionTransition(new DimensionTransition(
                        transition.newLevel(),
                        new Vec3(
                                marker.getX() + 0.5D,
                                marker.getY(),
                                marker.getZ() + 0.5D),
                        transition.speed(),
                        transition.yRot(),
                        transition.xRot(),
                        transition.missingRespawnBlock(),
                        transition.postDimensionTransition()));
            });
        } catch (RuntimeException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to respawn a player on the starting structure's spawn marker",
                    failure);
        }
    }

    /*
     * Placed where the game puts a player it has never seen, whatever
     * placePlayerAtSpawnMarker says; the move onto the marker then treats
     * the player as arriving, as it would have on a first load that worked.
     */
    private static void arriveAgainAfterRefusedSession(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        if (!persistentData.getCompound(Player.PERSISTED_NBT_TAG)
                .getBoolean(REFUSED_SESSION_KEY)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        Optional<StartingStructureSavedData.State> state =
                StartingStructureStorage.load(overworld)
                        .map(StartingStructureSavedData::state);
        if (!arrivesAgain(persistentData, state)) {
            return;
        }
        BlockPos arrival = player.adjustSpawnLocation(
                overworld,
                overworld.getSharedSpawnPos());
        player.teleportTo(
                overworld,
                arrival.getX() + 0.5D,
                arrival.getY(),
                arrival.getZ() + 0.5D,
                0.0F,
                0.0F);
        clearRefusedSession(persistentData);
        AdaptiveStartingStructure.LOGGER.info(
                "Moved {}, who joined a session refused before the starting structure was built, "
                        + "to the world spawn at {}",
                player.getGameProfile().getName(),
                arrival);
    }

    /** The marker of a structure that is actually standing in this world. */
    private static Optional<BlockPos> markerFor(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !ModConfig.snapshot().placePlayerAtSpawnMarker()) {
            return Optional.empty();
        }
        ServerLevel overworld = server.overworld();
        return StartingStructureStorage.load(overworld)
                .filter(data -> data.state()
                        == StartingStructureSavedData.State.COMPLETE)
                .map(data -> data.plan().candidate().worldSpawn());
    }

    private static int spawnRadius(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null
                ? 0
                : server.getSpawnRadius(server.overworld());
    }

    private static boolean hasBeenPlaced(ServerPlayer player) {
        return player.getPersistentData()
                .getCompound(Player.PERSISTED_NBT_TAG)
                .getBoolean(PLACED_KEY);
    }

    private static void markPlaced(ServerPlayer player) {
        CompoundTag persistent = player.getPersistentData();
        CompoundTag persisted =
                persistent.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putBoolean(PLACED_KEY, true);
        persistent.put(Player.PERSISTED_NBT_TAG, persisted);
    }
}
