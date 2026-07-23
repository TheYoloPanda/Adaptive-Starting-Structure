package com.typ.adaptivestartingstructure.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

record StructureNbtValidation(
        Vec3i size,
        int paletteSize,
        Map<Integer, List<ResourceLocation>> unavailableBlocksByStateIndex) {
    StructureNbtValidation {
        size = Objects.requireNonNull(size, "size");
        if (paletteSize <= 0) {
            throw new IllegalArgumentException("paletteSize must be positive");
        }
        Map<Integer, List<ResourceLocation>> copy = new LinkedHashMap<>();
        unavailableBlocksByStateIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int stateIndex = entry.getKey();
                    if (stateIndex < 0 || stateIndex >= paletteSize) {
                        throw new IllegalArgumentException(
                                "Unavailable state index lies outside the palette");
                    }
                    List<ResourceLocation> ids = new ArrayList<>(entry.getValue());
                    ids.sort((first, second) ->
                            first.toString().compareTo(second.toString()));
                    List<ResourceLocation> distinctIds = ids.stream()
                            .distinct()
                            .toList();
                    if (distinctIds.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Unavailable state indexes must name at least one block");
                    }
                    copy.put(stateIndex, distinctIds);
                });
        unavailableBlocksByStateIndex = Collections.unmodifiableMap(copy);
    }

    boolean isUnavailable(int stateIndex) {
        return unavailableBlocksByStateIndex.containsKey(stateIndex);
    }
}
