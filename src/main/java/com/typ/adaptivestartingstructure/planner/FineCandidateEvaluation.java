package com.typ.adaptivestartingstructure.planner;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class FineCandidateEvaluation {
    private final CoarseCandidate candidate;
    private final FineCandidatePlan plan;
    private final FineCandidateMetrics metrics;
    private final Set<FineRejectionReason> rejectionReasons;

    private FineCandidateEvaluation(
            CoarseCandidate candidate,
            FineCandidatePlan plan,
            FineCandidateMetrics metrics,
            Set<FineRejectionReason> rejectionReasons) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.plan = plan;
        this.metrics = metrics;
        EnumSet<FineRejectionReason> reasons = rejectionReasons.isEmpty()
                ? EnumSet.noneOf(FineRejectionReason.class)
                : EnumSet.copyOf(rejectionReasons);
        this.rejectionReasons = Collections.unmodifiableSet(reasons);
        if ((plan == null) != (metrics == null)) {
            throw new IllegalArgumentException(
                    "Fine plan and metrics must either both be present or both be absent");
        }
        if (reasons.isEmpty() && plan == null) {
            throw new IllegalArgumentException(
                    "An accepted fine evaluation requires plan and metrics");
        }
    }

    static FineCandidateEvaluation complete(
            CoarseCandidate candidate,
            FineCandidatePlan plan,
            FineCandidateMetrics metrics,
            Set<FineRejectionReason> rejectionReasons) {
        return new FineCandidateEvaluation(
                candidate,
                Objects.requireNonNull(plan, "plan"),
                Objects.requireNonNull(metrics, "metrics"),
                Objects.requireNonNull(rejectionReasons, "rejectionReasons"));
    }

    static FineCandidateEvaluation incomplete(
            CoarseCandidate candidate,
            Set<FineRejectionReason> rejectionReasons) {
        if (rejectionReasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "An incomplete fine evaluation requires a rejection reason");
        }
        return new FineCandidateEvaluation(
                candidate,
                null,
                null,
                Objects.requireNonNull(rejectionReasons, "rejectionReasons"));
    }

    public CoarseCandidate candidate() {
        return candidate;
    }

    public Optional<FineCandidatePlan> plan() {
        return Optional.ofNullable(plan);
    }

    public Optional<FineCandidateMetrics> metrics() {
        return Optional.ofNullable(metrics);
    }

    public Set<FineRejectionReason> rejectionReasons() {
        return rejectionReasons;
    }

    public boolean accepted() {
        return rejectionReasons.isEmpty();
    }
}
