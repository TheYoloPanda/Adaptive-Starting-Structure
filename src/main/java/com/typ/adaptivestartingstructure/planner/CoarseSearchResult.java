package com.typ.adaptivestartingstructure.planner;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CoarseSearchResult(
        List<CoarseCandidate> acceptedCandidates,
        int evaluatedCandidateCount,
        int rejectedCandidateCount,
        Map<CoarseRejectionReason, Integer> rejectionCounts) {

    public CoarseSearchResult {
        acceptedCandidates = List.copyOf(
                Objects.requireNonNull(
                        acceptedCandidates,
                        "acceptedCandidates"));
        if (evaluatedCandidateCount < 0 || rejectedCandidateCount < 0) {
            throw new IllegalArgumentException(
                    "Coarse candidate counts must not be negative");
        }
        if ((long) acceptedCandidates.size() + rejectedCandidateCount
                != evaluatedCandidateCount) {
            throw new IllegalArgumentException(
                    "Accepted and rejected coarse candidates must equal evaluated candidates");
        }

        EnumMap<CoarseRejectionReason, Integer> counts =
                new EnumMap<>(CoarseRejectionReason.class);
        long countedRejections = 0L;
        for (Map.Entry<CoarseRejectionReason, Integer> entry :
                Objects.requireNonNull(
                                rejectionCounts,
                                "rejectionCounts")
                        .entrySet()) {
            CoarseRejectionReason reason =
                    Objects.requireNonNull(
                            entry.getKey(),
                            "rejection reason");
            Integer count =
                    Objects.requireNonNull(
                            entry.getValue(),
                            "rejection count");
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Coarse rejection counts must be positive");
            }
            counts.put(reason, count);
            countedRejections += count;
        }
        if (countedRejections != rejectedCandidateCount) {
            throw new IllegalArgumentException(
                    "Coarse rejection counts must equal rejected candidates");
        }
        rejectionCounts = Collections.unmodifiableMap(counts);
    }
}
