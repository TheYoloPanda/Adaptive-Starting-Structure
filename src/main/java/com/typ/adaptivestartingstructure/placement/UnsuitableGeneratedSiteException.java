package com.typ.adaptivestartingstructure.placement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

public final class UnsuitableGeneratedSiteException
        extends PlacementPreparationException {
    public UnsuitableGeneratedSiteException(String message) {
        super(message);
    }

    static UnsuitableGeneratedSiteException cutFillThresholdExceeded(
            String area,
            TerrainColumnSnapshot column,
            int targetY,
            int maximumCutDepth,
            int maximumFillDepth) {
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
                        + "], configured[maximumCutDepth="
                        + maximumCutDepth
                        + ", maximumFillDepth="
                        + maximumFillDepth
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
