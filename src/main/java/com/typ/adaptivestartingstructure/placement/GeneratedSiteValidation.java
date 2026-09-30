package com.typ.adaptivestartingstructure.placement;

public record GeneratedSiteValidation(
        long affectedColumns,
        int footprintColumns,
        long waterColumns,
        int minimumGroundY,
        int maximumGroundY,
        int maximumCutDepth,
        int maximumFillDepth) {

    public GeneratedSiteValidation {
        if (affectedColumns <= 0L
                || footprintColumns <= 0
                || waterColumns < 0L
                || waterColumns > affectedColumns) {
            throw new IllegalArgumentException(
                    "Generated-site column counts are invalid");
        }
        if (maximumGroundY < minimumGroundY
                || maximumCutDepth < 0
                || maximumFillDepth < 0) {
            throw new IllegalArgumentException(
                    "Generated-site terrain metrics are invalid");
        }
    }

    public double waterFraction() {
        return (double) waterColumns / affectedColumns;
    }
}
