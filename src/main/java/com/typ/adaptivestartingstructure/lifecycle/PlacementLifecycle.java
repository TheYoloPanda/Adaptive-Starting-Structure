package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.config.ModConfig;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import com.typ.adaptivestartingstructure.placement.PlacementPreparation;
import com.typ.adaptivestartingstructure.placement.PreparedPlacement;
import com.typ.adaptivestartingstructure.placement.PreparedTerrainBlending;
import com.typ.adaptivestartingstructure.placement.PreparedTerrainLeveling;
import com.typ.adaptivestartingstructure.placement.TemplatePlacement;
import com.typ.adaptivestartingstructure.placement.TerrainBlending;
import com.typ.adaptivestartingstructure.placement.TerrainLeveling;
import com.typ.adaptivestartingstructure.placement.UnsuitableGeneratedSiteException;
import com.typ.adaptivestartingstructure.placement.WorldFinalization;
import com.typ.adaptivestartingstructure.placement.WorldFinalizationMetrics;
import com.typ.adaptivestartingstructure.planner.SiteCandidate;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

public final class PlacementLifecycle {
    private PlacementLifecycle() {
    }

    public static void onServerStarting(
            ServerStartingEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            run(new EventContext(event.getServer()));
        } catch (Exception exception) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Adaptive starting-structure placement blocked world entry; "
                            + "the full error is logged and the server will stop without a crash report",
                    exception);
            PlacementFailureNotifier.blockWorldEntry(
                    event.getServer(),
                    exception);
        }
    }

    static PlacementLifecycleResult run(
            PlacementLifecycleContext context) throws Exception {
        Objects.requireNonNull(context, "context");
        if (!context.isServerThread()) {
            throw new StartingStructureStartupException(
                    "Starting-structure placement must run on the server thread");
        }

        Optional<StartingStructureSavedData> loaded =
                context.loadData();
        if (loaded.isEmpty()) {
            if (!context.isWorldInitialized()) {
                throw new StartingStructureStartupException(
                        "ServerStartingEvent reached an uninitialized world without starting-structure data");
            }
            context.logNoOp(
                    PlacementLifecycleResult.Status.NO_DATA);
            return PlacementLifecycleResult.noData();
        }

        StartingStructureSavedData data = loaded.orElseThrow();
        return switch (data.state()) {
            case COMPLETE -> {
                context.logNoOp(
                        PlacementLifecycleResult.Status
                                .ALREADY_COMPLETE);
                yield PlacementLifecycleResult
                        .alreadyComplete();
            }
            case PLACING -> throw blockedPlacing();
            case FAILED -> throw blockedFailed(data);
            case PLANNED -> placePlanned(context, data);
        };
    }

    private static PlacementLifecycleResult placePlanned(
            PlacementLifecycleContext context,
            StartingStructureSavedData data) throws Exception {
        long placementStartedAt = context.nanoTime();
        ConfigSnapshot config;
        try {
            config = context.loadConfig();
        } catch (Exception failure) {
            failBeforeWorldWrites(context, data, failure);
            throw failure;
        }

        PlacementWork work = prepareCandidate(
                context,
                data,
                config);
        try (work) {
            try {
                transitionAndPersist(
                        context,
                        data,
                        StartingStructureSavedData.State.PLACING);
            } catch (Exception failure) {
                failBeforeWorldWrites(context, data, failure);
                throw failure;
            }

            WorldFinalizationMetrics metrics =
                    context.placeAndFinalize(work, config);
            long placementElapsedNanos = Math.max(
                    0L,
                    context.nanoTime() - placementStartedAt);
            transitionAndPersist(
                    context,
                    data,
                    StartingStructureSavedData.State.COMPLETE);
            context.logCompletion(metrics, placementElapsedNanos);
            return PlacementLifecycleResult.completed(metrics);
        }
    }

    private static PlacementWork prepareCandidate(
            PlacementLifecycleContext context,
            StartingStructureSavedData data,
            ConfigSnapshot config) throws Exception {
        while (true) {
            try {
                return context.prepare(data, config);
            } catch (UnsuitableGeneratedSiteException failure) {
                if (data.plan().alternativeCandidates().isEmpty()) {
                    failBeforeWorldWrites(
                            context,
                            data,
                            failure);
                    throw failure;
                }

                var rejected = data.plan().candidate();
                try {
                    data.advanceCandidate();
                    context.persistState(data);
                } catch (Exception retryFailure) {
                    retryFailure.addSuppressed(failure);
                    failBeforeWorldWrites(
                            context,
                            data,
                            retryFailure);
                    throw retryFailure;
                }
                var replacement = data.plan().candidate();
                context.logCandidateRetry(
                        rejected,
                        replacement,
                        data.plan().alternativeCandidates().size(),
                        failure.getMessage());
            } catch (Exception failure) {
                failBeforeWorldWrites(
                        context,
                        data,
                        failure);
                throw failure;
            }
        }
    }

    private static void transitionAndPersist(
            PlacementLifecycleContext context,
            StartingStructureSavedData data,
            StartingStructureSavedData.State target)
            throws Exception {
        StartingStructureSavedData.State previous =
                data.state();
        switch (target) {
            case PLACING -> data.markPlacing();
            case COMPLETE -> data.markComplete();
            case FAILED, PLANNED ->
                    throw new IllegalArgumentException(
                            "Unsupported lifecycle transition target "
                                    + target);
        }
        context.persistState(data);
        context.logTransition(previous, target);
    }

    private static void failBeforeWorldWrites(
            PlacementLifecycleContext context,
            StartingStructureSavedData data,
            Exception originalFailure) {
        if (data.state()
                        != StartingStructureSavedData.State.PLANNED
                && data.state()
                        != StartingStructureSavedData.State.PLACING) {
            return;
        }
        StartingStructureSavedData.State previous =
                data.state();
        try {
            data.markFailed(failureSummary(originalFailure));
            context.persistState(data);
            context.logTransition(
                    previous,
                    StartingStructureSavedData.State.FAILED);
        } catch (Exception persistenceFailure) {
            originalFailure.addSuppressed(persistenceFailure);
        }
    }

    private static String failureSummary(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName()
                + (message == null || message.isBlank()
                        ? ""
                        : ": " + message);
    }

    private static StartingStructureStartupException
            blockedPlacing() {
        return new StartingStructureStartupException(
                "Starting-structure state is PLACING; the prior attempt may have partially modified the world. "
                        + "Automatic recovery is intentionally disabled; recreate the world after correcting the cause.");
    }

    private static StartingStructureStartupException blockedFailed(
            StartingStructureSavedData data) {
        return new StartingStructureStartupException(
                "Starting-structure state is FAILED; automatic retry is disabled. Last error: "
                        + data.lastError().orElse("unknown"));
    }

    private static final class EventContext
            implements PlacementLifecycleContext {
        private final MinecraftServer server;
        private final ServerLevel level;

        private EventContext(MinecraftServer server) {
            this.server = Objects.requireNonNull(
                    server,
                    "server");
            this.level = Objects.requireNonNull(
                    server.overworld(),
                    "overworld");
        }

        @Override
        public boolean isServerThread() {
            return server.isSameThread();
        }

        @Override
        public boolean isWorldInitialized() {
            return server.getWorldData()
                    .overworldData()
                    .isInitialized();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public Optional<StartingStructureSavedData> loadData() {
            return StartingStructureStorage.load(level);
        }

        @Override
        public ConfigSnapshot loadConfig() {
            ConfigSnapshot config = ModConfig.snapshot();
            if (!config.enabled()) {
                AdaptiveStartingStructure.LOGGER.warn(
                        "Completing a persisted PLANNED starting structure even though the mod is now disabled; "
                                + "the world spawn was already committed during first initialization");
            }
            return config;
        }

        @Override
        public PlacementWork prepare(
                StartingStructureSavedData data,
                ConfigSnapshot config) throws Exception {
            level.setDefaultSpawnPos(
                    data.plan().candidate().worldSpawn(),
                    0.0F);
            PreparedPlacement prepared =
                    PlacementPreparation.prepare(
                            level,
                            data,
                            config);
            try {
                PreparedTerrainLeveling terrain =
                        TerrainLeveling.prepare(
                                prepared,
                                config);
                PreparedTerrainBlending blending =
                        TerrainBlending.prepare(
                                prepared,
                                terrain,
                                config);
                if (blending.plan().selectedTreeCount() > 0) {
                    AdaptiveStartingStructure.LOGGER.info(
                            "Prepared bounded tree cleanup: {} trees, {} tree/accessory blocks selected "
                                    + "({} attachments), {} additional cleanup writes",
                            blending.plan().selectedTreeCount(),
                            blending.plan().selectedTreeBlockCount(),
                            blending.plan().selectedTreeAccessoryCount(),
                            blending.plan().treeCleanupWrites());
                }
                return new ServerPlacementWork(
                        prepared,
                        terrain,
                        blending);
            } catch (Exception | Error failure) {
                try {
                    prepared.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        @Override
        public void persistState(
                StartingStructureSavedData data) throws Exception {
            StartingStructureStorage.persistCurrent(level, data);
        }

        @Override
        public WorldFinalizationMetrics placeAndFinalize(
                PlacementWork work,
                ConfigSnapshot config) {
            if (!(work instanceof ServerPlacementWork serverWork)) {
                throw new IllegalArgumentException(
                        "Unexpected placement-work implementation");
            }
            var leveling = TerrainLeveling.apply(
                    serverWork.prepared,
                    serverWork.terrain);
            var blending = TerrainBlending.apply(
                    serverWork.prepared,
                    leveling,
                    serverWork.blending);
            var template = TemplatePlacement.place(
                    serverWork.prepared,
                    blending);
            return WorldFinalization.finish(
                            serverWork.prepared,
                            template,
                            config)
                    .metrics();
        }

        @Override
        public void logNoOp(
                PlacementLifecycleResult.Status status) {
            if (status
                    == PlacementLifecycleResult.Status
                            .ALREADY_COMPLETE) {
                AdaptiveStartingStructure.LOGGER.debug(
                        "Starting structure is already COMPLETE; no pool, NBT, planner, chunk, or block work will run");
            } else {
                AdaptiveStartingStructure.LOGGER.debug(
                        "Initialized world has no starting-structure SavedData; leaving it unchanged");
            }
        }

        @Override
        public void logTransition(
                StartingStructureSavedData.State from,
                StartingStructureSavedData.State to) {
            AdaptiveStartingStructure.LOGGER.info(
                    "Starting-structure state transition: {} -> {}",
                    from,
                    to);
        }

        @Override
        public void logCandidateRetry(
                SiteCandidate rejected,
                SiteCandidate replacement,
                int remainingAlternatives,
                String reason) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Generated site for starting structure '{}' at {} was unsuitable ({}); "
                            + "retrying candidate at {} with world spawn {}. {} alternatives remain.",
                    rejected.structureId(),
                    rejected.placementOrigin(),
                    reason,
                    replacement.placementOrigin(),
                    replacement.worldSpawn(),
                    remainingAlternatives);
        }

        @Override
        public void logCompletion(
                WorldFinalizationMetrics metrics,
                long placementElapsedNanos) {
            AdaptiveStartingStructure.LOGGER.info(
                    "Starting structure COMPLETE in {} ms: {} prepared chunks, {} modified chunks, {} columns, "
                            + "{} observed block writes ({} leveling, {} blending, {} template, {} shape correction), "
                            + "{} block entities, {} light checks, {} neighbor updates, "
                            + "{} fluid ticks, and {} validated spawn columns",
                    TimeUnit.NANOSECONDS.toMillis(placementElapsedNanos),
                    metrics.preparedChunks(),
                    metrics.modifiedChunks(),
                    metrics.modifiedColumns(),
                    metrics.observedBlockWrites(),
                    metrics.levelingBlockWrites(),
                    metrics.blendingBlockWrites(),
                    metrics.templateBlockWrites(),
                    metrics.shapeCorrectionWrites(),
                    metrics.blockEntityLoads(),
                    metrics.lightChecks(),
                    metrics.neighborUpdates(),
                    metrics.fluidTicks(),
                    metrics.spawnColumnsChecked());
        }
    }

    private static final class ServerPlacementWork
            implements PlacementWork {
        private final PreparedPlacement prepared;
        private final PreparedTerrainLeveling terrain;
        private final PreparedTerrainBlending blending;

        private ServerPlacementWork(
                PreparedPlacement prepared,
                PreparedTerrainLeveling terrain,
                PreparedTerrainBlending blending) {
            this.prepared = Objects.requireNonNull(
                    prepared,
                    "prepared");
            this.terrain = Objects.requireNonNull(
                    terrain,
                    "terrain");
            this.blending = Objects.requireNonNull(
                    blending,
                    "blending");
        }

        @Override
        public void close() {
            prepared.close();
        }
    }
}
