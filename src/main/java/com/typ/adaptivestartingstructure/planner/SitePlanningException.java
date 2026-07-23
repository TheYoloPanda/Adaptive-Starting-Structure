package com.typ.adaptivestartingstructure.planner;

import java.util.Objects;

public final class SitePlanningException extends RuntimeException {
    private final SitePlanningDiagnostics diagnostics;

    SitePlanningException(SitePlanningDiagnostics diagnostics) {
        super(message(Objects.requireNonNull(diagnostics, "diagnostics")));
        this.diagnostics = diagnostics;
    }

    public SitePlanningDiagnostics diagnostics() {
        return diagnostics;
    }

    private static String message(SitePlanningDiagnostics diagnostics) {
        return "No valid starting-structure site found: coarseEvaluated="
                + diagnostics.coarseEvaluationCount()
                + ", coarseAccepted="
                + diagnostics.coarseCandidateCount()
                + ", coarseRejected="
                + diagnostics.coarseRejectedCandidateCount()
                + ", fineEvaluated="
                + diagnostics.fineEvaluationCount()
                + ", fineAccepted="
                + diagnostics.fineAcceptedCount()
                + ", spawnValidated="
                + diagnostics.spawnValidationCount()
                + ", coarseRejections="
                + diagnostics.coarseRejectionCounts()
                + ", fineRejections="
                + diagnostics.fineRejectionCounts()
                + ", spawnRejections="
                + diagnostics.spawnRejectionCounts();
    }
}
