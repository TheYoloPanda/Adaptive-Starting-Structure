package com.typ.adaptivestartingstructure.planner;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FineSearchResult {
    private final List<FineCandidateEvaluation> evaluations;
    private final List<FineCandidateEvaluation> acceptedCandidates;
    private final Map<FineRejectionReason, Integer> rejectionCounts;

    FineSearchResult(
            List<FineCandidateEvaluation> evaluations,
            List<FineCandidateEvaluation> acceptedCandidates) {
        this.evaluations = List.copyOf(evaluations);
        this.acceptedCandidates = List.copyOf(acceptedCandidates);
        if (this.acceptedCandidates.stream().anyMatch(
                evaluation -> !evaluation.accepted())) {
            throw new IllegalArgumentException(
                    "acceptedCandidates must contain only accepted evaluations");
        }
        EnumMap<FineRejectionReason, Integer> counts =
                new EnumMap<>(FineRejectionReason.class);
        for (FineCandidateEvaluation evaluation : this.evaluations) {
            Objects.requireNonNull(evaluation, "evaluation");
            for (FineRejectionReason reason : evaluation.rejectionReasons()) {
                counts.merge(reason, 1, Integer::sum);
            }
        }
        this.rejectionCounts = Collections.unmodifiableMap(counts);
    }

    public List<FineCandidateEvaluation> evaluations() {
        return evaluations;
    }

    public List<FineCandidateEvaluation> acceptedCandidates() {
        return acceptedCandidates;
    }

    public Optional<FineCandidateEvaluation> bestCandidate() {
        return acceptedCandidates.isEmpty()
                ? Optional.empty()
                : Optional.of(acceptedCandidates.getFirst());
    }

    public Map<FineRejectionReason, Integer> rejectionCounts() {
        return rejectionCounts;
    }
}
