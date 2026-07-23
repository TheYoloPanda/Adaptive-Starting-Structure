package com.typ.adaptivestartingstructure.structure;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record UnavailableBlockReport(
        List<ResourceLocation> blockIds,
        int replacedPositionCount) {
    public static final UnavailableBlockReport EMPTY =
            new UnavailableBlockReport(List.of(), 0);

    public UnavailableBlockReport {
        blockIds = blockIds.stream()
                .map(blockId -> Objects.requireNonNull(blockId, "blockId"))
                .distinct()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
        if (replacedPositionCount < 0) {
            throw new IllegalArgumentException(
                    "replacedPositionCount must not be negative");
        }
        if ((replacedPositionCount == 0) != blockIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unavailable block IDs and replaced positions must both be empty or non-empty");
        }
    }

    /**
     * @deprecated Use {@link #replacedPositionCount()}.
     */
    @Deprecated(forRemoval = false)
    public int omittedPositionCount() {
        return replacedPositionCount;
    }

    public boolean isEmpty() {
        return replacedPositionCount == 0;
    }
}
