package com.typ.adaptivestartingstructure.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

public record ConfigSnapshot(
        boolean enabled,
        int preferredSearchRadius,
        int maximumSearchRadius,
        int nearCandidateSpacing,
        int farCandidateSpacing,
        int maximumCoarseCandidates,
        int fineCandidateCount,
        int fineSampleStep,
        int maximumGeneratorQueries,
        int blendWidth,
        int maximumCutDepth,
        int maximumFillDepth,
        int maximumElevationRange,
        int maximumPerimeterError,
        double maximumWaterFraction,
        boolean placeTemplateEntities,
        boolean requireSafeSpawnArea,
        boolean allowUnlistedLandBiomes,
        List<Rotation> allowedRotations,
        Set<ResourceLocation> preferredBiomes,
        Set<ResourceLocation> excludedBiomes,
        Set<ResourceLocation> excludedBiomeTags,
        BlockedStateRecovery blockedStateRecovery,
        boolean placePlayerAtSpawnMarker,
        Set<ResourceLocation> ignoredStructureCollisions) {

    private static final Set<ResourceLocation> BASE_PREFERRED_BIOMES = resourceLocations(
            "minecraft:plains",
            "minecraft:sunflower_plains",
            "minecraft:forest",
            "minecraft:birch_forest",
            "minecraft:savanna",
            "minecraft:savanna_plateau",
            "minecraft:desert",
            "minecraft:badlands",
            "minecraft:wooded_badlands");

    private static final Set<ResourceLocation> BASE_EXCLUDED_BIOMES = resourceLocations(
            "minecraft:ocean",
            "minecraft:deep_ocean",
            "minecraft:warm_ocean",
            "minecraft:lukewarm_ocean",
            "minecraft:deep_lukewarm_ocean",
            "minecraft:cold_ocean",
            "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean",
            "minecraft:river",
            "minecraft:frozen_river",
            "minecraft:beach",
            "minecraft:snowy_beach",
            "minecraft:stony_shore",
            "minecraft:swamp",
            "minecraft:mangrove_swamp",
            "minecraft:windswept_hills",
            "minecraft:windswept_gravelly_hills",
            "minecraft:windswept_forest",
            "minecraft:windswept_savanna",
            "minecraft:meadow",
            "minecraft:cherry_grove",
            "minecraft:grove",
            "minecraft:snowy_slopes",
            "minecraft:jagged_peaks",
            "minecraft:frozen_peaks",
            "minecraft:stony_peaks");

    /**
     * A snapshot that refuses to start a world stuck in a blocked state and
     * trusts the declared bounds of every generated structure.
     */
    public ConfigSnapshot(
            boolean enabled,
            int preferredSearchRadius,
            int maximumSearchRadius,
            int nearCandidateSpacing,
            int farCandidateSpacing,
            int maximumCoarseCandidates,
            int fineCandidateCount,
            int fineSampleStep,
            int maximumGeneratorQueries,
            int blendWidth,
            int maximumCutDepth,
            int maximumFillDepth,
            int maximumElevationRange,
            int maximumPerimeterError,
            double maximumWaterFraction,
            boolean placeTemplateEntities,
            boolean requireSafeSpawnArea,
            boolean allowUnlistedLandBiomes,
            List<Rotation> allowedRotations,
            Set<ResourceLocation> preferredBiomes,
            Set<ResourceLocation> excludedBiomes,
            Set<ResourceLocation> excludedBiomeTags) {
        this(
                enabled,
                preferredSearchRadius,
                maximumSearchRadius,
                nearCandidateSpacing,
                farCandidateSpacing,
                maximumCoarseCandidates,
                fineCandidateCount,
                fineSampleStep,
                maximumGeneratorQueries,
                blendWidth,
                maximumCutDepth,
                maximumFillDepth,
                maximumElevationRange,
                maximumPerimeterError,
                maximumWaterFraction,
                placeTemplateEntities,
                requireSafeSpawnArea,
                allowUnlistedLandBiomes,
                allowedRotations,
                preferredBiomes,
                excludedBiomes,
                excludedBiomeTags,
                BlockedStateRecovery.BLOCK,
                ModConfig.DEFAULT_PLACE_PLAYER_AT_SPAWN_MARKER,
                Set.of());
    }

    public ConfigSnapshot {
        Objects.requireNonNull(blockedStateRecovery, "blockedStateRecovery");
        validateRange("preferredSearchRadius", preferredSearchRadius, 0, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange("maximumSearchRadius", maximumSearchRadius, 0, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange("nearCandidateSpacing", nearCandidateSpacing, 1, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange("farCandidateSpacing", farCandidateSpacing, 1, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange(
                "maximumCoarseCandidates",
                maximumCoarseCandidates,
                1,
                ModConfig.MAX_COARSE_CANDIDATES);
        validateRange(
                "fineCandidateCount",
                fineCandidateCount,
                1,
                ModConfig.MAX_FINE_CANDIDATE_COUNT);
        validateRange("fineSampleStep", fineSampleStep, 1, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange(
                "maximumGeneratorQueries",
                maximumGeneratorQueries,
                1,
                ModConfig.MAX_GENERATOR_QUERIES);
        validateRange(
                "blendWidth",
                blendWidth,
                1,
                ModConfig.MAX_BLEND_WIDTH);
        validateRange("maximumCutDepth", maximumCutDepth, 0, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange("maximumFillDepth", maximumFillDepth, 0, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange("maximumElevationRange", maximumElevationRange, 0, ModConfig.MAX_BLOCK_DISTANCE);
        validateRange("maximumPerimeterError", maximumPerimeterError, 0, ModConfig.MAX_BLOCK_DISTANCE);
        if (!Double.isFinite(maximumWaterFraction)
                || maximumWaterFraction < 0.0D
                || maximumWaterFraction > 1.0D) {
            throw invalid("maximumWaterFraction", "must be between 0.0 and 1.0");
        }

        if (preferredSearchRadius > maximumSearchRadius) {
            throw invalid(
                    "preferredSearchRadius",
                    "must not exceed maximumSearchRadius (" + maximumSearchRadius + ")");
        }
        if (nearCandidateSpacing > farCandidateSpacing) {
            throw invalid(
                    "nearCandidateSpacing",
                    "must not exceed farCandidateSpacing (" + farCandidateSpacing + ")");
        }
        if (fineCandidateCount > maximumCoarseCandidates) {
            throw invalid(
                    "fineCandidateCount",
                    "must not exceed maximumCoarseCandidates (" + maximumCoarseCandidates + ")");
        }

        allowedRotations = immutableDistinctList("allowedRotations", allowedRotations);
        if (allowedRotations.isEmpty()) {
            throw invalid("allowedRotations", "must contain at least one rotation");
        }
        if (maximumCoarseCandidates < allowedRotations.size()) {
            throw invalid(
                    "maximumCoarseCandidates",
                    "must be at least the number of allowedRotations ("
                            + allowedRotations.size() + ")");
        }
        preferredBiomes = immutableSet("preferredBiomes", preferredBiomes);
        excludedBiomes = immutableSet("excludedBiomes", excludedBiomes);
        excludedBiomeTags = immutableSet("excludedBiomeTags", excludedBiomeTags);
        ignoredStructureCollisions = immutableSet(
                "ignoredStructureCollisions",
                ignoredStructureCollisions);

        LinkedHashSet<ResourceLocation> conflictingBiomes = new LinkedHashSet<>(preferredBiomes);
        conflictingBiomes.retainAll(excludedBiomes);
        if (!conflictingBiomes.isEmpty()) {
            throw invalid(
                    "additionalPreferredBiomes/additionalExcludedBiomes",
                    "biomes cannot be both preferred and excluded: " + conflictingBiomes);
        }
    }

    public ConfigSnapshot(
            boolean enabled,
            int preferredSearchRadius,
            int maximumSearchRadius,
            int nearCandidateSpacing,
            int farCandidateSpacing,
            int maximumCoarseCandidates,
            int fineCandidateCount,
            int fineSampleStep,
            int maximumGeneratorQueries,
            int blendWidth,
            int maximumCutDepth,
            int maximumFillDepth,
            int maximumElevationRange,
            int maximumPerimeterError,
            double maximumWaterFraction,
            boolean placeTemplateEntities,
            boolean allowUnlistedLandBiomes,
            List<Rotation> allowedRotations,
            Set<ResourceLocation> preferredBiomes,
            Set<ResourceLocation> excludedBiomes,
            Set<ResourceLocation> excludedBiomeTags) {
        this(
                enabled,
                preferredSearchRadius,
                maximumSearchRadius,
                nearCandidateSpacing,
                farCandidateSpacing,
                maximumCoarseCandidates,
                fineCandidateCount,
                fineSampleStep,
                maximumGeneratorQueries,
                blendWidth,
                maximumCutDepth,
                maximumFillDepth,
                maximumElevationRange,
                maximumPerimeterError,
                maximumWaterFraction,
                placeTemplateEntities,
                true,
                allowUnlistedLandBiomes,
                allowedRotations,
                preferredBiomes,
                excludedBiomes,
                excludedBiomeTags);
    }

    static ConfigSnapshot parse(
            boolean enabled,
            int preferredSearchRadius,
            int maximumSearchRadius,
            int nearCandidateSpacing,
            int farCandidateSpacing,
            int maximumCoarseCandidates,
            int fineCandidateCount,
            int fineSampleStep,
            int maximumGeneratorQueries,
            int blendWidth,
            int maximumCutDepth,
            int maximumFillDepth,
            int maximumElevationRange,
            int maximumPerimeterError,
            double maximumWaterFraction,
            boolean placeTemplateEntities,
            boolean requireSafeSpawnArea,
            boolean allowUnlistedLandBiomes,
            List<? extends String> excludedBiomeTags,
            List<? extends String> allowedRotations,
            List<? extends String> additionalPreferredBiomes,
            List<? extends String> additionalExcludedBiomes,
            List<? extends String> removedPreferredBiomes,
            List<? extends String> removedExcludedBiomes,
            String blockedStateRecovery,
            boolean placePlayerAtSpawnMarker,
            List<? extends String> ignoredStructureCollisions) {
        Set<ResourceLocation> preferredBiomes = withoutRemoved(
                mergeAdditional(
                        "additionalPreferredBiomes",
                        BASE_PREFERRED_BIOMES,
                        parseResourceLocations(
                                "additionalPreferredBiomes",
                                additionalPreferredBiomes)),
                parseResourceLocations(
                        "removedPreferredBiomes",
                        removedPreferredBiomes));
        Set<ResourceLocation> excludedBiomes = withoutRemoved(
                mergeAdditional(
                        "additionalExcludedBiomes",
                        BASE_EXCLUDED_BIOMES,
                        parseResourceLocations(
                                "additionalExcludedBiomes",
                                additionalExcludedBiomes)),
                parseResourceLocations(
                        "removedExcludedBiomes",
                        removedExcludedBiomes));
        Set<ResourceLocation> allExcludedTags =
                parseResourceLocations("excludedBiomeTags", excludedBiomeTags);

        return new ConfigSnapshot(
                enabled,
                preferredSearchRadius,
                maximumSearchRadius,
                nearCandidateSpacing,
                farCandidateSpacing,
                maximumCoarseCandidates,
                fineCandidateCount,
                fineSampleStep,
                maximumGeneratorQueries,
                blendWidth,
                maximumCutDepth,
                maximumFillDepth,
                maximumElevationRange,
                maximumPerimeterError,
                maximumWaterFraction,
                placeTemplateEntities,
                requireSafeSpawnArea,
                allowUnlistedLandBiomes,
                parseRotations(allowedRotations),
                preferredBiomes,
                excludedBiomes,
                allExcludedTags,
                BlockedStateRecovery.fromConfigValue(blockedStateRecovery),
                placePlayerAtSpawnMarker,
                parseResourceLocations(
                        "ignoredStructureCollisions",
                        ignoredStructureCollisions));
    }

    static boolean isKnownBlockedStateRecovery(Object value) {
        if (!(value instanceof String name)) {
            return false;
        }
        try {
            BlockedStateRecovery.fromConfigValue(name);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean isKnownRotation(Object value) {
        return value instanceof String name && rotation(name) != null;
    }

    static boolean isResourceLocation(Object value) {
        return value instanceof String id && parseResourceLocation(id) != null;
    }

    private static List<Rotation> parseRotations(List<? extends String> values) {
        List<Rotation> rotations = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            Rotation rotation = rotation(value);
            if (rotation == null) {
                throw invalid(
                        "allowedRotations[" + index + "]",
                        "unknown rotation '" + value
                                + "'; expected none, clockwise_90, clockwise_180, or counterclockwise_90");
            }
            rotations.add(rotation);
        }
        return rotations;
    }

    private static Rotation rotation(String value) {
        return switch (value) {
            case "none" -> Rotation.NONE;
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> null;
        };
    }

    private static Set<ResourceLocation> parseResourceLocations(
            String key,
            List<? extends String> values) {
        LinkedHashSet<ResourceLocation> parsed = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            ResourceLocation location = parseResourceLocation(value);
            if (location == null) {
                throw invalid(key + "[" + index + "]", "invalid resource location '" + value + "'");
            }
            if (!parsed.add(location)) {
                throw invalid(key, "contains duplicate resource location '" + location + "'");
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

    private static ResourceLocation parseResourceLocation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(value);
        return location == null || location.getPath().isEmpty() ? null : location;
    }

    /*
     * Naming something the defaults already name is redundant, not wrong, and
     * refusing the whole configuration over it costs the world its structure.
     */
    private static Set<ResourceLocation> mergeAdditional(
            String key,
            Set<ResourceLocation> base,
            Set<ResourceLocation> additional) {
        LinkedHashSet<ResourceLocation> merged = new LinkedHashSet<>(base);
        merged.addAll(additional);
        return Collections.unmodifiableSet(merged);
    }

    /**
     * The set without the entries a pack asked to take out.
     *
     * <p>The default biome lists are the mod's opinion, not a rule: a pack that
     * wants its starting structure in meadows, or never in badlands, has to be
     * able to say so, and there is no other way to reach an entry the mod
     * hardcodes.
     */
    private static Set<ResourceLocation> withoutRemoved(
            Set<ResourceLocation> merged,
            Set<ResourceLocation> removed) {
        if (removed.isEmpty()) {
            return merged;
        }
        LinkedHashSet<ResourceLocation> retained = new LinkedHashSet<>(merged);
        retained.removeAll(removed);
        return Collections.unmodifiableSet(retained);
    }

    private static <T> List<T> immutableDistinctList(String key, List<T> values) {
        List<T> copy = List.copyOf(values);
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw invalid(key, "must not contain duplicate values");
        }
        return copy;
    }

    private static <T> Set<T> immutableSet(String key, Set<T> values) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw invalid(key, "must not contain null values");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static void validateRange(String key, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw invalid(key, "must be between " + minimum + " and " + maximum + ", got " + value);
        }
    }

    private static IllegalArgumentException invalid(String key, String message) {
        return new IllegalArgumentException(
                "Invalid adaptive_starting_structure config: " + key + " " + message);
    }

    private static Set<ResourceLocation> resourceLocations(String... ids) {
        LinkedHashSet<ResourceLocation> locations = new LinkedHashSet<>(ids.length);
        for (String id : ids) {
            locations.add(ResourceLocation.parse(id));
        }
        return Collections.unmodifiableSet(locations);
    }
}
