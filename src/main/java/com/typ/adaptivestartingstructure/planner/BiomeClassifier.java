package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.BiomeListEdits;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

public final class BiomeClassifier {
    private static final String PREFERRED_ADDITION_KEY = "additionalPreferredBiomes";
    private static final String PREFERRED_REMOVAL_KEY = "removedPreferredBiomes";
    private static final String EXCLUDED_REMOVAL_KEY = "removedExcludedBiomes";
    private static final String BUILT_IN_PREFERRED_LIST = "built-in preferred list";

    private final boolean allowUnlistedLandBiomes;
    private final Set<ResourceLocation> preferredBiomes;
    private final Set<ResourceLocation> excludedBiomes;
    private final Set<ResourceLocation> excludedBiomeTags;

    private final Set<ResourceLocation> ignoredConfigEntries;
    private final List<IneffectiveEntry> ineffectiveConfigEntries;
    private final Map<ResourceLocation, RememberedClassification> classifications =
            new HashMap<>();

    private BiomeClassifier(
            boolean allowUnlistedLandBiomes,
            Set<ResourceLocation> preferredBiomes,
            Set<ResourceLocation> excludedBiomes,
            Set<ResourceLocation> excludedBiomeTags,
            Set<ResourceLocation> ignoredConfigEntries,
            List<IneffectiveEntry> ineffectiveConfigEntries) {
        this.allowUnlistedLandBiomes = allowUnlistedLandBiomes;
        this.preferredBiomes = preferredBiomes;
        this.excludedBiomes = excludedBiomes;
        this.excludedBiomeTags = excludedBiomeTags;
        this.ignoredConfigEntries = ignoredConfigEntries;
        this.ineffectiveConfigEntries = ineffectiveConfigEntries;
    }

    /**
     * Builds the classifier, dropping anything the server's registry does not
     * have.
     *
     * <p>A modpack that lists another mod's biome and later drops that mod
     * would otherwise lose the starting structure entirely, for an entry whose
     * only effect was to name a biome that no longer exists. The dropped
     * entries are logged so a typo is still visible, and so are the additions
     * and removals that change nothing, which take the same registry to tell
     * apart.
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
        List<IneffectiveEntry> ineffective = ineffectiveEntries(
                config.biomeListEdits(),
                preferred,
                excludedTags,
                registryView);
        if (!ineffective.isEmpty()) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "{} configured biome entries have no effect: {}",
                    ineffective.size(),
                    ineffective);
        }
        return new BiomeClassifier(
                config.allowUnlistedLandBiomes(),
                preferred,
                excluded,
                excludedTags,
                Set.copyOf(ignored),
                List.copyOf(ineffective));
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

    private static List<IneffectiveEntry> ineffectiveEntries(
            BiomeListEdits edits,
            Set<ResourceLocation> preferred,
            Set<ResourceLocation> excludedTags,
            BiomeRegistryView registryView) {
        List<IneffectiveEntry> entries = new ArrayList<>();
        removals(
                PREFERRED_REMOVAL_KEY,
                edits.preferred(),
                Set.of(),
                registryView,
                entries);
        removals(
                EXCLUDED_REMOVAL_KEY,
                edits.excluded(),
                excludedTags,
                registryView,
                entries);
        for (ResourceLocation id : preferred) {
            String source = edits.preferred().added().contains(id)
                    ? PREFERRED_ADDITION_KEY
                    : BUILT_IN_PREFERRED_LIST;
            coveringTag(id, excludedTags, registryView).ifPresent(tag ->
                    entries.add(IneffectiveEntry.excludedByTag(source, id, tag)));
        }
        return entries;
    }

    /*
     * One reason per entry, the first that applies: a biome the registry does
     * not have is in no tag, and a removal that takes nothing out leaves no
     * exclusion of its own for a tag to override.
     */
    private static void removals(
            String key,
            BiomeListEdits.ListEdit edit,
            Set<ResourceLocation> excludedTags,
            BiomeRegistryView registryView,
            List<IneffectiveEntry> entries) {
        for (ResourceLocation id : edit.removed()) {
            if (!registryView.containsBiome(id)) {
                entries.add(IneffectiveEntry.of(key, id, Reason.NOT_IN_REGISTRY));
            } else if (edit.added().contains(id)) {
                entries.add(IneffectiveEntry.of(key, id, Reason.ALSO_ADDED));
            } else if (!edit.listed().contains(id)) {
                entries.add(IneffectiveEntry.of(key, id, Reason.NOT_IN_LIST));
            } else {
                coveringTag(id, excludedTags, registryView).ifPresent(tag ->
                        entries.add(IneffectiveEntry.excludedByTag(key, id, tag)));
            }
        }
    }

    private static Optional<ResourceLocation> coveringTag(
            ResourceLocation biomeId,
            Set<ResourceLocation> excludedTags,
            BiomeRegistryView registryView) {
        for (ResourceLocation tag : excludedTags) {
            if (registryView.biomeHasTag(biomeId, tag)) {
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    /** The configured entries this server's registry does not have. */
    public Set<ResourceLocation> ignoredConfigEntries() {
        return ignoredConfigEntries;
    }

    /** The configured additions and removals that change nothing, in log order. */
    public List<IneffectiveEntry> ineffectiveConfigEntries() {
        return ineffectiveConfigEntries;
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

            @Override
            public boolean biomeHasTag(ResourceLocation biomeId, ResourceLocation tagId) {
                return biomeRegistry.getHolder(ResourceKey.create(Registries.BIOME, biomeId))
                        .map(biome -> biome.is(TagKey.create(Registries.BIOME, tagId)))
                        .orElse(false);
            }
        });
    }

    /**
     * The classification of {@code biome}, worked out once per biome. A biome
     * is sampled thousands of times while a site is planned and its tags do not
     * change meanwhile; a sample naming it with other tags is classified anew.
     */
    public Classification classify(BiomeSample biome) {
        Objects.requireNonNull(biome, "biome");
        RememberedClassification remembered = classifications.get(biome.id());
        if (remembered != null && remembered.tags().equals(biome.tags())) {
            return remembered.classification();
        }
        Classification classification = classifyByLists(biome);
        classifications.put(
                biome.id(),
                new RememberedClassification(biome.tags(), classification));
        return classification;
    }

    private Classification classifyByLists(BiomeSample biome) {
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

    /**
     * A configured biome entry that changes nothing: where it was configured,
     * the biome, why, and for {@link Reason#EXCLUDED_BY_TAG} the excluded tag
     * that decides before any list.
     */
    public record IneffectiveEntry(
            String source,
            ResourceLocation id,
            Reason reason,
            Optional<ResourceLocation> excludingTag) {
        public IneffectiveEntry {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(excludingTag, "excludingTag");
        }

        private static IneffectiveEntry of(
                String source,
                ResourceLocation id,
                Reason reason) {
            return new IneffectiveEntry(source, id, reason, Optional.empty());
        }

        private static IneffectiveEntry excludedByTag(
                String source,
                ResourceLocation id,
                ResourceLocation tag) {
            return new IneffectiveEntry(
                    source,
                    id,
                    Reason.EXCLUDED_BY_TAG,
                    Optional.of(tag));
        }

        @Override
        public String toString() {
            String why = switch (reason) {
                case NOT_IN_REGISTRY -> "not in this server's registry";
                case NOT_IN_LIST -> "not in the list it is taken out of";
                case ALSO_ADDED -> "also added to the same list; the removal wins";
                case EXCLUDED_BY_TAG -> "still excluded by tag "
                        + excludingTag.map(ResourceLocation::toString).orElse("");
            };
            return source + " " + id + " (" + why + ")";
        }
    }

    private record RememberedClassification(
            Set<ResourceLocation> tags,
            Classification classification) {
    }

    public enum Reason {
        NOT_IN_REGISTRY,
        NOT_IN_LIST,
        ALSO_ADDED,
        EXCLUDED_BY_TAG
    }

    public enum Classification {
        PREFERRED,
        FALLBACK_LAND,
        EXCLUDED,
        UNLISTED
    }
}
