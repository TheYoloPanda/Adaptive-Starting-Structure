package com.typ.adaptivestartingstructure.network;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record FallbackDecisionResultPayload(
        boolean success,
        boolean retryable) implements CustomPacketPayload {
    public static final Type<FallbackDecisionResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AdaptiveStartingStructure.MOD_ID,
                    "fallback_decision_result"));
    public static final StreamCodec<
            ByteBuf,
            FallbackDecisionResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    FallbackDecisionResultPayload::success,
                    ByteBufCodecs.BOOL,
                    FallbackDecisionResultPayload::retryable,
                    FallbackDecisionResultPayload::new);

    public FallbackDecisionResultPayload {
        if (success && retryable) {
            throw new IllegalArgumentException(
                    "A successful fallback result cannot be retryable");
        }
    }

    public static FallbackDecisionResultPayload applied() {
        return new FallbackDecisionResultPayload(true, false);
    }

    public static FallbackDecisionResultPayload retryableFailure() {
        return new FallbackDecisionResultPayload(false, true);
    }

    public static FallbackDecisionResultPayload terminalFailure() {
        return new FallbackDecisionResultPayload(false, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
