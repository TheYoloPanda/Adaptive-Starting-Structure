package com.typ.adaptivestartingstructure.network;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.lifecycle.FallbackDecisionLifecycle;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class FallbackNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private FallbackNetwork() {
    }

    public static void register(
            RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event
                .registrar(AdaptiveStartingStructure.MOD_ID)
                .versioned(PROTOCOL_VERSION)
                .optional();
        registrar.playToClient(
                OpenFallbackDecisionPayload.TYPE,
                OpenFallbackDecisionPayload.STREAM_CODEC,
                FallbackNetwork::handleOpen);
        registrar.playToServer(
                ContinueFallbackPayload.TYPE,
                ContinueFallbackPayload.STREAM_CODEC,
                FallbackNetwork::handleContinue);
        registrar.playToClient(
                FallbackDecisionResultPayload.TYPE,
                FallbackDecisionResultPayload.STREAM_CODEC,
                FallbackNetwork::handleResult);
    }

    private static void handleOpen(
            OpenFallbackDecisionPayload payload,
            IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        context.enqueueWork(() ->
                com.typ.adaptivestartingstructure.client
                        .ClientFallbackDecisionHandler
                        .open());
    }

    private static void handleContinue(
            ContinueFallbackPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player()
                    instanceof ServerPlayer player) {
                FallbackDecisionLifecycle
                        .continueWithoutStructure(player);
            }
        });
    }

    private static void handleResult(
            FallbackDecisionResultPayload payload,
            IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        context.enqueueWork(() ->
                com.typ.adaptivestartingstructure.client
                        .ClientFallbackDecisionHandler
                        .handleResult(payload));
    }
}
