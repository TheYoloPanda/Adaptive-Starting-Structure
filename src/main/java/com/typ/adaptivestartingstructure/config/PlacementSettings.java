package com.typ.adaptivestartingstructure.config;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.Rotation;

/**
 * The configuration a planned site was selected under, stored with the plan.
 *
 * <p>Planning and placement happen in two different sessions: a world can be
 * created, returned to the world list, reconfigured and only then loaded for
 * the first time. Re-reading these values at placement would judge a finished
 * plan by rules it was never planned under, which fails the placement and
 * leaves the world unusable. Values that do not take part in choosing a site,
 * such as whether template entities are placed, are deliberately absent and
 * keep following the live configuration.
 */
public record PlacementSettings(
        int blendWidth,
        int maximumCutDepth,
        int maximumFillDepth,
        int maximumElevationRange,
        int maximumPerimeterError,
        double maximumWaterFraction,
        List<Rotation> allowedRotations) {

    public PlacementSettings {
        allowedRotations = List.copyOf(
                Objects.requireNonNull(allowedRotations, "allowedRotations"));
        if (allowedRotations.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedRotations must contain at least one rotation");
        }
    }

    public static PlacementSettings from(ConfigSnapshot config) {
        Objects.requireNonNull(config, "config");
        return new PlacementSettings(
                config.blendWidth(),
                config.maximumCutDepth(),
                config.maximumFillDepth(),
                config.maximumElevationRange(),
                config.maximumPerimeterError(),
                config.maximumWaterFraction(),
                config.allowedRotations());
    }

    /**
     * The live configuration with every planning-time value restored, so that
     * placement reads one snapshot and no caller has to remember which of its
     * fields are authoritative.
     */
    public ConfigSnapshot applyTo(ConfigSnapshot config) {
        Objects.requireNonNull(config, "config");
        return new ConfigSnapshot(
                config.enabled(),
                config.preferredSearchRadius(),
                config.maximumSearchRadius(),
                config.nearCandidateSpacing(),
                config.farCandidateSpacing(),
                /*
                 * Search sizing plays no part in placement, but the snapshot
                 * refuses to hold fewer coarse candidates than rotations, and
                 * the live value may have shrunk since planning.
                 */
                Math.max(
                        config.maximumCoarseCandidates(),
                        allowedRotations.size()),
                config.fineCandidateCount(),
                config.fineSampleStep(),
                config.maximumGeneratorQueries(),
                blendWidth,
                maximumCutDepth,
                maximumFillDepth,
                maximumElevationRange,
                maximumPerimeterError,
                maximumWaterFraction,
                config.placeTemplateEntities(),
                config.requireSafeSpawnArea(),
                config.allowUnlistedLandBiomes(),
                allowedRotations,
                config.preferredBiomes(),
                config.excludedBiomes(),
                config.excludedBiomeTags(),
                config.blockedStateRecovery(),
                config.placePlayerAtSpawnMarker(),
                config.ignoredStructureCollisions(),
                config.blendDepthAllowance());
    }
}
