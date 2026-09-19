package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.config.ModConfig;
import com.typ.adaptivestartingstructure.config.PlacementSettings;
import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import com.typ.adaptivestartingstructure.planner.PlannerQueryContext;
import com.typ.adaptivestartingstructure.planner.SitePlanner;
import com.typ.adaptivestartingstructure.planner.SitePlanningResult;
import com.typ.adaptivestartingstructure.structure.ExternalStructureLoader;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import com.typ.adaptivestartingstructure.structure.StructurePool;
import com.typ.adaptivestartingstructure.structure.StructureSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.event.level.LevelEvent;

public final class SpawnPlanningLifecycle {
    private static final float DEFAULT_SPAWN_ANGLE = 0.0F;

    private SpawnPlanningLifecycle() {
    }

    public static void onCreateSpawnPosition(
            LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        try {
            initialize(new EventContext(level, event));
        } catch (Exception exception) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Adaptive starting-structure initialization failed after the recoverable planning boundary; "
                            + "world creation cannot continue safely",
                    exception);
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(
                    "Failed to initialize the adaptive starting structure safely",
                    exception);
        }
    }

    static boolean initialize(SpawnPlanningContext context) throws Exception {
        if (!context.isOverworld() || !context.isFirstInitialization()) {
            return false;
        }

        ConfigSnapshot config;
        try {
            config = context.loadConfig();
        } catch (RuntimeException failure) {
            context.reportPlanningFallback(
                    PlanningFailurePhase.CONFIGURATION,
                    failure);
            return false;
        }
        if (!config.enabled()) {
            return false;
        }
        if (!context.isServerThread()) {
            throw new IllegalStateException(
                    "Starting-structure planning must run on the server thread");
        }

        Optional<StartingStructureSavedData> existing;
        try {
            existing = context.loadExistingData();
        } catch (RuntimeException failure) {
            context.reportPlanningFallback(
                    PlanningFailurePhase.PLANNING,
                    failure);
            return false;
        }
        Optional<StartingStructurePlan> resumable =
                resumablePlan(existing, context.worldSeed());
        if (resumable.isPresent()) {
            /*
             * A previous attempt planned this world and then died before the
             * level was marked initialized, so planning runs again. Writing
             * the data file a second time is refused by design, which would
             * leave the world impossible to create; the plan on disk is still
             * valid for this seed, so it is used as it stands.
             */
            StartingStructurePlan plannedEarlier = resumable.get();
            context.setSpawn(
                    plannedEarlier.candidate().worldSpawn(),
                    DEFAULT_SPAWN_ANGLE);
            context.cancelEvent();
            context.logResumedPlan(plannedEarlier);
            return true;
        }

        long planningStartedAt = context.nanoTime();
        StartingStructurePlan plan;
        try {
            plan = context.createPlan(config);
        } catch (Exception failure) {
            context.reportPlanningFallback(
                    PlanningFailurePhase.PLANNING,
                    failure);
            return false;
        }
        long planningElapsedNanos = Math.max(
                0L,
                context.nanoTime() - planningStartedAt);
        StartingStructureSavedData data =
                StartingStructureSavedData.planned(plan);
        context.persist(data);
        context.setSpawn(
                plan.candidate().worldSpawn(),
                DEFAULT_SPAWN_ANGLE);
        context.cancelEvent();
        context.logCompletion(plan, planningElapsedNanos);
        return true;
    }

    private static Optional<StartingStructurePlan> resumablePlan(
            Optional<StartingStructureSavedData> existing,
            long worldSeed) {
        return existing
                .filter(data -> data.state()
                        == StartingStructureSavedData.State.PLANNED)
                .map(StartingStructureSavedData::plan)
                .filter(plan -> plan.selectionSeed() == worldSeed);
    }

    private static final class EventContext implements SpawnPlanningContext {
        private final ServerLevel level;
        private final LevelEvent.CreateSpawnPosition event;
        private final ServerLevelData levelData;

        private EventContext(
                ServerLevel level,
                LevelEvent.CreateSpawnPosition event) {
            this.level = level;
            this.event = event;
            this.levelData = event.getSettings();
        }

        @Override
        public boolean isOverworld() {
            return level.dimension() == Level.OVERWORLD;
        }

        @Override
        public boolean isFirstInitialization() {
            return !levelData.isInitialized();
        }

        @Override
        public boolean isServerThread() {
            return level.getServer().isSameThread();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public ConfigSnapshot loadConfig() {
            return ModConfig.snapshot();
        }

        @Override
        public long worldSeed() {
            return level.getSeed();
        }

        @Override
        public Optional<StartingStructureSavedData> loadExistingData() {
            return StartingStructureStorage.load(level);
        }

        @Override
        public StartingStructurePlan createPlan(ConfigSnapshot config)
                throws Exception {
            long seed = level.getSeed();
            List<StructureSource> sources = StructurePool.discover();
            ExternalStructureLoader loader = new ExternalStructureLoader(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK));
            StructureDefinition selected =
                    StructurePool.loadAndSelect(seed, sources, loader);
            PlannerQueryContext queries =
                    PlannerQueryContext.create(level, config);
            SitePlanningResult result = SitePlanner.plan(
                    seed,
                    config,
                    queries,
                    selected,
                    level.getGameRules());
            return StartingStructurePlan.fromPlanningResult(
                    seed,
                    StructurePool.SELECTION_SALT_V1,
                    StructurePool.SELECTION_ALGORITHM_VERSION,
                    result,
                    PlacementSettings.from(config));
        }

        @Override
        public void persist(StartingStructureSavedData data) throws Exception {
            StartingStructureStorage.persistInitial(level, data);
        }

        @Override
        public void setSpawn(BlockPos spawn, float angle) {
            levelData.setSpawn(spawn, angle);
        }

        @Override
        public void cancelEvent() {
            event.setCanceled(true);
        }

        @Override
        public void reportPlanningFallback(
                PlanningFailurePhase phase,
                Exception failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Adaptive starting-structure planning failed before any world change; "
                            + "continuing with vanilla spawn and no adaptive structure for this world",
                    failure);
            try {
                PlanningFailureNotifier.queue(
                        level.getServer(),
                        PlanningFailureNotice.from(phase, failure));
            } catch (RuntimeException notificationFailure) {
                AdaptiveStartingStructure.LOGGER.error(
                        "Failed to queue the starting-structure planning failure notification",
                        notificationFailure);
            }
        }

        @Override
        public void logCompletion(
                StartingStructurePlan plan,
                long planningElapsedNanos) {
            var diagnostics = plan.diagnostics();
            AdaptiveStartingStructure.LOGGER.info(
                    "Planned starting structure '{}' at {} with rotation {}, score {}, and world spawn {} "
                            + "in {} ms using {} generator queries (search v{}) "
                            + "(coarse: {} evaluated/{} accepted; fine: {} evaluated/{} accepted; "
                            + "spawn: {} evaluated/{} rejected). "
                            + "Vanilla initial-spawn processing is canceled; any configured bonus chest will be skipped.",
                    plan.candidate().structureId(),
                    plan.candidate().placementOrigin(),
                    plan.candidate().rotation(),
                    plan.candidate().fitCost(),
                    plan.candidate().worldSpawn(),
                    TimeUnit.NANOSECONDS.toMillis(planningElapsedNanos),
                    diagnostics.generatorQueriesUsed(),
                    SitePlanner.SEARCH_ALGORITHM_VERSION,
                    diagnostics.coarseEvaluationCount(),
                    diagnostics.coarseCandidateCount(),
                    diagnostics.fineEvaluationCount(),
                    diagnostics.fineAcceptedCount(),
                    diagnostics.spawnValidationCount(),
                    diagnostics.spawnRejectedCandidateCount());
        }

        @Override
        public void logResumedPlan(StartingStructurePlan plan) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Reusing the starting-structure plan for '{}' at {} with world spawn {} "
                            + "left by an earlier creation attempt of this world; "
                            + "planning is skipped and the stored site is kept.",
                    plan.candidate().structureId(),
                    plan.candidate().placementOrigin(),
                    plan.candidate().worldSpawn());
        }
    }
}
