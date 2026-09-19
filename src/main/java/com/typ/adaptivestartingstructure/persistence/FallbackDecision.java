package com.typ.adaptivestartingstructure.persistence;

import java.util.Objects;
import net.minecraft.core.BlockPos;

public record FallbackDecision(
        BlockPos vanillaSpawn,
        boolean generateBonusChest,
        String originalError) {

    public FallbackDecision {
        vanillaSpawn = Objects.requireNonNull(
                vanillaSpawn,
                "vanillaSpawn").immutable();
        originalError = Objects.requireNonNull(
                originalError,
                "originalError");
        if (originalError.isBlank()) {
            throw new IllegalArgumentException(
                    "Fallback decision requires a non-blank original error");
        }
    }
}
