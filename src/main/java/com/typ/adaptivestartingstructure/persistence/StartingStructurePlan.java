package com.typ.adaptivestartingstructure.persistence;

import com.typ.adaptivestartingstructure.config.PlacementSettings;
import com.typ.adaptivestartingstructure.planner.SiteCandidate;
import com.typ.adaptivestartingstructure.planner.SitePlanningDiagnostics;
import com.typ.adaptivestartingstructure.planner.SitePlanningResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record StartingStructurePlan(
        long selectionSeed,
        long selectionSalt,
        int selectionAlgorithmVersion,
        SiteCandidate candidate,
        List<SiteCandidate> alternativeCandidates,
        SitePlanningDiagnostics diagnostics,
        Optional<PlacementSettings> placementSettings) {
    private static final Pattern STRUCTURE_ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * A plan whose planning-time configuration is unknown, which is what a
     * plan written before that configuration was recorded decodes to.
     */
    public StartingStructurePlan(
            long selectionSeed,
            long selectionSalt,
            int selectionAlgorithmVersion,
            SiteCandidate candidate,
            List<SiteCandidate> alternativeCandidates,
            SitePlanningDiagnostics diagnostics) {
        this(
                selectionSeed,
                selectionSalt,
                selectionAlgorithmVersion,
                candidate,
                alternativeCandidates,
                diagnostics,
                Optional.empty());
    }

    public StartingStructurePlan {
        Objects.requireNonNull(placementSettings, "placementSettings");
        if (selectionAlgorithmVersion <= 0) {
            throw new IllegalArgumentException(
                    "selectionAlgorithmVersion must be positive");
        }
        candidate = Objects.requireNonNull(candidate, "candidate");
        alternativeCandidates = List.copyOf(alternativeCandidates);
        if (alternativeCandidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "alternativeCandidates must not contain null values");
        }
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        for (SiteCandidate plannedCandidate : candidates(
                candidate,
                alternativeCandidates)) {
            validateCandidateIdentity(plannedCandidate);
            if (!plannedCandidate.structureId().equals(candidate.structureId())
                    || !plannedCandidate.structureSha256().equals(
                            candidate.structureSha256())) {
                throw new IllegalArgumentException(
                        "all planned candidates must use the same structure asset");
            }
        }

        int acceptedSpawnCandidates =
                diagnostics.spawnValidationCount()
                        - diagnostics.spawnRejectedCandidateCount();
        if (1 + alternativeCandidates.size()
                > acceptedSpawnCandidates) {
            throw new IllegalArgumentException(
                    "persisted candidates exceed accepted spawn validations");
        }
    }

    public StartingStructurePlan(
            long selectionSeed,
            long selectionSalt,
            int selectionAlgorithmVersion,
            SiteCandidate candidate,
            SitePlanningDiagnostics diagnostics) {
        this(
                selectionSeed,
                selectionSalt,
                selectionAlgorithmVersion,
                candidate,
                List.of(),
                diagnostics);
    }

    public static StartingStructurePlan fromPlanningResult(
            long selectionSeed,
            long selectionSalt,
            int selectionAlgorithmVersion,
            SitePlanningResult result,
            PlacementSettings placementSettings) {
        Objects.requireNonNull(result, "result");
        return new StartingStructurePlan(
                selectionSeed,
                selectionSalt,
                selectionAlgorithmVersion,
                result.selectedCandidate(),
                result.alternativeCandidates(),
                result.diagnostics(),
                Optional.of(Objects.requireNonNull(
                        placementSettings,
                        "placementSettings")));
    }

    public StartingStructurePlan advanceCandidate() {
        if (alternativeCandidates.isEmpty()) {
            throw new IllegalStateException(
                    "No alternative starting-structure candidate remains");
        }
        return new StartingStructurePlan(
                selectionSeed,
                selectionSalt,
                selectionAlgorithmVersion,
                alternativeCandidates.getFirst(),
                alternativeCandidates.subList(
                        1,
                        alternativeCandidates.size()),
                diagnostics,
                placementSettings);
    }

    Optional<SiteAdvance> advancePastCurrentSite() {
        List<SiteCandidate> retained = alternativeCandidates.stream()
                .filter(alternative -> !sameSite(candidate, alternative))
                .toList();
        if (retained.isEmpty()) {
            return Optional.empty();
        }
        int discardedCandidates = Math.addExact(
                1,
                alternativeCandidates.size() - retained.size());
        return Optional.of(new SiteAdvance(
                new StartingStructurePlan(
                        selectionSeed,
                        selectionSalt,
                        selectionAlgorithmVersion,
                        retained.getFirst(),
                        retained.subList(1, retained.size()),
                        diagnostics,
                        placementSettings),
                discardedCandidates));
    }

    public List<SiteCandidate> candidates() {
        return candidates(candidate, alternativeCandidates);
    }

    private static List<SiteCandidate> candidates(
            SiteCandidate candidate,
            List<SiteCandidate> alternatives) {
        List<SiteCandidate> candidates =
                new ArrayList<>(1 + alternatives.size());
        candidates.add(candidate);
        candidates.addAll(alternatives);
        return List.copyOf(candidates);
    }

    private static void validateCandidateIdentity(
            SiteCandidate candidate) {
        if (!STRUCTURE_ID.matcher(candidate.structureId()).matches()) {
            throw new IllegalArgumentException(
                    "candidate structureId has an invalid format");
        }
        if (!SHA_256.matcher(candidate.structureSha256()).matches()) {
            throw new IllegalArgumentException(
                    "candidate structureSha256 must be 64 lowercase hexadecimal characters");
        }
    }

    private static boolean sameSite(
            SiteCandidate first,
            SiteCandidate second) {
        return first.centerX() == second.centerX()
                && first.centerZ() == second.centerZ();
    }

    record SiteAdvance(
            StartingStructurePlan plan,
            int discardedCandidates) {
        SiteAdvance {
            plan = Objects.requireNonNull(plan, "plan");
            if (discardedCandidates <= 0) {
                throw new IllegalArgumentException(
                        "discardedCandidates must be positive");
            }
        }
    }
}
