package com.typ.adaptivestartingstructure.planner;

import java.util.Objects;

public record FineCandidateMetrics(
        int footprintSampleCount,
        int perimeterSampleCount,
        int blendSampleCount,
        int totalSampleCount,
        int minimumGroundY,
        int maximumGroundY,
        double averageError,
        int maximumPerimeterError,
        int maximumCutDepth,
        int maximumFillDepth,
        double averageCut,
        double averageFill,
        int waterColumns,
        BiomeClassifier.Classification biomeClassification) {

    public FineCandidateMetrics {
        if (footprintSampleCount <= 0) {
            throw new IllegalArgumentException("footprintSampleCount must be positive");
        }
        if (perimeterSampleCount <= 0 || perimeterSampleCount > footprintSampleCount) {
            throw new IllegalArgumentException(
                    "perimeterSampleCount must be positive and not exceed footprintSampleCount");
        }
        if (blendSampleCount < 0) {
            throw new IllegalArgumentException("blendSampleCount must be non-negative");
        }
        if (totalSampleCount < footprintSampleCount
                || totalSampleCount < blendSampleCount) {
            throw new IllegalArgumentException(
                    "totalSampleCount must cover footprint and blend samples");
        }
        if (maximumGroundY < minimumGroundY) {
            throw new IllegalArgumentException(
                    "maximumGroundY must not be below minimumGroundY");
        }
        if (!Double.isFinite(averageError) || averageError < 0.0D
                || !Double.isFinite(averageCut) || averageCut < 0.0D
                || !Double.isFinite(averageFill) || averageFill < 0.0D) {
            throw new IllegalArgumentException(
                    "Average terrain metrics must be finite and non-negative");
        }
        if (maximumPerimeterError < 0
                || maximumCutDepth < 0
                || maximumFillDepth < 0) {
            throw new IllegalArgumentException(
                    "Maximum terrain metrics must be non-negative");
        }
        if (waterColumns < 0 || waterColumns > totalSampleCount) {
            throw new IllegalArgumentException(
                    "waterColumns must be between zero and totalSampleCount");
        }
        biomeClassification =
                Objects.requireNonNull(biomeClassification, "biomeClassification");
    }

    public int elevationRange() {
        return maximumGroundY - minimumGroundY;
    }

    public double waterFraction() {
        return (double) waterColumns / totalSampleCount;
    }

    public double fitCost() {
        return 4.0D * averageError
                + 6.0D * maximumPerimeterError
                + 2.0D * averageCut
                + 2.0D * averageFill
                + 100.0D * waterFraction();
    }
}
