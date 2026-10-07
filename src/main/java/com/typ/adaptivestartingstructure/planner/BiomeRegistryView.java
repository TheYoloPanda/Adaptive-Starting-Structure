package com.typ.adaptivestartingstructure.planner;

import net.minecraft.resources.ResourceLocation;

public interface BiomeRegistryView {
    boolean containsBiome(ResourceLocation biomeId);

    boolean containsTag(ResourceLocation tagId);

    /**
     * Whether the registered biome is in the tag. Only used to name
     * configuration entries an excluded tag overrides, so a view that cannot
     * tell answers no and costs a warning, never a classification.
     */
    default boolean biomeHasTag(ResourceLocation biomeId, ResourceLocation tagId) {
        return false;
    }
}
