package com.typ.adaptivestartingstructure.network;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenFallbackDecisionPayload()
        implements CustomPacketPayload {
    public static final OpenFallbackDecisionPayload INSTANCE =
            new OpenFallbackDecisionPayload();
    public static final Type<OpenFallbackDecisionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AdaptiveStartingStructure.MOD_ID,
                    "open_fallback_decision"));
    public static final StreamCodec<
            ByteBuf,
            OpenFallbackDecisionPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
