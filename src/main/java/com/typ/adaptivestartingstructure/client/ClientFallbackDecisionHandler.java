package com.typ.adaptivestartingstructure.client;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.network.FallbackDecisionResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

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

    static void returnToWorldList(
            SafePlacementFailureScreen returnScreen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isOwnIntegratedServer(minecraft)) {
            minecraft.setScreen(
                    returnScreen.withError(
                            Component.translatable(
                                    "screen.adaptive_starting_structure.fallback.local_only_error")));
            return;
        }

        SelectWorldScreen worldList =
                new SelectWorldScreen(new TitleScreen());

        if (minecraft.level != null) {
            minecraft.level.disconnect();
        }
        minecraft.disconnect(new GenericMessageScreen(
                Component.translatable(
                        "screen.adaptive_starting_structure.fallback.returning")));
        minecraft.setScreen(worldList);
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
