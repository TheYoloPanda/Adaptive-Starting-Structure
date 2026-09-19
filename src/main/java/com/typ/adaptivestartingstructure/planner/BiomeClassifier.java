package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
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

    private final Set<ResourceLocation> ignoredConfigEntries;

    private BiomeClassifier(
            boolean allowUnlistedLandBiomes,
            Set<ResourceLocation> preferredBiomes,
            Set<ResourceLocation> excludedBiomes,
            Set<ResourceLocation> excludedBiomeTags,
            Set<ResourceLocation> ignoredConfigEntries) {
        this.allowUnlistedLandBiomes = allowUnlistedLandBiomes;
        this.preferredBiomes = preferredBiomes;
        this.excludedBiomes = excludedBiomes;
        this.excludedBiomeTags = excludedBiomeTags;
        this.ignoredConfigEntries = ignoredConfigEntries;
    }

    /**
     * Builds the classifier, dropping anything the server's registry does not
     * have.
     *
     * <p>A modpack that lists another mod's biome and later drops that mod
     * would otherwise lose the starting structure entirely, for an entry whose
     * only effect was to name a biome that no longer exists. The dropped
     * entries are logged so a typo is still visible.
     */
    public static BiomeClassifier resolve(
            ConfigSnapshot config,
            BiomeRegistryView registryView) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registryView, "registryView");
        Set<ResourceLocation> ignored = new LinkedHashSet<>();
        Set<ResourceLocation> preferred = known(
                config.preferredBiomes(),
                registryView::containsBiome,
                ignored);
        Set<ResourceLocation> excluded = known(
                config.excludedBiomes(),
                registryView::containsBiome,
                ignored);
        Set<ResourceLocation> excludedTags = known(
                config.excludedBiomeTags(),
                registryView::containsTag,
                ignored);
        if (!ignored.isEmpty()) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Ignoring {} configured biome entries that this server's registry does not have: {}",
                    ignored.size(),
                    ignored);
        }
        return new BiomeClassifier(
                config.allowUnlistedLandBiomes(),
                preferred,
                excluded,
                excludedTags,
                Set.copyOf(ignored));
    }

    private static Set<ResourceLocation> known(
            Set<ResourceLocation> configured,
            Predicate<ResourceLocation> present,
            Set<ResourceLocation> ignored) {
        Set<ResourceLocation> retained = new LinkedHashSet<>(configured.size());
        for (ResourceLocation id : configured) {
            if (present.test(id)) {
                retained.add(id);
            } else {
                ignored.add(id);
            }
        }
        return Collections.unmodifiableSet(retained);
    }

    /** The configured entries this server's registry does not have. */
    public Set<ResourceLocation> ignoredConfigEntries() {
        return ignoredConfigEntries;
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


    public enum Classification {
        PREFERRED,
        FALLBACK_LAND,
        EXCLUDED,
        UNLISTED
    }
}
