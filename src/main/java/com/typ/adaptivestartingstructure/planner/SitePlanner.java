package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import java.util.ArrayList;
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

    /*
     * The coarse search would happily spend the whole budget looking for
     * more positions, leaving nothing to evaluate the ones it found: every
     * later query would fail immediately and the run would end with no site
     * despite having candidates in hand. Each phase therefore stops at its
     * own share of the budget and hands its partial result to the next one.
     * The configured budget still caps the run as a whole.
     */
    static final int COARSE_QUERY_SHARE_PERCENT = 70;
    static final int FINE_QUERY_SHARE_PERCENT = 95;

    /*
     * How many validated sites end the search. One would do for placement, but
     * the rest are the alternatives placement falls back on when the generated
     * world turns out to disagree with the planned terrain, and finding them
     * later means searching again.
     */
    static final int EARLY_EXIT_TARGET_SITES = 4;

    /**
     * Which site-search algorithm produced a plan.
     *
     * <p>Version 2 walks a square lattice outward from the vanilla spawn,
     * ranks bands before cost, stops once it has enough validated sites, and
     * scores possible generated structures as a penalty. Version 1 swept a
     * golden-angle spiral over the whole radius and ranked on flatness alone.
     *
     * <p>This is not a compatibility gate: a plan is written once and replayed
     * as it stands, never re-planned. It exists so a log line from the field
     * says which search chose the site.
     */
    public static final int SEARCH_ALGORITHM_VERSION = 2;

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
        int budget = config.maximumGeneratorQueries();
        EnumMap<SpawnRejectionReason, Integer> spawnRejectionCounts =
                new EnumMap<>(SpawnRejectionReason.class);
        int spawnValidationCount = 0;
        int spawnRejectedCandidateCount = 0;
        TheoreticalTerrainSource terrain = queries::baseColumn;
        int validationRadius = config.requireSafeSpawnArea()
                ? configuredSpawnRadius
                : 0;
        List<SiteCandidate> acceptedCandidates = new ArrayList<>();
        CoarseSearchResult coarseResult = null;
        FineSearchResult fineResult = null;
        BudgetInterruption interruption = null;

        /*
         * One band at a time, nearest first. The far band costs roughly four
         * times the near one with the default settings, and paying for it once
         * the near band has already produced usable sites buys nothing: the
         * sites it finds rank behind them anyway.
         */
        for (CoarseCandidate.SearchBand band
                : CoarseCandidate.SearchBand.values()) {
            queries.limitNextPhase(
                    phaseLimit(budget, COARSE_QUERY_SHARE_PERCENT));
            CoarseSearchResult bandCoarse = CoarseSiteSearch.searchBand(
                    config,
                    queries,
                    structure,
                    band);
            coarseResult = coarseResult == null
                    ? bandCoarse
                    : coarseResult.merge(bandCoarse);

            queries.limitNextPhase(
                    phaseLimit(budget, FINE_QUERY_SHARE_PERCENT));
            FineSearchResult bandFine = FineSiteEvaluator.evaluate(
                    config,
                    queries,
                    bandCoarse.acceptedCandidates());
            fineResult = fineResult == null
                    ? bandFine
                    : fineResult.merge(bandFine);

            queries.limitNextPhase(budget);
            if (interruption == null) {
                interruption = firstInterruption(bandCoarse, bandFine);
            }

            for (FineCandidateEvaluation evaluation
                    : bandFine.acceptedCandidates()) {
                FineCandidatePlan plan = evaluation.plan().orElseThrow();
                SpawnValidationResult spawnValidation;
                try {
                    spawnValidation =
                            TheoreticalSpawnValidator.validate(
                                    validationRadius,
                                    evaluation.candidate().structure(),
                                    plan,
                                    terrain);
                } catch (GeneratorQueryBudgetExceededException failure) {
                    if (interruption == null) {
                        interruption = new BudgetInterruption("spawn", failure);
                    }
                    break;
                }
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

            if (interruption != null
                    || acceptedCandidates.size() >= EARLY_EXIT_TARGET_SITES) {
                break;
            }
        }

        SitePlanningDiagnostics diagnostics = diagnostics(
                coarseResult,
                fineResult,
                spawnValidationCount,
                spawnRejectedCandidateCount,
                queries,
                spawnRejectionCounts);
        if (!acceptedCandidates.isEmpty()) {
            if (interruption != null) {
                AdaptiveStartingStructure.LOGGER.warn(
                        "Starting-structure planning stopped the {} phase on its query budget "
                                + "after {} of {} queries; continuing with the {} site(s) already found",
                        interruption.phase(),
                        queries.queriesUsed(),
                        queries.maximumQueries(),
                        acceptedCandidates.size());
            }
            return new SitePlanningResult(
                    acceptedCandidates.getFirst(),
                    acceptedCandidates.subList(
                            1,
                            acceptedCandidates.size()),
                    diagnostics);
        }
        if (interruption != null) {
            /*
             * Nothing survived, so the budget really was the cause and the
             * caller gets the failure that names it.
             */
            throw interruption.failure();
        }
        throw new SitePlanningException(diagnostics);
    }

    static int phaseLimit(int budget, int sharePercent) {
        return Math.max(1, (int) ((long) budget * sharePercent / 100L));
    }

    private static BudgetInterruption firstInterruption(
            CoarseSearchResult coarseResult,
            FineSearchResult fineResult) {
        return coarseResult.budgetFailure()
                .map(failure -> new BudgetInterruption("coarse", failure))
                .or(() -> fineResult.budgetFailure()
                        .map(failure -> new BudgetInterruption("fine", failure)))
                .orElse(null);
    }

    private record BudgetInterruption(
            String phase,
            GeneratorQueryBudgetExceededException failure) {
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
