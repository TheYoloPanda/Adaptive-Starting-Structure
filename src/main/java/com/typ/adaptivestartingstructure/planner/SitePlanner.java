package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;

public final class SitePlanner {
    static final long SLOW_WARNING_NANOS = TimeUnit.SECONDS.toNanos(10L);

    private SitePlanner() {
    }

    public static SitePlanningResult plan(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            GameRules gameRules) {
        Objects.requireNonNull(gameRules, "gameRules");
        return plan(
                seed,
                config,
                queries,
                structure,
                gameRules.getInt(GameRules.RULE_SPAWN_RADIUS));
    }

    public static SitePlanningResult plan(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            int configuredSpawnRadius) {
        return plan(
                seed,
                config,
                queries,
                structure,
                configuredSpawnRadius,
                System::nanoTime,
                elapsedNanos -> AdaptiveStartingStructure.LOGGER.warn(
                        "Starting-structure site planning took {} ms and {} generator queries",
                        TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                        queries.queriesUsed()));
    }

    static SitePlanningResult plan(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            int configuredSpawnRadius,
            LongSupplier nanoTime,
            LongConsumer slowWarning) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(slowWarning, "slowWarning");
        if (queries.maximumQueries() != config.maximumGeneratorQueries()) {
            throw new IllegalArgumentException(
                    "PlannerQueryContext budget must match maximumGeneratorQueries");
        }

        long startedAt = nanoTime.getAsLong();
        try {
            return planCore(
                    seed,
                    config,
                    queries,
                    structure,
                    configuredSpawnRadius);
        } finally {
            long elapsedNanos =
                    Math.max(0L, nanoTime.getAsLong() - startedAt);
            if (elapsedNanos > SLOW_WARNING_NANOS) {
                emitSlowWarning(slowWarning, elapsedNanos);
            }
        }
    }

    private static SitePlanningResult planCore(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            int configuredSpawnRadius) {
        CoarseSearchResult coarseResult =
                CoarseSiteSearch.searchDetailed(
                        seed,
                        config,
                        queries,
                        structure);
        List<CoarseCandidate> coarseCandidates =
                coarseResult.acceptedCandidates();
        FineSearchResult fineResult =
                FineSiteEvaluator.evaluate(config, queries, coarseCandidates);
        EnumMap<SpawnRejectionReason, Integer> spawnRejectionCounts =
                new EnumMap<>(SpawnRejectionReason.class);
        int spawnValidationCount = 0;
        int spawnRejectedCandidateCount = 0;
        TheoreticalTerrainSource terrain = queries::baseColumn;
        int validationRadius = config.requireSafeSpawnArea()
                ? configuredSpawnRadius
                : 0;
        List<SiteCandidate> acceptedCandidates =
                new java.util.ArrayList<>();

        for (FineCandidateEvaluation evaluation :
                fineResult.acceptedCandidates()) {
            FineCandidatePlan plan = evaluation.plan().orElseThrow();
            SpawnValidationResult spawnValidation =
                    TheoreticalSpawnValidator.validate(
                            validationRadius,
                            evaluation.candidate().structure(),
                            plan,
                            terrain);
            spawnValidationCount++;
            if (!spawnValidation.accepted()) {
                spawnRejectedCandidateCount++;
                mergeCounts(
                        spawnRejectionCounts,
                        spawnValidation.rejectionCounts());
                continue;
            }

            acceptedCandidates.add(createSiteCandidate(
                    structure,
                    evaluation,
                    configuredSpawnRadius,
                    spawnValidation));
        }

        SitePlanningDiagnostics diagnostics = diagnostics(
                coarseResult,
                fineResult,
                spawnValidationCount,
                spawnRejectedCandidateCount,
                queries,
                spawnRejectionCounts);
        if (!acceptedCandidates.isEmpty()) {
            return new SitePlanningResult(
                    acceptedCandidates.getFirst(),
                    acceptedCandidates.subList(
                            1,
                            acceptedCandidates.size()),
                    diagnostics);
        }
        throw new SitePlanningException(diagnostics);
    }

    private static SiteCandidate createSiteCandidate(
            StructureDefinition definition,
            FineCandidateEvaluation evaluation,
            int configuredSpawnRadius,
            SpawnValidationResult spawnValidation) {
        CoarseCandidate coarse = evaluation.candidate();
        FineCandidatePlan plan = evaluation.plan().orElseThrow();
        FineCandidateMetrics metrics = evaluation.metrics().orElseThrow();
        RotatedStructureView structure = coarse.structure();
        validateGeometry(coarse, plan, structure, spawnValidation);
        return new SiteCandidate(
                definition.source().id(),
                definition.placementSha256(),
                coarse.centerX(),
                coarse.centerZ(),
                coarse.rotation(),
                plan,
                spawnValidation.spawnFeet(),
                metrics,
                metrics.fitCost(),
                coarse.distanceSquared(),
                configuredSpawnRadius,
                Math.max(0, configuredSpawnRadius),
                spawnValidation.checkedColumns());
    }

    private static void validateGeometry(
            CoarseCandidate coarse,
            FineCandidatePlan plan,
            RotatedStructureView structure,
            SpawnValidationResult spawnValidation) {
        long expectedOriginY = (long) plan.groundSurfaceY() + 1L
                - structure.markers().groundLevel().getY();
        if (expectedOriginY != plan.placementOrigin().getY()
                || plan.placementOrigin().getX() != coarse.minimumX()
                || plan.placementOrigin().getZ() != coarse.minimumZ()) {
            throw new IllegalStateException(
                    "Fine placement origin is inconsistent with candidate markers");
        }

        StructureBounds expectedBounds = new StructureBounds(
                plan.placementOrigin().offset(structure.bounds().minimum()),
                plan.placementOrigin().offset(structure.bounds().maximum()));
        if (!expectedBounds.equals(plan.structureBounds())) {
            throw new IllegalStateException(
                    "Fine structure bounds are inconsistent with placement origin");
        }
        BlockPos expectedSpawn =
                plan.placementOrigin().offset(structure.markers().spawn());
        if (!spawnValidation.accepted()
                || !expectedSpawn.equals(spawnValidation.spawnFeet())) {
            throw new IllegalStateException(
                    "Spawn validation is inconsistent with the selected candidate");
        }
    }

    private static SitePlanningDiagnostics diagnostics(
            CoarseSearchResult coarseResult,
            FineSearchResult fineResult,
            int spawnValidationCount,
            int spawnRejectedCandidateCount,
            PlannerQueryContext queries,
            Map<SpawnRejectionReason, Integer> spawnRejectionCounts) {
        return new SitePlanningDiagnostics(
                coarseResult.evaluatedCandidateCount(),
                coarseResult.acceptedCandidates().size(),
                coarseResult.rejectedCandidateCount(),
                fineResult.evaluations().size(),
                fineResult.acceptedCandidates().size(),
                spawnValidationCount,
                spawnRejectedCandidateCount,
                queries.queriesUsed(),
                coarseResult.rejectionCounts(),
                fineResult.rejectionCounts(),
                spawnRejectionCounts);
    }

    private static <E extends Enum<E>> void mergeCounts(
            Map<E, Integer> target,
            Map<E, Integer> additions) {
        for (Map.Entry<E, Integer> entry : additions.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static void emitSlowWarning(
            LongConsumer slowWarning,
            long elapsedNanos) {
        try {
            slowWarning.accept(elapsedNanos);
        } catch (RuntimeException exception) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to emit slow site-planning warning",
                    exception);
        }
    }
}
