package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class PlacementFailureNotifier {
    private static final PendingPlacementFailures<MinecraftServer> PENDING =
            new PendingPlacementFailures<>();

    private PlacementFailureNotifier() {
    }

    static void blockWorldEntry(
            MinecraftServer server,
            Exception failure) {
        Objects.requireNonNull(server, "server");
        PENDING.queue(
                server,
                PlacementFailureNotice.from(failure));
        if (server.isDedicatedServer()) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Stopping the dedicated server cleanly because starting-structure placement failed");
            server.halt(false);
        }
    }

    public static void onPlayerLoggedIn(
            PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PENDING.find(server).ifPresent(notice -> {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Disconnecting player '{}' because starting-structure placement failed",
                    player.getGameProfile().getName());
            player.connection.disconnect(notice.message());
        });
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear(Objects.requireNonNull(event, "event").getServer());
    }
}
