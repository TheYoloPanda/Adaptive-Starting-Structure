package com.typ.adaptivestartingstructure.placement;

public final class UnsuitableGeneratedSiteException
        extends PlacementPreparationException {
    public UnsuitableGeneratedSiteException(String message) {
        super(message);
    }

    static UnsuitableGeneratedSiteException cutFillThresholdExceeded(
            String area,
            int x,
            int z,
            int groundY,
            int targetY,
            int maximumCutDepth,
            int maximumFillDepth) {
        int signedError = groundY - targetY;
        int cutDepth = Math.max(0, signedError);
        int fillDepth = Math.max(0, -signedError);
        return new UnsuitableGeneratedSiteException(
                area
                        + " terrain exceeds cut/fill thresholds at ["
                        + x + ", " + z + "]: actual[groundY="
                        + groundY
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
                        + "]");
    }
}
