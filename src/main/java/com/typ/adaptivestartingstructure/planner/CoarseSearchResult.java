package com.typ.adaptivestartingstructure.planner;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record CoarseSearchResult(
        List<CoarseCandidate> acceptedCandidates,
        int evaluatedCandidateCount,
        int rejectedCandidateCount,
        Map<CoarseRejectionReason, Integer> rejectionCounts,
        Optional<GeneratorQueryBudgetExceededException> budgetFailure) {

    public CoarseSearchResult(
            List<CoarseCandidate> acceptedCandidates,
            int evaluatedCandidateCount,
            int rejectedCandidateCount,
            Map<CoarseRejectionReason, Integer> rejectionCounts) {
        this(
                acceptedCandidates,
                evaluatedCandidateCount,
                rejectedCandidateCount,
                rejectionCounts,
                Optional.empty());
    }

    /** The same result carrying a reordered or filtered candidate list. */
    public CoarseSearchResult withCandidates(
            List<CoarseCandidate> replacement) {
        return new CoarseSearchResult(
                replacement,
                evaluatedCandidateCount,
                rejectedCandidateCount,
                rejectionCounts,
                budgetFailure);
    }

    /** Adds the counts of a later band to this one, keeping its order. */
    public CoarseSearchResult merge(CoarseSearchResult later) {
        Objects.requireNonNull(later, "later");
        List<CoarseCandidate> combined = new java.util.ArrayList<>(
                acceptedCandidates.size() + later.acceptedCandidates.size());
        combined.addAll(acceptedCandidates);
        combined.addAll(later.acceptedCandidates);
        EnumMap<CoarseRejectionReason, Integer> counts =
                new EnumMap<>(CoarseRejectionReason.class);
        counts.putAll(rejectionCounts);
        later.rejectionCounts.forEach(
                (reason, count) -> counts.merge(reason, count, Integer::sum));
        return new CoarseSearchResult(
                combined,
                Math.addExact(evaluatedCandidateCount, later.evaluatedCandidateCount),
                Math.addExact(rejectedCandidateCount, later.rejectedCandidateCount),
                counts,
                budgetFailure.isPresent() ? budgetFailure : later.budgetFailure);
    }

    public CoarseSearchResult {
        Objects.requireNonNull(budgetFailure, "budgetFailure");
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
