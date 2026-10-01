package com.typ.adaptivestartingstructure.client;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.network.FallbackDecisionResultPayload;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

public final class ClientFallbackDecisionHandler {
    private ClientFallbackDecisionHandler() {
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isOwnIntegratedServer(minecraft)) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Ignored a fallback-decision screen payload because the client is not connected to its own integrated server");
            return;
        }
        if (!(minecraft.screen
                instanceof SafePlacementFailureScreen)
                && !(minecraft.screen
                        instanceof ApplyingFallbackScreen)) {
            minecraft.setScreen(
                    new SafePlacementFailureScreen());
        }
    }

    public static void handleResult(
            FallbackDecisionResultPayload result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isOwnIntegratedServer(minecraft)) {
            return;
        }
        if (result.success()) {
            minecraft.setScreen(null);
        } else if (result.retryable()) {
            minecraft.setScreen(
                    new SafePlacementFailureScreen()
                            .withError(Component.translatable(
                                    "screen.adaptive_starting_structure.fallback.retry_error")));
        }
    }

    static void deleteWorldAndCreateNew(
            SafePlacementFailureScreen returnScreen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isOwnIntegratedServer(minecraft)) {
            minecraft.setScreen(
                    returnScreen.withError(
                            Component.translatable(
                                    "screen.adaptive_starting_structure.fallback.local_only_error")));
            return;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        /*
         * Identified while the world is still running: once the server has
         * stopped, nothing ties a folder to the world the player was in.
         */
        Optional<String> levelId = FailedWorldDeletion.runningWorldId(
                server.getWorldPath(LevelResource.ROOT),
                minecraft.getLevelSource());
        String failureLabel = levelId.orElse(
                server.getWorldData().getLevelName());
        Runnable closeAndDelete = () -> {
            closeWorld(minecraft);
            SelectWorldScreen worldList =
                    new SelectWorldScreen(new TitleScreen());
            /*
             * The world's lock is released only when the server has stopped;
             * deleting any earlier could leave half a world behind.
             */
            if (levelId.isPresent()
                    && server.isShutdown()
                    && FailedWorldDeletion.deleteListedWorld(
                            minecraft.getLevelSource(),
                            levelId.get())) {
                CreateWorldScreen.openFresh(minecraft, worldList);
            } else {
                minecraft.setScreen(worldList);
                SystemToast.onWorldDeleteFailure(minecraft, failureLabel);
            }
        };
        if (minecraft.player == null) {
            closeAndDelete.run();
            return;
        }
        minecraft.setScreen(new LeavingWorldScreen(
                server,
                minecraft.player.getUUID(),
                closeAndDelete));
    }

    private static void closeWorld(Minecraft minecraft) {
        if (minecraft.level != null) {
            minecraft.level.disconnect();
        }
        minecraft.disconnect(new GenericMessageScreen(
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.closing")));
    }

    private static boolean isOwnIntegratedServer(
            Minecraft minecraft) {
        return minecraft.isSingleplayer()
                && minecraft.hasSingleplayerServer()
                && minecraft.getConnection() != null
                && minecraft.getConnection()
                        .getConnection()
                        .isMemoryConnection();
    }
}
