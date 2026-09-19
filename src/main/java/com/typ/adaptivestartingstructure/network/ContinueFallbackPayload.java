package com.typ.adaptivestartingstructure.network;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ContinueFallbackPayload()
        implements CustomPacketPayload {
    public static final ContinueFallbackPayload INSTANCE =
            new ContinueFallbackPayload();
    public static final Type<ContinueFallbackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AdaptiveStartingStructure.MOD_ID,
                    "continue_fallback"));
    public static final StreamCodec<
            ByteBuf,
            ContinueFallbackPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
