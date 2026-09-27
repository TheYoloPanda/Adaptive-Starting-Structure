package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.BlockedStateRecovery;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.config.ModConfig;
import com.typ.adaptivestartingstructure.config.PlacementSettings;
import com.typ.adaptivestartingstructure.persistence.FallbackDecision;
import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import com.typ.adaptivestartingstructure.placement.GeneratedStructureCollisionValidator;
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
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

public final class PlacementLifecycle {
    static final int MAX_PERSISTED_ERROR_LENGTH = 2_048;

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
            case PLACING -> blockedOrRecovered(context, blockedPlacing());
            case FAILED -> blockedOrRecovered(context, blockedFailed(data));
            case AWAITING_DECISION -> {
                if (!context.supportsPlayerDecision()) {
                    throw blockedAwaitingDecision();
                }
                FallbackDecision decision =
                        data.fallbackDecision()
                                .orElseThrow();
                context.applyFallbackSpawn(decision);
                context.logAwaitingDecision(decision);
                yield PlacementLifecycleResult
                        .awaitingDecision();
            }
            case FALLBACK_APPLYING ->
                    blockedOrRecovered(
                            context,
                            blockedFallbackApplying());
            case SKIPPED -> {
                context.logNoOp(
                        PlacementLifecycleResult.Status.SKIPPED);
                yield PlacementLifecycleResult.skipped();
            }
            case PLANNED -> placePlanned(context, data);
        };
    }

    /**
     * Refuses to start, unless the admin has asked for blocked worlds to load
     * anyway. A crash during placement leaves a state that no later start can
     * clear on its own, which without a way out costs the whole world; the way
     * out stays opt-in because the world may be half-modified, and it keeps
     * the state on disk so the refusal returns the moment it is turned off.
     */
    private static PlacementLifecycleResult blockedOrRecovered(
            PlacementLifecycleContext context,
            StartingStructureStartupException blocked) {
        if (context.blockedStateRecovery() != BlockedStateRecovery.SKIP) {
            throw blocked;
        }
        context.logBlockedStateRecovery(blocked.getMessage());
        return PlacementLifecycleResult.skipped();
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
        config = planningTimeConfig(config, data.plan());

        Optional<PlacementWork> preparedWork = prepareCandidate(
                context,
                data,
                config);
        if (preparedWork.isEmpty()) {
            return PlacementLifecycleResult
                    .awaitingDecision();
        }
        PlacementWork work = preparedWork.orElseThrow();
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
            context.flushWorldChanges();
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

    /**
     * The live configuration with the values the site was planned under put
     * back. A world can be created, returned to the world list, reconfigured
     * and only then loaded for the first time; judging a finished plan by
     * settings it was never planned under fails placement and, with the
     * failed state blocking every later start, costs the whole world.
     */
    private static ConfigSnapshot planningTimeConfig(
            ConfigSnapshot config,
            StartingStructurePlan plan) {
        Optional<PlacementSettings> planned = plan.placementSettings();
        if (planned.isEmpty()) {
            return config;
        }
        PlacementSettings settings = planned.get();
        if (!settings.equals(PlacementSettings.from(config))) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Placement settings changed since this world was planned; "
                            + "placing with the planned values instead ({}). "
                            + "Change them before creating a world for them to take effect.",
                    settings);
        }
        return settings.applyTo(config);
    }

    private static Optional<PlacementWork> prepareCandidate(
            PlacementLifecycleContext context,
            StartingStructureSavedData data,
            ConfigSnapshot config) throws Exception {
        while (true) {
            try {
                return Optional.of(
                        context.prepare(data, config));
            } catch (UnsuitableGeneratedSiteException failure) {
                var rejected = data.plan().candidate();
                OptionalInt discardedSiteCandidates;
                try {
                    discardedSiteCandidates =
                            data.advancePastCurrentSite();
                } catch (Exception retryFailure) {
                    retryFailure.addSuppressed(failure);
                    failBeforeWorldWrites(
                            context,
                            data,
                            retryFailure);
                    throw retryFailure;
                }
                if (discardedSiteCandidates.isEmpty()) {
                    context.logLastCandidateRejected(
                            rejected,
                            failure.getMessage());
                    if (context.supportsPlayerDecision()) {
                        FallbackDecision decision;
                        try {
                            decision =
                                    context.createFallbackDecision(
                                            failure);
                        } catch (Exception fallbackFailure) {
                            fallbackFailure.addSuppressed(
                                    failure);
                            failBeforeWorldWrites(
                                    context,
                                    data,
                                    fallbackFailure);
                            throw fallbackFailure;
                        }
                        try {
                            context.persistAwaitingDecision(
                                    data,
                                    decision);
                            context.logTransition(
                                    StartingStructureSavedData.State
                                            .PLANNED,
                                    StartingStructureSavedData.State
                                            .AWAITING_DECISION);
                        } catch (Exception persistenceFailure) {
                            persistenceFailure.addSuppressed(
                                    failure);
                            failBeforeWorldWrites(
                                    context,
                                    data,
                                    persistenceFailure);
                            throw persistenceFailure;
                        }
                        context.applyFallbackSpawn(decision);
                        context.logAwaitingDecision(decision);
                        return Optional.empty();
                    }
                    failBeforeWorldWrites(
                            context,
                            data,
                            failure);
                    throw failure;
                }

                try {
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
                        discardedSiteCandidates.getAsInt(),
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
            case FAILED, PLANNED, AWAITING_DECISION,
                    FALLBACK_APPLYING, SKIPPED ->
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

    static String failureSummary(Throwable failure) {
        String message = failure.getMessage();
        String summary = failure.getClass().getName()
                + (message == null || message.isBlank()
                        ? ""
                        : ": " + message);
        return boundedSingleLine(summary);
    }

    private static String boundedSingleLine(String value) {
        StringBuilder result = new StringBuilder(
                Math.min(
                        value.length(),
                        MAX_PERSISTED_ERROR_LENGTH));
        boolean pendingSpace = false;
        boolean truncated = false;
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            int required = Character.charCount(codePoint)
                    + (pendingSpace ? 1 : 0);
            if (result.length() + required
                    > MAX_PERSISTED_ERROR_LENGTH) {
                truncated = true;
                break;
            }
            if (pendingSpace) {
                result.append(' ');
                pendingSpace = false;
            }
            result.appendCodePoint(codePoint);
        }
        if (offset < value.length()) {
            truncated = true;
        }
        if (!truncated) {
            return result.toString();
        }

        int contentLimit =
                MAX_PERSISTED_ERROR_LENGTH - 3;
        if (result.length() > contentLimit) {
            result.setLength(contentLimit);
            if (Character.isHighSurrogate(
                    result.charAt(
                            result.length() - 1))) {
                result.setLength(
                        result.length() - 1);
            }
        }
        return result.append("...").toString();
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

    private static StartingStructureStartupException
            blockedAwaitingDecision() {
        return new StartingStructureStartupException(
                "Starting-structure state is AWAITING_DECISION, but the world is not running on its own integrated server. "
                        + "Player fallback decisions are singleplayer-only.");
    }

    private static StartingStructureStartupException
            blockedFallbackApplying() {
        return new StartingStructureStartupException(
                "Starting-structure state is FALLBACK_APPLYING; fallback writes may have been only partially applied. "
                        + "Automatic recovery is intentionally disabled.");
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
        public boolean supportsPlayerDecision() {
            return server.isSingleplayer();
        }

        @Override
        public Optional<StartingStructureSavedData> loadData() {
            return StartingStructureStorage.load(level);
        }

        @Override
        public BlockedStateRecovery blockedStateRecovery() {
            try {
                return ModConfig.snapshot().blockedStateRecovery();
            } catch (RuntimeException failure) {
                /*
                 * An unreadable configuration must not be the thing that
                 * decides to accept a half-modified world.
                 */
                AdaptiveStartingStructure.LOGGER.error(
                        "Could not read the starting-structure configuration while handling a "
                                + "blocked world; refusing to start",
                        failure);
                return BlockedStateRecovery.BLOCK;
            }
        }

        @Override
        public void logBlockedStateRecovery(String blockedReason) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Loading this world anyway because blockedStateRecovery is set to '{}': {} "
                            + "The starting structure is given up on and the world may keep partial "
                            + "terrain or structure changes from the interrupted attempt.",
                    BlockedStateRecovery.SKIP.configValue(),
                    blockedReason);
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
            SpawnRelocation.apply(
                    level,
                    data.plan().candidate().worldSpawn());
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
                GeneratedStructureCollisionValidator.validate(
                        prepared,
                        terrain,
                        blending);
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
        public void persistAwaitingDecision(
                StartingStructureSavedData data,
                FallbackDecision decision) throws Exception {
            StartingStructureStorage.persistAwaitingDecision(
                    level,
                    data,
                    decision);
        }

        @Override
        public FallbackDecision createFallbackDecision(
                Exception failure) {
            return new FallbackDecision(
                    VanillaSpawnFallback.resolve(level),
                    server.getWorldData()
                            .worldGenOptions()
                            .generateBonusChest(),
                    failureSummary(failure));
        }

        @Override
        public void applyFallbackSpawn(
                FallbackDecision decision) {
            VanillaSpawnFallback.applySpawn(
                    level,
                    decision.vanillaSpawn());
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
        public void flushWorldChanges() {
            if (!server.saveAllChunks(
                    true,
                    true,
                    true)) {
                throw new StartingStructureStartupException(
                        "Minecraft did not save any level after starting-structure placement");
            }
        }

        @Override
        public void logNoOp(
                PlacementLifecycleResult.Status status) {
            if (status
                    == PlacementLifecycleResult.Status
                            .ALREADY_COMPLETE) {
                AdaptiveStartingStructure.LOGGER.debug(
                        "Starting structure is already COMPLETE; no pool, NBT, planner, chunk, or block work will run");
            } else if (status
                    == PlacementLifecycleResult.Status.SKIPPED) {
                AdaptiveStartingStructure.LOGGER.debug(
                        "Starting structure is SKIPPED for this world; no pool, retry, planner, chunk, or block work will run");
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
                int discardedSiteCandidates,
                int remainingAlternatives,
                String reason) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Generated site for starting structure '{}' at center [{}, {}], origin {}, rotation {} "
                            + "was unsuitable ({}); discarded {} candidate variant(s) for that site. "
                            + "Retrying site at center [{}, {}], origin {}, rotation {}, with world spawn {}. "
                            + "{} candidate alternatives remain.",
                    rejected.structureId(),
                    rejected.centerX(),
                    rejected.centerZ(),
                    rejected.placementOrigin(),
                    rejected.rotation(),
                    reason,
                    discardedSiteCandidates,
                    replacement.centerX(),
                    replacement.centerZ(),
                    replacement.placementOrigin(),
                    replacement.rotation(),
                    replacement.worldSpawn(),
                    remainingAlternatives);
        }

        @Override
        public void logLastCandidateRejected(
                SiteCandidate rejected,
                String reason) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Generated site for starting structure '{}' at center [{}, {}], origin {}, rotation {} "
                            + "was unsuitable ({}); no candidate alternatives remain.",
                    rejected.structureId(),
                    rejected.centerX(),
                    rejected.centerZ(),
                    rejected.placementOrigin(),
                    rejected.rotation(),
                    reason);
        }

        @Override
        public void logAwaitingDecision(
                FallbackDecision decision) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "No safe generated site remained. Singleplayer world entry is awaiting the owner's decision; "
                            + "vanilla fallback spawn {} is saved and Bonus Chest is {}.",
                    decision.vanillaSpawn(),
                    decision.generateBonusChest()
                            ? "enabled"
                            : "disabled");
        }

        @Override
        public void logCompletion(
                WorldFinalizationMetrics metrics,
                long placementElapsedNanos) {
            AdaptiveStartingStructure.LOGGER.info(
                    "Starting structure COMPLETE in {} ms: {} prepared chunks, {} modified chunks, {} columns, "
                            + "{} observed block writes ({} leveling, {} blending, {} template, {} shape correction), "
                            + "{} block entities, {} light checks, {} neighbor updates, "
                            + "{} fluid ticks, {} validated spawn columns, and entities "
                            + "[source={}, planned={}, materialized={}, added={}, skippedDisabled={}, skippedUnsupported={}]",
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
                    metrics.spawnColumnsChecked(),
                    metrics.sourceEntities(),
                    metrics.plannedEntities(),
                    metrics.materializedEntities(),
                    metrics.addedEntities(),
                    metrics.skippedByDisabledOption(),
                    metrics.skippedUnsupportedEntities());
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
