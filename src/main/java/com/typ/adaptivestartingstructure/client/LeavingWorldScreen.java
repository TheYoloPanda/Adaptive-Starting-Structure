package com.typ.adaptivestartingstructure.client;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.lifecycle.PendingChunkGeneration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/*
 * Stays a pause screen on purpose: while the game is paused the integrated
 * server does not copy the client's render distance back over the reduced view
 * distance, yet it keeps running the chunk tasks it already has.
 */
final class LeavingWorldScreen extends Screen {
    /*
     * Keeps a server that stops answering from holding the player here. Past
     * this bound, leaving is no worse than it was without the wait.
     */
    private static final long MAXIMUM_WAIT_NANOS =
            TimeUnit.SECONDS.toNanos(30);

    private final IntegratedServer server;
    private final UUID playerId;
    private final Runnable leave;
    private final long startedNanos = Util.getNanos();
    private final CompletableFuture<Integer> previousViewDistance;
    private CompletableFuture<Integer> pendingCheck;
    private int claimedChunks = -1;
    private boolean left;

    LeavingWorldScreen(
            IntegratedServer server,
            UUID playerId,
            Runnable leave) {
        super(Component.translatable(
                "screen.adaptive_starting_structure.fallback.leaving"));
        this.server = server;
        this.playerId = playerId;
        this.leave = leave;
        this.previousViewDistance = server.submit(
                () -> PendingChunkGeneration.stopRequestingChunks(server));
    }

    @Override
    public void tick() {
        if (this.left) {
            return;
        }
        long waitedNanos = Util.getNanos() - this.startedNanos;
        if (this.pendingCheck != null && this.pendingCheck.isDone()) {
            this.claimedChunks = this.pendingCheck
                    .exceptionally(failure -> 0)
                    .join();
            this.pendingCheck = null;
            if (this.claimedChunks == 0) {
                AdaptiveStartingStructure.LOGGER.info(
                        "Chunk generation settled {} ms after leaving was requested",
                        TimeUnit.NANOSECONDS.toMillis(waitedNanos));
                leave();
                return;
            }
        }
        if (waitedNanos > MAXIMUM_WAIT_NANOS) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "{} chunks were still being generated {} ms after leaving was requested; leaving anyway, and the integrated server may not stop cleanly",
                    this.claimedChunks,
                    TimeUnit.NANOSECONDS.toMillis(waitedNanos));
            leave();
            return;
        }
        if (this.pendingCheck == null && this.previousViewDistance.isDone()) {
            int viewDistance = this.previousViewDistance
                    .exceptionally(failure -> 0)
                    .join();
            this.pendingCheck = this.server.submit(
                    () -> PendingChunkGeneration.claimedChunksAround(
                            this.server,
                            this.playerId,
                            viewDistance));
        }
    }

    private void leave() {
        this.left = true;
        this.leave.run();
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        super.render(
                graphics,
                mouseX,
                mouseY,
                partialTick);
        graphics.drawCenteredString(
                this.font,
                this.title,
                this.width / 2,
                this.height / 2 - 10,
                0xFFFFFF);
        if (this.claimedChunks > 0) {
            graphics.drawCenteredString(
                    this.font,
                    Component.translatable(
                            "screen.adaptive_starting_structure.fallback.leaving_chunks",
                            this.claimedChunks),
                    this.width / 2,
                    this.height / 2 + 6,
                    0xA0A0A0);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
