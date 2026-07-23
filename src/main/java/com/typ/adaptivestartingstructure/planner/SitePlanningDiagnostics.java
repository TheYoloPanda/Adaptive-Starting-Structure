package com.typ.adaptivestartingstructure.planner;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record SitePlanningDiagnostics(
        int coarseEvaluationCount,
        int coarseCandidateCount,
        int coarseRejectedCandidateCount,
        int fineEvaluationCount,
        int fineAcceptedCount,
        int spawnValidationCount,
        int spawnRejectedCandidateCount,
        int generatorQueriesUsed,
        Map<CoarseRejectionReason, Integer> coarseRejectionCounts,
        Map<FineRejectionReason, Integer> fineRejectionCounts,
        Map<SpawnRejectionReason, Integer> spawnRejectionCounts) {

    public SitePlanningDiagnostics {
        if (coarseEvaluationCount < 0
                || coarseCandidateCount < 0
                || coarseRejectedCandidateCount < 0
                || fineEvaluationCount < 0
                || fineAcceptedCount < 0
                || spawnValidationCount < 0
                || spawnRejectedCandidateCount < 0
                || generatorQueriesUsed < 0) {
            throw new IllegalArgumentException(
                    "Planning diagnostic counts must not be negative");
        }
        if ((long) coarseCandidateCount + coarseRejectedCandidateCount
                        != coarseEvaluationCount
                || fineEvaluationCount > coarseCandidateCount
                || fineAcceptedCount > fineEvaluationCount
                || spawnValidationCount > fineAcceptedCount
                || spawnRejectedCandidateCount > spawnValidationCount) {
            throw new IllegalArgumentException(
                    "Planning diagnostic stage counts are inconsistent");
        }
        coarseRejectionCounts = immutableCounts(
                CoarseRejectionReason.class,
                coarseRejectionCounts);
        long countedCoarseRejections = coarseRejectionCounts.values().stream()
                .mapToLong(Integer::longValue)
                .sum();
        if (countedCoarseRejections != coarseRejectedCandidateCount) {
            throw new IllegalArgumentException(
                    "Coarse rejection counts must equal rejected candidates");
        }
        fineRejectionCounts = immutableCounts(
                FineRejectionReason.class,
                fineRejectionCounts);
        spawnRejectionCounts = immutableCounts(
                SpawnRejectionReason.class,
                spawnRejectionCounts);
    }

    private static <E extends Enum<E>> Map<E, Integer> immutableCounts(
            Class<E> keyType,
            Map<E, Integer> source) {
        EnumMap<E, Integer> copy = new EnumMap<>(keyType);
        for (Map.Entry<E, Integer> entry :
                Objects.requireNonNull(source, "rejection counts").entrySet()) {
            E reason = Objects.requireNonNull(entry.getKey(), "rejection reason");
            Integer count =
                    Objects.requireNonNull(entry.getValue(), "rejection count");
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Rejection counts must be positive");
            }
            copy.put(reason, count);
        }
        return Collections.unmodifiableMap(copy);
    }
}
