package com.typ.adaptivestartingstructure.lifecycle;

import com.mojang.authlib.GameProfile;
import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.compat.RadialResourceGatingCenter;
import com.typ.adaptivestartingstructure.config.ModConfig;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Tells the singleplayer host and each operator, once per world, that Radial
 * Resource Gating keeps its overworld ore gate on a fixed point while this
 * mod moved the world spawn, so the player may start outside the gated ring.
 * Only they can change either config, so nobody else is told.
 */
public final class FixedOreGateCenterNotice {
    static final String NOTIFIED_KEY =
            "AdaptiveStartingStructureFixedOreGateCenterNotice";
    static final String MESSAGE_KEY =
            "message.adaptive_starting_structure.fixed_ore_gate_center";

    private FixedOreGateCenterNotice() {
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        try {
            GameProfile profile = player.getGameProfile();
            centerToReport(
                    ModConfig.snapshot().warnAboutFixedOreGateCenter(),
                    StartingStructureStorage.load(server.overworld())
                            .map(StartingStructureSavedData::state),
                    server.isSingleplayerOwner(profile)
                            || server.getPlayerList().isOp(profile),
                    player.getPersistentData(),
                    RadialResourceGatingCenter::lookUp)
                    .ifPresent(center -> report(
                            player,
                            center,
                            server.overworld().getSharedSpawnPos()));
        } catch (RuntimeException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to check Radial Resource Gating's ore gate center for player '{}'",
                    player.getGameProfile().getName(),
                    failure);
        }
    }

    /**
     * Radial Resource Gating's config is read last, and only for a player who
     * would be told, so that a world nobody needs to hear about never touches
     * another mod's config.
     */
    static Optional<RadialResourceGatingCenter> centerToReport(
            boolean warningEnabled,
            Optional<StartingStructureSavedData.State> state,
            boolean hostOrOperator,
            CompoundTag persistentData,
            Supplier<RadialResourceGatingCenter> lookUp) {
        if (!warningEnabled
                || state.filter(StartingStructureSavedData.State.COMPLETE::equals).isEmpty()
                || !hostOrOperator
                || isNotified(persistentData)) {
            return Optional.empty();
        }
        return Optional.of(lookUp.get());
    }

    /**
     * An unreadable config leaves the player unmarked, so that a later login
     * checks again once whatever kept it unreadable is gone.
     */
    private static void report(
            ServerPlayer player,
            RadialResourceGatingCenter center,
            BlockPos spawn) {
        switch (center.status()) {
            case FIXED -> {
                player.sendSystemMessage(message(center, spawn));
                AdaptiveStartingStructure.LOGGER.warn(
                        "Radial Resource Gating centers its overworld ore gate on ({}, {}) "
                                + "with range {}, but the world spawn is at ({}, {}); "
                                + "told player '{}'",
                        center.centerX(),
                        center.centerZ(),
                        center.range(),
                        spawn.getX(),
                        spawn.getZ(),
                        player.getGameProfile().getName());
                markNotified(player.getPersistentData());
            }
            case UNREADABLE -> AdaptiveStartingStructure.LOGGER.warn(
                    "Could not check where Radial Resource Gating centers its overworld ore gate: {}",
                    center.unreadableReason());
            case NOT_FIXED -> {
            }
        }
    }

    private static boolean isNotified(CompoundTag persistentData) {
        return persistentData.getCompound(Player.PERSISTED_NBT_TAG)
                .getBoolean(NOTIFIED_KEY);
    }

    static void markNotified(CompoundTag persistentData) {
        CompoundTag persisted =
                persistentData.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putBoolean(NOTIFIED_KEY, true);
        persistentData.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    static Component message(RadialResourceGatingCenter center, BlockPos spawn) {
        Component prefix = Component.translatable(PlanningFailureNotice.PREFIX_KEY)
                .withStyle(ChatFormatting.GOLD);
        return Component.translatable(
                        MESSAGE_KEY,
                        prefix,
                        center.centerX(),
                        center.centerZ(),
                        center.range(),
                        spawn.getX(),
                        spawn.getZ())
                .withStyle(ChatFormatting.YELLOW);
    }
}
