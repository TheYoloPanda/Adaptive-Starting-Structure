package com.typ.adaptivestartingstructure.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SitePlanningResult(
        SiteCandidate selectedCandidate,
        List<SiteCandidate> alternativeCandidates,
        SitePlanningDiagnostics diagnostics) {

    public SitePlanningResult {
        selectedCandidate =
                Objects.requireNonNull(selectedCandidate, "selectedCandidate");
        alternativeCandidates = List.copyOf(alternativeCandidates);
        if (alternativeCandidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "alternativeCandidates must not contain null values");
        }
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        int acceptedSpawnCandidates = diagnostics.spawnValidationCount()
                - diagnostics.spawnRejectedCandidateCount();
        if (acceptedSpawnCandidates
                != 1 + alternativeCandidates.size()) {
            throw new IllegalArgumentException(
                    "Planning result candidates must match accepted spawn validations");
        }
    }

    public SitePlanningResult(
            SiteCandidate selectedCandidate,
            SitePlanningDiagnostics diagnostics) {
        this(selectedCandidate, List.of(), diagnostics);
    }

    public List<SiteCandidate> candidates() {
        List<SiteCandidate> candidates =
                new ArrayList<>(1 + alternativeCandidates.size());
        candidates.add(selectedCandidate);
        candidates.addAll(alternativeCandidates);
        return List.copyOf(candidates);
    }
}
