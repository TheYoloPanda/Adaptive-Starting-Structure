package com.typ.adaptivestartingstructure.config;

import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModConfig {
    public static final String FILE_NAME = "adaptive_starting_structure-common.toml";

    static final int MAX_BLOCK_DISTANCE = 30_000_000;

    static final boolean DEFAULT_ENABLED = true;
    static final int DEFAULT_PREFERRED_SEARCH_RADIUS = 1024;
    static final int DEFAULT_MAXIMUM_SEARCH_RADIUS = 4096;
    static final int DEFAULT_NEAR_CANDIDATE_SPACING = 64;
    static final int DEFAULT_FAR_CANDIDATE_SPACING = 128;
    static final int DEFAULT_MAXIMUM_COARSE_CANDIDATES = 4096;
    static final int DEFAULT_FINE_CANDIDATE_COUNT = 32;
    static final int DEFAULT_FINE_SAMPLE_STEP = 4;
    static final int DEFAULT_MAXIMUM_GENERATOR_QUERIES = 150_000;
    static final int DEFAULT_BLEND_WIDTH = 24;
    static final int DEFAULT_MAXIMUM_CUT_DEPTH = 8;
    static final int DEFAULT_MAXIMUM_FILL_DEPTH = 8;
    static final int DEFAULT_MAXIMUM_ELEVATION_RANGE = 12;
    static final int DEFAULT_MAXIMUM_PERIMETER_ERROR = 4;
    static final double DEFAULT_MAXIMUM_WATER_FRACTION = 0.05D;
    static final boolean DEFAULT_PLACE_TEMPLATE_ENTITIES = false;
    static final boolean DEFAULT_REQUIRE_SAFE_SPAWN_AREA = false;
    static final boolean DEFAULT_ALLOW_UNLISTED_LAND_BIOMES = true;
    static final List<String> DEFAULT_EXCLUDED_BIOME_TAGS = List.of(
            "minecraft:is_ocean",
            "minecraft:is_river",
            "minecraft:is_beach",
            "minecraft:is_mountain");
    static final List<String> DEFAULT_ALLOWED_ROTATIONS = List.of(
            "none",
            "clockwise_90",
            "clockwise_180",
            "counterclockwise_90");

    private static final Values VALUES;
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        VALUES = new Values(builder);
        SPEC = builder.build();
    }

    private ModConfig() {
    }

    public static void register(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, SPEC, FILE_NAME);
        modEventBus.addListener(ModConfig::onConfigLoading);
        modEventBus.addListener(ModConfig::onConfigReloading);
    }

    public static ConfigSnapshot snapshot() {
        return VALUES.snapshot(false);
    }

    static ConfigSnapshot defaultSnapshot() {
        return VALUES.snapshot(true);
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        validateOwnedConfig(event);
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        validateOwnedConfig(event);
    }

    private static void validateOwnedConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            snapshot();
        }
    }

    private static final class Values {
        private final ModConfigSpec.BooleanValue enabled;
        private final ModConfigSpec.IntValue preferredSearchRadius;
        private final ModConfigSpec.IntValue maximumSearchRadius;
        private final ModConfigSpec.IntValue nearCandidateSpacing;
        private final ModConfigSpec.IntValue farCandidateSpacing;
        private final ModConfigSpec.IntValue maximumCoarseCandidates;
        private final ModConfigSpec.IntValue fineCandidateCount;
        private final ModConfigSpec.IntValue fineSampleStep;
        private final ModConfigSpec.IntValue maximumGeneratorQueries;
        private final ModConfigSpec.IntValue blendWidth;
        private final ModConfigSpec.IntValue maximumCutDepth;
        private final ModConfigSpec.IntValue maximumFillDepth;
        private final ModConfigSpec.IntValue maximumElevationRange;
        private final ModConfigSpec.IntValue maximumPerimeterError;
        private final ModConfigSpec.DoubleValue maximumWaterFraction;
        private final ModConfigSpec.BooleanValue placeTemplateEntities;
        private final ModConfigSpec.BooleanValue requireSafeSpawnArea;
        private final ModConfigSpec.BooleanValue allowUnlistedLandBiomes;
        private final ModConfigSpec.ConfigValue<List<? extends String>> excludedBiomeTags;
        private final ModConfigSpec.ConfigValue<List<? extends String>> allowedRotations;
        private final ModConfigSpec.ConfigValue<List<? extends String>> additionalPreferredBiomes;
        private final ModConfigSpec.ConfigValue<List<? extends String>> additionalExcludedBiomes;
        private final ModConfigSpec.ConfigValue<List<? extends String>> additionalExcludedBiomeTags;

        private Values(ModConfigSpec.Builder builder) {
            enabled = builder
                    .comment("Whether starting structure planning and placement are enabled.")
                    .define("enabled", DEFAULT_ENABLED);
            preferredSearchRadius = builder
                    .comment("Preferred search radius in blocks around the theoretical spawn.")
                    .defineInRange(
                            "preferredSearchRadius",
                            DEFAULT_PREFERRED_SEARCH_RADIUS,
                            0,
                            MAX_BLOCK_DISTANCE);
            maximumSearchRadius = builder
                    .comment("Maximum search radius in blocks around the theoretical spawn.")
                    .defineInRange(
                            "maximumSearchRadius",
                            DEFAULT_MAXIMUM_SEARCH_RADIUS,
                            0,
                            MAX_BLOCK_DISTANCE);
            nearCandidateSpacing = builder
                    .comment("Candidate spacing in blocks inside the preferred search radius.")
                    .defineInRange(
                            "nearCandidateSpacing",
                            DEFAULT_NEAR_CANDIDATE_SPACING,
                            1,
                            MAX_BLOCK_DISTANCE);
            farCandidateSpacing = builder
                    .comment("Candidate spacing in blocks outside the preferred search radius.")
                    .defineInRange(
                            "farCandidateSpacing",
                            DEFAULT_FAR_CANDIDATE_SPACING,
                            1,
                            MAX_BLOCK_DISTANCE);
            maximumCoarseCandidates = builder
                    .comment("Maximum number of candidates considered by the coarse search.")
                    .defineInRange(
                            "maximumCoarseCandidates",
                            DEFAULT_MAXIMUM_COARSE_CANDIDATES,
                            1,
                            Integer.MAX_VALUE);
            fineCandidateCount = builder
                    .comment("Number of top coarse candidates evaluated by the fine search.")
                    .defineInRange(
                            "fineCandidateCount",
                            DEFAULT_FINE_CANDIDATE_COUNT,
                            1,
                            Integer.MAX_VALUE);
            fineSampleStep = builder
                    .comment("Sampling step in blocks during fine evaluation.")
                    .defineInRange(
                            "fineSampleStep",
                            DEFAULT_FINE_SAMPLE_STEP,
                            1,
                            MAX_BLOCK_DISTANCE);
            maximumGeneratorQueries = builder
                    .comment("Maximum generator queries allowed for one planning operation.")
                    .defineInRange(
                            "maximumGeneratorQueries",
                            DEFAULT_MAXIMUM_GENERATOR_QUERIES,
                            1,
                            Integer.MAX_VALUE);
            blendWidth = builder
                    .comment("Terrain blend width in blocks outside the structure footprint.")
                    .defineInRange("blendWidth", DEFAULT_BLEND_WIDTH, 1, MAX_BLOCK_DISTANCE);
            maximumCutDepth = builder
                    .comment("Maximum number of blocks that terrain may be cut.")
                    .defineInRange("maximumCutDepth", DEFAULT_MAXIMUM_CUT_DEPTH, 0, MAX_BLOCK_DISTANCE);
            maximumFillDepth = builder
                    .comment("Maximum number of blocks that terrain may be filled.")
                    .defineInRange("maximumFillDepth", DEFAULT_MAXIMUM_FILL_DEPTH, 0, MAX_BLOCK_DISTANCE);
            maximumElevationRange = builder
                    .comment("Maximum elevation range allowed across the evaluated site.")
                    .defineInRange(
                            "maximumElevationRange",
                            DEFAULT_MAXIMUM_ELEVATION_RANGE,
                            0,
                            MAX_BLOCK_DISTANCE);
            maximumPerimeterError = builder
                    .comment("Maximum elevation error allowed at the blended perimeter.")
                    .defineInRange(
                            "maximumPerimeterError",
                            DEFAULT_MAXIMUM_PERIMETER_ERROR,
                            0,
                            MAX_BLOCK_DISTANCE);
            maximumWaterFraction = builder
                    .comment("Maximum fraction of sampled columns that may contain water.")
                    .defineInRange("maximumWaterFraction", DEFAULT_MAXIMUM_WATER_FRACTION, 0.0D, 1.0D);
            placeTemplateEntities = builder
                    .comment("Whether entities stored in the structure template are placed.")
                    .define("placeTemplateEntities", DEFAULT_PLACE_TEMPLATE_ENTITIES);
            requireSafeSpawnArea = builder
                    .comment(
                            "Whether every column in the vanilla spawnRadius area must be safe. "
                                    + "When false, only the exact spawn marker is validated.")
                    .define("requireSafeSpawnArea", DEFAULT_REQUIRE_SAFE_SPAWN_AREA);
            allowUnlistedLandBiomes = builder
                    .comment("Whether non-preferred land biomes may be accepted with lower priority.")
                    .define("allowUnlistedLandBiomes", DEFAULT_ALLOW_UNLISTED_LAND_BIOMES);
            excludedBiomeTags = builder
                    .comment("Biome tags excluded from site selection.")
                    .defineListAllowEmpty(
                            "excludedBiomeTags",
                            DEFAULT_EXCLUDED_BIOME_TAGS,
                            () -> "minecraft:is_ocean",
                            ConfigSnapshot::isResourceLocation);
            allowedRotations = builder
                    .comment("Horizontal rotations that may be selected for the structure.")
                    .defineList(
                            "allowedRotations",
                            DEFAULT_ALLOWED_ROTATIONS,
                            () -> "none",
                            ConfigSnapshot::isKnownRotation);
            additionalPreferredBiomes = builder
                    .comment("Additional biome IDs treated as preferred.")
                    .defineListAllowEmpty(
                            "additionalPreferredBiomes",
                            List.of(),
                            () -> "example:preferred_biome",
                            ConfigSnapshot::isResourceLocation);
            additionalExcludedBiomes = builder
                    .comment("Additional biome IDs excluded from site selection.")
                    .defineListAllowEmpty(
                            "additionalExcludedBiomes",
                            List.of(),
                            () -> "example:excluded_biome",
                            ConfigSnapshot::isResourceLocation);
            additionalExcludedBiomeTags = builder
                    .comment("Additional biome tag IDs excluded from site selection.")
                    .defineListAllowEmpty(
                            "additionalExcludedBiomeTags",
                            List.of(),
                            () -> "example:excluded_biomes",
                            ConfigSnapshot::isResourceLocation);
        }

        private ConfigSnapshot snapshot(boolean defaults) {
            return ConfigSnapshot.parse(
                    read(enabled, defaults),
                    read(preferredSearchRadius, defaults),
                    read(maximumSearchRadius, defaults),
                    read(nearCandidateSpacing, defaults),
                    read(farCandidateSpacing, defaults),
                    read(maximumCoarseCandidates, defaults),
                    read(fineCandidateCount, defaults),
                    read(fineSampleStep, defaults),
                    read(maximumGeneratorQueries, defaults),
                    read(blendWidth, defaults),
                    read(maximumCutDepth, defaults),
                    read(maximumFillDepth, defaults),
                    read(maximumElevationRange, defaults),
                    read(maximumPerimeterError, defaults),
                    read(maximumWaterFraction, defaults),
                    read(placeTemplateEntities, defaults),
                    read(requireSafeSpawnArea, defaults),
                    read(allowUnlistedLandBiomes, defaults),
                    read(excludedBiomeTags, defaults),
                    read(allowedRotations, defaults),
                    read(additionalPreferredBiomes, defaults),
                    read(additionalExcludedBiomes, defaults),
                    read(additionalExcludedBiomeTags, defaults));
        }

        private static <T> T read(ModConfigSpec.ConfigValue<T> value, boolean defaults) {
            return defaults ? value.getDefault() : value.get();
        }
    }
}
