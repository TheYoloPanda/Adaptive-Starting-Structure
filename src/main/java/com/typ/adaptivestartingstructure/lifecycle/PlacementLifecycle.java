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
import com.typ.adaptivestartingstructure.placement.PlacementPreparationException;
import com.typ.adaptivestartingstructure.placement.PreWriteSpawnValidator;
import com.typ.adaptivestartingstructure.placement.PreparedPlacement;
import com.typ.adaptivestartingstructure.placement.PreparedTerrainBlending;
import com.typ.adaptivestartingstructure.placement.PreparedTerrainLeveling;
import com.typ.adaptivestartingstructure.placement.StartupChunkRecord;
import com.typ.adaptivestartingstructure.placement.TemplatePlacement;
import com.typ.adaptivestartingstructure.placement.TerrainBlending;
import com.typ.adaptivestartingstructure.placement.TerrainLeveling;
import com.typ.adaptivestartingstructure.placement.TrialTerrainDiscard;
import com.typ.adaptivestartingstructure.placement.TrialTerrainDiscardResult;
import com.typ.adaptivestartingstructure.placement.UnsuitableGeneratedSiteException;
import com.typ.adaptivestartingstructure.placement.WorldFinalization;
import com.typ.adaptivestartingstructure.placement.WorldFinalizationMetrics;
import com.typ.adaptivestartingstructure.planner.SiteCandidate;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

public final class PlacementLifecycle {
    static final int MAX_PERSISTED_ERROR_LENGTH = 2_048;

    private PlacementLifecycle() {
    }

    public static void onServerStarting(
            ServerStartingEvent event,
            StartupChunkRecord startupChunks) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(startupChunks, "startupChunks");
        try {
            run(new EventContext(event.getServer(), startupChunks));
        } catch (Exception exception) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Adaptive starting-structure placement blocked world entry; "
                            + "the full error is logged and the server will stop without a crash report",
                    exception);
            PlacementFailureNotifier.blockWorldEntry(
                    event.getServer(),
                    exception);
        } finally {
            startupChunks.stop();
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
                FallbackDecision decision =
                        data.fallbackDecision()
                                .orElseThrow();
                if (!context.supportsPlayerDecision()) {
                    yield continueWithoutStructure(
                            context,
                            data,
                            decision);
                }
                context.applyFallbackSpawn(decision);
                context.logAwaitingDecision(decision);
                yield PlacementLifecycleResult
                        .awaitingDecision();
            }
            case FALLBACK_APPLYING -> {
                FallbackDecision decision =
                        data.fallbackDecision()
                                .orElseThrow();
                /*
                 * Of what the interrupted attempt applies, only the spawn is
                 * applied again: setting it twice changes nothing, while the
                 * bonus chest may already stand, whole or in part, and
                 * nothing on disk says which.
                 */
                yield blockedOrRecovered(
                        context,
                        blockedFallbackApplying(),
                        () -> applyRecoverySpawnOnce(
                                context,
                                data,
                                decision));
            }
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
            StartingStructureStartupException blocked) throws Exception {
        return blockedOrRecovered(context, blocked, () -> {
        });
    }

    private static PlacementLifecycleResult blockedOrRecovered(
            PlacementLifecycleContext context,
            StartingStructureStartupException blocked,
            RecoveryStep beforeLoading) throws Exception {
        if (context.blockedStateRecovery() != BlockedStateRecovery.SKIP) {
            throw blocked;
        }
        beforeLoading.run();
        context.logBlockedStateRecovery(blocked.getMessage());
        return PlacementLifecycleResult.skipped();
    }

    /**
     * Gives a world that its fallback decision blocks, and that skip loads
     * anyway, that decision's spawn once, so that a spawn moved afterwards,
     * with /setworldspawn or by another mod, is kept by later starts. The
     * world is saved before the record because the game writes a level's
     * SavedData ahead of level.dat, and a record that reached disk alone
     * would keep every later start from replacing a planned site's spawn
     * still in level.dat. A failed save or record only warns: skip exists to
     * load a blocked world, and without the record the next start applies
     * the spawn again.
     */
    private static void applyRecoverySpawnOnce(
            PlacementLifecycleContext context,
            StartingStructureSavedData data,
            FallbackDecision decision) throws Exception {
        if (data.recoverySpawnApplied()) {
            return;
        }
        context.applyFallbackSpawn(decision);
        try {
            context.flushWorldChanges();
            data.markRecoverySpawnApplied();
            context.persistState(data);
        } catch (Exception failure) {
            context.logRecoverySpawnNotRecorded(failure);
        }
    }

    /**
     * Does what Continue Anyway does, where no owner can be asked: a
     * dedicated server whose world has no site left goes on without the
     * structure, at the decision's vanilla spawn, and tells each player so.
     * Refusing every start instead would leave an admin a world no setting
     * but "skip" could open. A failure before FALLBACK_APPLYING is on disk
     * has written nothing, so the next start tries again; one after it
     * leaves that state, which refuses to start, since the bonus chest may
     * stand in part.
     */
    private static PlacementLifecycleResult continueWithoutStructure(
            PlacementLifecycleContext context,
            StartingStructureSavedData data,
            FallbackDecision decision) throws Exception {
        context.persistFallbackApplying(data);
        context.logTransition(
                StartingStructureSavedData.State.AWAITING_DECISION,
                StartingStructureSavedData.State.FALLBACK_APPLYING);
        context.completeFallback(data, decision);
        context.logTransition(
                StartingStructureSavedData.State.FALLBACK_APPLYING,
                StartingStructureSavedData.State.SKIPPED);
        context.logContinuedWithoutStructure(decision);
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
            keepPlannedOrFail(context, data, failure);
            throw failure;
        }
        config = planningTimeConfig(config, data.plan());

        Optional<PlacementWork> preparedWork = prepareCandidate(
                context,
                data,
                config);
        if (preparedWork.isEmpty()) {
            FallbackDecision decision =
                    data.fallbackDecision().orElseThrow();
            if (!context.supportsPlayerDecision()) {
                return continueWithoutStructure(
                        context,
                        data,
                        decision);
            }
            context.applyFallbackSpawn(decision);
            context.logAwaitingDecision(decision);
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
                keepPlannedOrFail(context, data, failure);
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
                context.relocateSpawn(
                        data.plan().candidate().worldSpawn());
                return Optional.of(
                        context.prepare(data, config));
            } catch (UnsuitableGeneratedSiteException failure) {
                var rejected = data.plan().candidate();
                if (failure.internalInconsistency()) {
                    context.logInternalInconsistency(rejected, failure);
                }
                /*
                 * Discarded while the site is still the current one: if the
                 * discard fails, the next start prepares this site again and
                 * so loads whatever of its terrain this start left behind.
                 */
                try {
                    context.discardTrialTerrain();
                } catch (Exception discardFailure) {
                    discardFailure.addSuppressed(failure);
                    keepPlannedOrFail(
                            context,
                            data,
                            discardFailure);
                    throw discardFailure;
                }
                OptionalInt discardedSiteCandidates;
                try {
                    discardedSiteCandidates =
                            data.advancePastCurrentSite();
                } catch (Exception retryFailure) {
                    retryFailure.addSuppressed(failure);
                    keepPlannedOrFail(
                            context,
                            data,
                            retryFailure);
                    throw retryFailure;
                }
                if (discardedSiteCandidates.isEmpty()) {
                    context.logLastCandidateRejected(
                            rejected,
                            failure.getMessage());
                    FallbackDecision decision;
                    try {
                        decision =
                                context.createFallbackDecision(
                                        failure);
                        /*
                         * Finding the vanilla spawn generates chunks under
                         * the rejected site's spawn, and a start that finds
                         * the decision recorded moves to it without
                         * discarding anything, so the move comes first.
                         */
                        context.relocateSpawn(
                                decision.vanillaSpawn());
                    } catch (Exception fallbackFailure) {
                        fallbackFailure.addSuppressed(
                                failure);
                        keepPlannedOrFail(
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
                        keepPlannedOrFail(
                                context,
                                data,
                                persistenceFailure);
                        throw persistenceFailure;
                    }
                    return Optional.empty();
                }

                try {
                    context.persistState(data);
                } catch (Exception retryFailure) {
                    retryFailure.addSuppressed(failure);
                    keepPlannedOrFail(
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
                keepPlannedOrFail(
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

    /**
     * Whether a failure while a site's terrain and template were planned is
     * one of their own consistency checks failing, rather than a deliberate
     * refusal. Those plans are computed from the site's generated blocks and
     * the template alone, after the configuration and the template file have
     * been accepted, so such a failure is a bug the next site may not hit.
     */
    static boolean isPlanInconsistency(Throwable failure) {
        return (failure instanceof IllegalArgumentException
                        || failure instanceof IllegalStateException)
                && !(failure instanceof PlacementPreparationException);
    }

    /**
     * Ends an attempt that failed before any world write. Nothing in the
     * world needs recovering, and causes such as an invalid configuration or
     * a template changed since the world was created can be fixed, so the
     * state on disk stays PLANNED and the next start retries; marking it
     * FAILED would refuse every later start of a world nothing was written
     * to. An admin who skips blocked worlds gets the structure given up
     * instead, since a cause that is never fixed would otherwise refuse
     * every start as well.
     */
    private static void keepPlannedOrFail(
            PlacementLifecycleContext context,
            StartingStructureSavedData data,
            Exception failure) {
        if (context.blockedStateRecovery() == BlockedStateRecovery.SKIP) {
            persistFailed(context, data, failure);
            return;
        }
        /*
         * The game saves dirty SavedData on its own, which would write what
         * this attempt changed in memory, such as PLACING after a failed write
         * of it, and refuse the next start after all.
         */
        data.setDirty(false);
        context.logPlannedKept();
    }

    private static void persistFailed(
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
            blockedFallbackApplying() {
        return new StartingStructureStartupException(
                "Starting-structure state is FALLBACK_APPLYING; fallback writes may have been only partially applied. "
                        + "Automatic recovery is intentionally disabled.");
    }

    private static final class EventContext
            implements PlacementLifecycleContext {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final StartupChunkRecord startupChunks;
        private StructureDefinition selected;

        private EventContext(
                MinecraftServer server,
                StartupChunkRecord startupChunks) {
            this.server = Objects.requireNonNull(
                    server,
                    "server");
            this.level = Objects.requireNonNull(
                    server.overworld(),
                    "overworld");
            this.startupChunks = Objects.requireNonNull(
                    startupChunks,
                    "startupChunks");
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
        public void logRecoverySpawnNotRecorded(Exception failure) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Could not save the world after applying the fallback decision's spawn, or record that it was "
                            + "applied; the spawn is set for this session, but the next start may set it again "
                            + "and put back a spawn moved in the meantime",
                    failure);
        }

        @Override
        public void logPlannedKept() {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Starting-structure placement failed before anything was written to the world; "
                            + "the saved state stays PLANNED and the next start retries once the cause is fixed. "
                            + "Set blockedStateRecovery to '{}' to give the structure up instead.",
                    BlockedStateRecovery.SKIP.configValue());
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

        /*
         * Every site a start retries places the same template, so it is read
         * from its file once per start, not once per site.
         */
        private StructureDefinition selectedDefinition(
                StartingStructureSavedData data) throws Exception {
            String structureId = data.plan().candidate().structureId();
            if (selected == null
                    || !selected.source().id().equals(structureId)) {
                selected = PlacementPreparation.loadSelected(
                        level,
                        data.plan());
            }
            return selected;
        }

        @Override
        public PlacementWork prepare(
                StartingStructureSavedData data,
                ConfigSnapshot config) throws Exception {
            PreparedPlacement prepared =
                    PlacementPreparation.prepare(
                            level,
                            data,
                            config,
                            selectedDefinition(data));
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
                for (String ignoredCollision
                        : GeneratedStructureCollisionValidator.validate(
                                prepared,
                                terrain,
                                blending,
                                config.ignoredStructureCollisions())) {
                    AdaptiveStartingStructure.LOGGER.info(
                            "{}; ignored because ignoredStructureCollisions lists it",
                            ignoredCollision);
                }
                PreWriteSpawnValidator.validate(
                        prepared,
                        blending,
                        config);
                if (blending.plan().selectedTreeCount() > 0) {
                    AdaptiveStartingStructure.LOGGER.info(
                            "Prepared bounded tree cleanup: {} trees, {} tree/accessory blocks selected "
                                    + "({} attachments), {} additional cleanup writes, "
                                    + "{} trunk grounds given the site's surface",
                            blending.plan().selectedTreeCount(),
                            blending.plan().selectedTreeBlockCount(),
                            blending.plan().selectedTreeAccessoryCount(),
                            blending.plan().treeCleanupWrites(),
                            blending.plan().resurfacedTrunkGrounds());
                }
                if (blending.plan().restoredSnowLayers() > 0) {
                    AdaptiveStartingStructure.LOGGER.info(
                            "Prepared snow cover on {} columns the blend or tree cleanup uncovers",
                            blending.plan().restoredSnowLayers());
                }
                /*
                 * Only the site that is kept gets the chunks vanilla keeps
                 * loaded around the spawn: each site tried before it would
                 * have had them generated, and discarded with it.
                 */
                SpawnRelocation.apply(
                        level,
                        data.plan().candidate().worldSpawn());
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
                if (isPlanInconsistency(failure)) {
                    throw UnsuitableGeneratedSiteException
                            .inconsistentPlans((RuntimeException) failure);
                }
                throw failure;
            }
        }

        @Override
        public void discardTrialTerrain() {
            BlockPos spawn = level.getLevelData().getSpawnPos();
            TrialTerrainDiscardResult result =
                    TrialTerrainDiscard.discard(
                            level,
                            startupChunks.loaded(),
                            startupChunks.saved(),
                            () -> SpawnRelocation.releaseSpawnChunks(
                                    level));
            if (result.discardedChunks() > 0) {
                AdaptiveStartingStructure.LOGGER.info(
                        "Discarded the terrain generated under world spawn {}: {} chunks ({} complete) "
                                + "and {} entities, none left in the region files, in {} ms "
                                + "({} ms waiting for generation already running, {} chunk ticks)",
                        spawn,
                        result.discardedChunks(),
                        result.fullChunks(),
                        result.removedEntities(),
                        TimeUnit.NANOSECONDS.toMillis(
                                result.elapsedNanos()),
                        TimeUnit.NANOSECONDS.toMillis(
                                result.settleNanos()),
                        result.ticks());
            }
            if (result.keptChunks() > 0) {
                AdaptiveStartingStructure.LOGGER.warn(
                        "{} chunks generated under world spawn {} stay loaded because a ticket "
                                + "Adaptive Starting Structure does not own holds them; they keep "
                                + "what they derived from that spawn",
                        result.keptChunks(),
                        spawn);
            }
            if (result.savedChunks() > 0) {
                AdaptiveStartingStructure.LOGGER.warn(
                        "{} of the discarded chunks had been saved complete by an earlier start "
                                + "of this world; their blocks are cleared, but the entities saved "
                                + "with them stay in the world's entity files and come back if "
                                + "those chunks generate again",
                        result.savedChunks());
            }
        }

        @Override
        public void relocateSpawn(BlockPos spawn) {
            if (level.getLevelData()
                    .getSpawnPos()
                    .equals(spawn)) {
                return;
            }
            discardTrialTerrain();
            SpawnRelocation.moveAfterDiscard(level, spawn);
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
        public void persistFallbackApplying(
                StartingStructureSavedData data) throws Exception {
            StartingStructureStorage.persistFallbackApplying(level, data);
        }

        @Override
        public void completeFallback(
                StartingStructureSavedData data,
                FallbackDecision decision) throws Exception {
            FallbackDecisionLifecycle.completeFallback(
                    server,
                    level,
                    data,
                    decision);
        }

        @Override
        public void logContinuedWithoutStructure(
                FallbackDecision decision) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "No safe generated site remained. A dedicated server cannot ask for the owner's choice, "
                            + "so the world continues without the starting structure at Minecraft's normal spawn {} "
                            + "(Bonus Chest {}); every player is told so at their first login. "
                            + "This is not the intended way to play: creating a new world is recommended.",
                    decision.vanillaSpawn(),
                    decision.generateBonusChest()
                            ? "placed"
                            : "not requested");
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
                    blending,
                    config);
            if (config.adaptTemplateToSite()) {
                AdaptiveStartingStructure.LOGGER.info(
                        "Adapted the template to the site: {}",
                        template.plan().siteAdaptation());
            }
            return WorldFinalization.finish(
                            serverWork.prepared,
                            template,
                            config)
                    .metrics();
        }

        @Override
        public void flushWorldChanges() {
            WorldSave.saveConfirmingSpawn(
                    server,
                    "after starting-structure world changes");
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
        public void logInternalInconsistency(
                SiteCandidate site,
                Exception failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Preparing starting structure '{}' at center [{}, {}], origin {}, rotation {} "
                            + "hit an internal inconsistency, a bug in Adaptive Starting Structure; "
                            + "nothing was written and the site is skipped for the next one. "
                            + "Please report it with this log.",
                    site.structureId(),
                    site.centerX(),
                    site.centerZ(),
                    site.placementOrigin(),
                    site.rotation(),
                    failure);
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

    private interface RecoveryStep {
        void run() throws Exception;
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
