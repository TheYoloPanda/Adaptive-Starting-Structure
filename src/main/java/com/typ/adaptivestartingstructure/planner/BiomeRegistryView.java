package com.typ.adaptivestartingstructure.planner;

import net.minecraft.resources.ResourceLocation;

public interface BiomeRegistryView {
    boolean containsBiome(ResourceLocation biomeId);

    boolean containsTag(ResourceLocation tagId);
}
