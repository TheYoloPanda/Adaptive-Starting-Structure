package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.placement.WorldFinalizationMetrics;
import com.typ.adaptivestartingstructure.planner.SiteCandidate;
import java.util.Optional;

interface PlacementLifecycleContext {
    boolean isServerThread();

    boolean isWorldInitialized();

    long nanoTime();

    Optional<StartingStructureSavedData> loadData()
            throws Exception;

    ConfigSnapshot loadConfig();

    PlacementWork prepare(
            StartingStructureSavedData data,
            ConfigSnapshot config) throws Exception;

    void persistState(StartingStructureSavedData data)
            throws Exception;

    WorldFinalizationMetrics placeAndFinalize(
            PlacementWork work,
            ConfigSnapshot config) throws Exception;

    void logNoOp(PlacementLifecycleResult.Status status);

    void logTransition(
            StartingStructureSavedData.State from,
            StartingStructureSavedData.State to);

    void logCandidateRetry(
            SiteCandidate rejected,
            SiteCandidate replacement,
            int remainingAlternatives,
            String reason);

    void logCompletion(
            WorldFinalizationMetrics metrics,
            long placementElapsedNanos);
}
