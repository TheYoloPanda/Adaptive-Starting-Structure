package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.config.BlockedStateRecovery;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.FallbackDecision;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.placement.WorldFinalizationMetrics;
import com.typ.adaptivestartingstructure.planner.SiteCandidate;
import java.util.Optional;

interface PlacementLifecycleContext {
    boolean isServerThread();

    boolean isWorldInitialized();

    long nanoTime();

    boolean supportsPlayerDecision();

    Optional<StartingStructureSavedData> loadData()
            throws Exception;

    ConfigSnapshot loadConfig();

    PlacementWork prepare(
            StartingStructureSavedData data,
            ConfigSnapshot config) throws Exception;

    void persistState(StartingStructureSavedData data)
            throws Exception;

    void persistAwaitingDecision(
            StartingStructureSavedData data,
            FallbackDecision decision) throws Exception;

    FallbackDecision createFallbackDecision(
            Exception failure) throws Exception;

    void applyFallbackSpawn(FallbackDecision decision)
            throws Exception;

    WorldFinalizationMetrics placeAndFinalize(
            PlacementWork work,
            ConfigSnapshot config) throws Exception;

    void flushWorldChanges() throws Exception;

    /** How the admin wants a world whose state blocks startup to be treated. */
    BlockedStateRecovery blockedStateRecovery();

    void logNoOp(PlacementLifecycleResult.Status status);

    void logBlockedStateRecovery(String blockedReason);

    void logTransition(
            StartingStructureSavedData.State from,
            StartingStructureSavedData.State to);

    void logCandidateRetry(
            SiteCandidate rejected,
            SiteCandidate replacement,
            int discardedSiteCandidates,
            int remainingAlternatives,
            String reason);

    void logAwaitingDecision(FallbackDecision decision);

    void logCompletion(
            WorldFinalizationMetrics metrics,
            long placementElapsedNanos);
}
