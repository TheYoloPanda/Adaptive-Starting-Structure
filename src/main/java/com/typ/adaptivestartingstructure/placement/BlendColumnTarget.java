package com.typ.adaptivestartingstructure.placement;

public record BlendColumnTarget(
        int x,
        int z,
        double distanceFromFootprint,
        double interpolation,
        int originalGroundY,
        int targetGroundY) {

    public BlendColumnTarget {
        if (!Double.isFinite(distanceFromFootprint)
                || distanceFromFootprint < 0.0D
                || !Double.isFinite(interpolation)
                || interpolation < 0.0D
                || interpolation > 1.0D) {
            throw new IllegalArgumentException(
                    "Blend column distance/interpolation is invalid");
        }
    }

    public boolean modifiesHeight() {
        return targetGroundY != originalGroundY;
    }
}
