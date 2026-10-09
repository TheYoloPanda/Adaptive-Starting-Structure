package com.typ.adaptivestartingstructure.placement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

public final class UnsuitableGeneratedSiteException
        extends PlacementPreparationException {
    private final boolean internalInconsistency;

    public UnsuitableGeneratedSiteException(String message) {
        super(message);
        this.internalInconsistency = false;
    }

    private UnsuitableGeneratedSiteException(
            String message,
            RuntimeException cause) {
        super(message, cause);
        this.internalInconsistency = true;
    }

    /**
     * A site whose terrain and template plans contradict each other: a bug,
     * not the terrain. Nothing is written by then, and what triggers it is
     * that site's own trees and ground, so it costs the site rather than a
     * world refused at every start, each of which would replan the same site.
     */
    public static UnsuitableGeneratedSiteException inconsistentPlans(
            RuntimeException cause) {
        return new UnsuitableGeneratedSiteException(
                "planning its terrain and template hit an internal inconsistency, "
                        + "a bug in Adaptive Starting Structure: " + cause,
                cause);
    }

    public boolean internalInconsistency() {
        return internalInconsistency;
    }

    static UnsuitableGeneratedSiteException cutFillThresholdExceeded(
            String area,
            TerrainColumnSnapshot column,
            int targetY,
            int maximumCutDepth,
            int maximumFillDepth) {
        return thresholdExceeded(
                area,
                column,
                targetY,
                "maximumCutDepth=" + maximumCutDepth
                        + ", maximumFillDepth=" + maximumFillDepth);
    }

    static UnsuitableGeneratedSiteException blendThresholdExceeded(
            TerrainColumnSnapshot column,
            int targetY,
            int maximumCutDepth,
            int maximumFillDepth,
            int blendDepthAllowance) {
        return thresholdExceeded(
                "Generated blend",
                column,
                targetY,
                "maximumCutDepth=" + maximumCutDepth
                        + ", maximumFillDepth=" + maximumFillDepth
                        + ", blendDepthAllowance=" + blendDepthAllowance);
    }

    private static UnsuitableGeneratedSiteException thresholdExceeded(
            String area,
            TerrainColumnSnapshot column,
            int targetY,
            String configuredLimits) {
        int signedError = column.groundY() - targetY;
        int cutDepth = Math.max(0, signedError);
        int fillDepth = Math.max(0, -signedError);
        return new UnsuitableGeneratedSiteException(
                area
                        + " terrain exceeds cut/fill thresholds at ["
                        + column.x() + ", " + column.z() + "]: actual[groundY="
                        + column.groundY()
                        + ", targetY="
                        + targetY
                        + ", cutDepth="
                        + cutDepth
                        + ", fillDepth="
                        + fillDepth
                        + "], configured["
                        + configuredLimits
                        + "]; "
                        + describeGround(
                                column.groundY(),
                                column.surfaceMaterial(),
                                column.stateAboveGround(),
                                column.surfaceY()));
    }

    /**
     * What the ground of one column was measured on. A rejection that names
     * the block tells terrain the plan could not foresee, such as a carved
     * cave, apart from a block wrongly taken for terrain, such as part of a
     * tree the classifier does not know.
     */
    static String describeGround(
            int groundY,
            BlockState ground,
            BlockState above,
            int topBlockY) {
        return "ground Y=" + groundY
                + " is " + blockId(ground)
                + " with " + blockId(above) + " above"
                + ", top block at Y=" + topBlockY;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
