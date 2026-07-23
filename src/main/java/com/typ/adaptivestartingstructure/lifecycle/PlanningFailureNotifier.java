package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class PlanningFailureNotifier {
    private static final PendingPlanningFailures<MinecraftServer> PENDING =
            new PendingPlanningFailures<>();

    private PlanningFailureNotifier() {
    }

    static void queue(
            MinecraftServer server,
            PlanningFailureNotice notice) {
        PENDING.queue(
                Objects.requireNonNull(server, "server"),
                Objects.requireNonNull(notice, "notice"));
    }

    public static void onPlayerLoggedIn(
            PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || !canReceive(
                server.isDedicatedServer(),
                server.getPlayerList().isOp(player.getGameProfile()))) {
            return;
        }
        try {
            PENDING.deliver(server, player::sendSystemMessage);
        } catch (RuntimeException deliveryFailure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to deliver the pending starting-structure planning notification",
                    deliveryFailure);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear(Objects.requireNonNull(event, "event").getServer());
    }

    static boolean canReceive(
            boolean dedicatedServer,
            boolean operator) {
        return !dedicatedServer || operator;
    }
}
