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
    private final Optional<GeneratorQueryBudgetExceededException> budgetFailure;

    FineSearchResult(
            List<FineCandidateEvaluation> evaluations,
            List<FineCandidateEvaluation> acceptedCandidates) {
        this(evaluations, acceptedCandidates, Optional.empty());
    }

    FineSearchResult(
            List<FineCandidateEvaluation> evaluations,
            List<FineCandidateEvaluation> acceptedCandidates,
            Optional<GeneratorQueryBudgetExceededException> budgetFailure) {
        this.budgetFailure =
                Objects.requireNonNull(budgetFailure, "budgetFailure");
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

    /**
     * Adds a later band's evaluations after this one's, which keeps the
     * accepted list ordered by band and then by cost.
     */
    FineSearchResult merge(FineSearchResult later) {
        Objects.requireNonNull(later, "later");
        List<FineCandidateEvaluation> combinedEvaluations =
                new java.util.ArrayList<>(
                        evaluations.size() + later.evaluations.size());
        combinedEvaluations.addAll(evaluations);
        combinedEvaluations.addAll(later.evaluations);
        List<FineCandidateEvaluation> combinedAccepted =
                new java.util.ArrayList<>(
                        acceptedCandidates.size()
                                + later.acceptedCandidates.size());
        combinedAccepted.addAll(acceptedCandidates);
        combinedAccepted.addAll(later.acceptedCandidates);
        return new FineSearchResult(
                combinedEvaluations,
                combinedAccepted,
                budgetFailure.isPresent() ? budgetFailure : later.budgetFailure);
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

    /**
     * The failure that stopped the fine stage before every finalist was
     * evaluated, if the query budget ran out while it was running.
     */
    public Optional<GeneratorQueryBudgetExceededException> budgetFailure() {
        return budgetFailure;
    }
}
