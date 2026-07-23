package com.typ.adaptivestartingstructure.planner;

import java.util.Objects;

public record CoarseCandidateMetrics(
        int minimumGroundY,
        int maximumGroundY,
        double averageGroundY,
        double meanAbsoluteGroundError,
        int sampledColumns,
        int fluidColumns,
        BiomeClassifier.Classification biomeClassification) {

    public CoarseCandidateMetrics {
        if (maximumGroundY < minimumGroundY) {
            throw new IllegalArgumentException(
                    "maximumGroundY must not be below minimumGroundY");
        }
        if (!Double.isFinite(averageGroundY)) {
            throw new IllegalArgumentException("averageGroundY must be finite");
        }
        if (!Double.isFinite(meanAbsoluteGroundError) || meanAbsoluteGroundError < 0.0D) {
            throw new IllegalArgumentException(
                    "meanAbsoluteGroundError must be finite and non-negative");
        }
        if (sampledColumns <= 0) {
            throw new IllegalArgumentException("sampledColumns must be positive");
        }
        if (fluidColumns < 0 || fluidColumns > sampledColumns) {
            throw new IllegalArgumentException(
                    "fluidColumns must be between zero and sampledColumns");
        }
        biomeClassification =
                Objects.requireNonNull(biomeClassification, "biomeClassification");
        if (biomeClassification == BiomeClassifier.Classification.EXCLUDED
                || biomeClassification == BiomeClassifier.Classification.UNLISTED) {
            throw new IllegalArgumentException(
                    "Accepted coarse metrics require an allowed biome classification");
        }
    }

    public int elevationRange() {
        return maximumGroundY - minimumGroundY;
    }

    public double fluidFraction() {
        return (double) fluidColumns / sampledColumns;
    }
}
