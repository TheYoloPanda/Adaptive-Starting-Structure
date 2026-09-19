package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.network.FallbackDecisionResultPayload;
import com.typ.adaptivestartingstructure.network.OpenFallbackDecisionPayload;
import com.typ.adaptivestartingstructure.persistence.FallbackDecision;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class FallbackDecisionLifecycle {
    private FallbackDecisionLifecycle() {
    }

    public static void onPlayerLoggedIn(
            PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity()
                instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (!isAuthorizedOwner(server, player)) {
            return;
        }
        try {
            StartingStructureStorage.load(server.overworld())
                    .filter(data ->
                            data.state()
                                    == StartingStructureSavedData.State
                                            .AWAITING_DECISION)
                    .ifPresent(data ->
                            PacketDistributor.sendToPlayer(
                                    player,
                                    OpenFallbackDecisionPayload
                                            .INSTANCE));
        } catch (RuntimeException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Could not read the pending fallback decision during player login",
                    failure);
            player.connection.disconnect(
                    Component.translatable(
                            "disconnect.adaptive_starting_structure.invalid_state"));
        }
    }

    public static void continueWithoutStructure(
            ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        MinecraftServer server = player.getServer();
        if (!isAuthorizedOwner(server, player)) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Rejected a starting-structure fallback confirmation from an unauthorized or non-local player");
            return;
        }

        ServerLevel level = server.overworld();
        StartingStructureSavedData data;
        try {
            data = StartingStructureStorage.load(level)
                    .orElse(null);
        } catch (RuntimeException failure) {
            failClosed(server, player, failure);
            return;
        }
        if (data == null) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Rejected a fallback confirmation because starting-structure SavedData is absent");
            return;
        }
        if (!acceptsConfirmation(
                true,
                true,
                data.state())) {
            if (data.state()
                    == StartingStructureSavedData.State.SKIPPED) {
                PacketDistributor.sendToPlayer(
                        player,
                        FallbackDecisionResultPayload.applied());
                return;
            }
            AdaptiveStartingStructure.LOGGER.warn(
                    "Rejected a fallback confirmation while starting-structure state was {}",
                    data.state());
            return;
        }

        FallbackDecision decision =
                data.fallbackDecision().orElseThrow();
        try {
            StartingStructureStorage
                    .persistFallbackApplying(level, data);
            AdaptiveStartingStructure.LOGGER.info(
                    "Starting-structure state transition: AWAITING_DECISION -> FALLBACK_APPLYING");
        } catch (Exception failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Could not persist FALLBACK_APPLYING; no fallback world writes were started and the player may retry",
                    failure);
            PacketDistributor.sendToPlayer(
                    player,
                    FallbackDecisionResultPayload
                            .retryableFailure());
            return;
        }

        try {
            VanillaSpawnFallback.applySpawn(
                    level,
                    decision.vanillaSpawn());
            if (decision.generateBonusChest()) {
                VanillaSpawnFallback.placeBonusChest(
                        level,
                        decision.vanillaSpawn());
            }
            if (!server.saveAllChunks(true, true, true)) {
                throw new StartingStructureStartupException(
                        "Minecraft did not save any level after applying the no-structure fallback");
            }
            StartingStructureStorage.persistSkipped(
                    level,
                    data);
            AdaptiveStartingStructure.LOGGER.info(
                    "Starting-structure state transition: FALLBACK_APPLYING -> SKIPPED");
            PacketDistributor.sendToPlayer(
                    player,
                    FallbackDecisionResultPayload.applied());
        } catch (Exception failure) {
            failClosed(server, player, failure);
        }
    }

    private static boolean isAuthorizedOwner(
            MinecraftServer server,
            ServerPlayer player) {
        return server != null
                && server.isSingleplayer()
                && server.isSingleplayerOwner(
                        player.getGameProfile())
                && player.connection
                        .getConnection()
                        .isMemoryConnection();
    }

    static boolean acceptsConfirmation(
            boolean authorizedOwner,
            boolean localConnection,
            StartingStructureSavedData.State state) {
        return authorizedOwner
                && localConnection
                && state
                        == StartingStructureSavedData.State
                                .AWAITING_DECISION;
    }

    private static void failClosed(
            MinecraftServer server,
            ServerPlayer player,
            Exception failure) {
        AdaptiveStartingStructure.LOGGER.error(
                "Fallback application failed after the fail-closed boundary; world entry remains blocked in FALLBACK_APPLYING",
                failure);
        PlacementFailureNotifier.blockWorldEntry(
                server,
                failure);
        PacketDistributor.sendToPlayer(
                player,
                FallbackDecisionResultPayload.terminalFailure());
        player.connection.disconnect(
                Component.translatable(
                        "disconnect.adaptive_starting_structure.fallback_applying"));
    }
}
