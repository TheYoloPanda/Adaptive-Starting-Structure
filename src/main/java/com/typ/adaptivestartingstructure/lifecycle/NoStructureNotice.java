package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Tells each player of a dedicated server, once, that its world went on
 * without the starting structure. Nobody chose that there: the server does it
 * by itself when no safe site remains, so the players are the ones left to
 * decide whether to start a new world.
 */
public final class NoStructureNotice {
    static final String NOTIFIED_KEY = "AdaptiveStartingStructureNoStructureNotice";
    static final String MESSAGE_KEY =
            "message.adaptive_starting_structure.continued_without_structure";

    private NoStructureNotice() {
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || !server.isDedicatedServer()) {
            return;
        }
        try {
            Optional<StartingStructureSavedData.State> state =
                    StartingStructureStorage.load(server.overworld())
                            .map(StartingStructureSavedData::state);
            if (!shouldNotify(player.getPersistentData(), state)) {
                return;
            }
            player.sendSystemMessage(message());
            markNotified(player.getPersistentData());
        } catch (RuntimeException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to tell player '{}' that this world has no starting structure",
                    player.getGameProfile().getName(),
                    failure);
        }
    }

    static boolean shouldNotify(
            CompoundTag persistentData,
            Optional<StartingStructureSavedData.State> state) {
        return state.filter(StartingStructureSavedData.State.SKIPPED::equals).isPresent()
                && !persistentData.getCompound(Player.PERSISTED_NBT_TAG)
                        .getBoolean(NOTIFIED_KEY);
    }

    static void markNotified(CompoundTag persistentData) {
        CompoundTag persisted =
                persistentData.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putBoolean(NOTIFIED_KEY, true);
        persistentData.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    static Component message() {
        Component prefix = Component.translatable(PlanningFailureNotice.PREFIX_KEY)
                .withStyle(ChatFormatting.GOLD);
        return Component.translatable(MESSAGE_KEY, prefix)
                .withStyle(ChatFormatting.YELLOW);
    }
}
