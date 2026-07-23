package com.typ.adaptivestartingstructure.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

public record BiomeSample(ResourceLocation id, Set<ResourceLocation> tags) {
    public BiomeSample {
        id = Objects.requireNonNull(id, "id");
        tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
    }

    static BiomeSample fromHolder(Holder<Biome> holder) {
        ResourceLocation id = holder.unwrapKey()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Planner biome sample must be registered"))
                .location();
        Set<ResourceLocation> tags = holder.tags()
                .map(tag -> tag.location())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new BiomeSample(id, tags);
    }
}
