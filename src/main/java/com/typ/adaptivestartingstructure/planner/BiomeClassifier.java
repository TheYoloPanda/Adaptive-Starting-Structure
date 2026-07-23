package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

public final class BiomeClassifier {
    private final boolean allowUnlistedLandBiomes;
    private final Set<ResourceLocation> preferredBiomes;
    private final Set<ResourceLocation> excludedBiomes;
    private final Set<ResourceLocation> excludedBiomeTags;

    private BiomeClassifier(ConfigSnapshot config) {
        this.allowUnlistedLandBiomes = config.allowUnlistedLandBiomes();
        this.preferredBiomes = config.preferredBiomes();
        this.excludedBiomes = config.excludedBiomes();
        this.excludedBiomeTags = config.excludedBiomeTags();
    }

    public static BiomeClassifier resolve(
            ConfigSnapshot config,
            BiomeRegistryView registryView) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registryView, "registryView");
        for (ResourceLocation biomeId : config.preferredBiomes()) {
            requireBiome(registryView, biomeId, "preferredBiomes");
        }
        for (ResourceLocation biomeId : config.excludedBiomes()) {
            requireBiome(registryView, biomeId, "excludedBiomes");
        }
        for (ResourceLocation tagId : config.excludedBiomeTags()) {
            if (!registryView.containsTag(tagId)) {
                throw new BiomeConfigurationException(
                        "Configured excluded biome tag does not exist in the server registry: #"
                                + tagId);
            }
        }
        return new BiomeClassifier(config);
    }

    public static BiomeClassifier resolve(
            ConfigSnapshot config,
            Registry<Biome> biomeRegistry) {
        Objects.requireNonNull(biomeRegistry, "biomeRegistry");
        return resolve(config, new BiomeRegistryView() {
            @Override
            public boolean containsBiome(ResourceLocation biomeId) {
                return biomeRegistry.containsKey(biomeId);
            }

            @Override
            public boolean containsTag(ResourceLocation tagId) {
                return biomeRegistry.getTag(TagKey.create(Registries.BIOME, tagId)).isPresent();
            }
        });
    }

    public Classification classify(BiomeSample biome) {
        Objects.requireNonNull(biome, "biome");
        if (excludedBiomes.contains(biome.id())
                || biome.tags().stream().anyMatch(excludedBiomeTags::contains)) {
            return Classification.EXCLUDED;
        }
        if (preferredBiomes.contains(biome.id())) {
            return Classification.PREFERRED;
        }
        return allowUnlistedLandBiomes
                ? Classification.FALLBACK_LAND
                : Classification.UNLISTED;
    }

    private static void requireBiome(
            BiomeRegistryView registryView,
            ResourceLocation biomeId,
            String configField) {
        if (!registryView.containsBiome(biomeId)) {
            throw new BiomeConfigurationException(
                    "Configured biome in " + configField
                            + " does not exist in the server registry: " + biomeId);
        }
    }

    public enum Classification {
        PREFERRED,
        FALLBACK_LAND,
        EXCLUDED,
        UNLISTED
    }
}
