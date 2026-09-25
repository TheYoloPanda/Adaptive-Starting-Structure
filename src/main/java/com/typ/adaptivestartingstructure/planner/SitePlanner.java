package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

    /*
     * How many positions the coarse search looks at before the run asks
     * whether it already has what it needs. Small enough that a world with
     * good ground nearby stops almost at once, large enough that a slice
     * normally holds more than a handful of candidates to choose between.
     */
    static final int COARSE_SLICE = 64;

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
                        queries.queriesUsed()),
                AdaptiveStartingStructure.LOGGER::info);
    }

    static SitePlanningResult plan(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            int configuredSpawnRadius,
            LongSupplier nanoTime,
            LongConsumer slowWarning) {
        return plan(
                seed,
                config,
                queries,
                structure,
                configuredSpawnRadius,
                nanoTime,
                slowWarning,
                line -> {
                });
    }

    static SitePlanningResult plan(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            int configuredSpawnRadius,
            LongSupplier nanoTime,
            LongConsumer slowWarning,
            Consumer<String> telemetry) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(slowWarning, "slowWarning");
        Objects.requireNonNull(telemetry, "telemetry");
        if (queries.maximumQueries() != config.maximumGeneratorQueries()) {
            throw new IllegalArgumentException(
                    "PlannerQueryContext budget must match maximumGeneratorQueries");
        }

        long startedAt = nanoTime.getAsLong();
        PlanningTelemetry measurements = new PlanningTelemetry(queries);
        int acceptedSites = 0;
        try {
            SitePlanningResult result = planCore(
                    seed,
                    config,
                    queries,
                    structure,
                    configuredSpawnRadius,
                    measurements);
            acceptedSites = result.candidates().size();
            return result;
        } finally {
            long elapsedNanos =
                    Math.max(0L, nanoTime.getAsLong() - startedAt);
            /*
             * Reported for a failed run too: that is the one most worth
             * having figures for.
             */
            report(telemetry, measurements, acceptedSites, elapsedNanos);
            if (elapsedNanos > SLOW_WARNING_NANOS) {
                emitSlowWarning(slowWarning, elapsedNanos);
            }
        }
    }

    private static void report(
            Consumer<String> telemetry,
            PlanningTelemetry measurements,
            int acceptedSites,
            long totalNanos) {
        try {
            telemetry.accept(measurements.describe(acceptedSites, totalNanos));
        } catch (RuntimeException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Failed to report starting-structure planning measurements",
                    failure);
        }
    }

    private static SitePlanningResult planCore(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            int configuredSpawnRadius,
            PlanningTelemetry measurements) {
        int budget = config.maximumGeneratorQueries();
        Finalists finalists = new Finalists(
                config,
                queries,
                structure,
                configuredSpawnRadius,
                measurements);
        CoarseSearchResult coarseResult = null;

        /*
         * Nearest first, a slice of positions at a time, stopping as soon as
         * enough sites have survived. Finishing a whole band before deciding
         * pays for positions never needed, and inside a band a kilometre wide
         * it also lets a flatter site far out beat a good one close by, which
         * is not what the ordering is meant to express.
         */
        bands:
        for (CoarseCandidate.SearchBand band
                : CoarseCandidate.SearchBand.values()) {
            /*
             * One finalist budget per band, spent across its slices. Spending
             * one budget over the whole run would let a band whose candidates
             * all fail spawn validation exhaust it and end the search before
             * the next band is looked at, which is less coverage than
             * finishing each band gave.
             */
            int remainingFinalists = config.fineCandidateCount();
            /*
             * A slice puts forward the best rotation of each of its places and
             * holds the others back until the band has offered every place.
             * Putting them forward with the slice spent its leftover slots on
             * the places it had just offered, so a band could run out on its
             * first few places without reaching the rest.
             */
            List<CoarseCandidate> heldBack = new ArrayList<>();
            List<CoarseSiteSearch.LatticePosition> positions =
                    CoarseSiteSearch.bandPositions(config, structure, band);
            for (int from = 0; from < positions.size(); from += COARSE_SLICE) {
                List<CoarseSiteSearch.LatticePosition> slice = positions.subList(
                        from,
                        Math.min(from + COARSE_SLICE, positions.size()));
                queries.limitNextPhase(
                        phaseLimit(budget, COARSE_QUERY_SHARE_PERCENT));
                measurements.begin();
                CoarseSearchResult sliceCoarse = CoarseSiteSearch.searchPositions(
                        config,
                        queries,
                        structure,
                        band,
                        slice);
                measurements.end("coarse");
                coarseResult = coarseResult == null
                        ? sliceCoarse
                        : coarseResult.merge(sliceCoarse);
                sliceCoarse.budgetFailure().ifPresent(
                        failure -> finalists.interrupt("coarse", failure));

                List<CoarseCandidate> best = new ArrayList<>();
                separateRotations(
                        sliceCoarse.acceptedCandidates(),
                        best,
                        heldBack);
                remainingFinalists -=
                        finalists.evaluate(best, remainingFinalists);
                if (finalists.finished()) {
                    break bands;
                }
                if (remainingFinalists <= 0) {
                    /* This band has had its chance; the next one may do better. */
                    break;
                }
            }

            /*
             * Every place has been offered. What is left of the budget goes to
             * the held-back rotations of places still without a site, one
             * rotation per place at a time. A place that has a site gets none:
             * another rotation of a working site is no alternative to it,
             * since placement discards every rotation of a failed site
             * together.
             */
            while (remainingFinalists > 0) {
                List<CoarseCandidate> next = finalists.nextRotations(heldBack);
                if (next.isEmpty()) {
                    break;
                }
                remainingFinalists -=
                        finalists.evaluate(next, remainingFinalists);
                if (finalists.finished()) {
                    break bands;
                }
            }
        }

        SitePlanningDiagnostics diagnostics = diagnostics(
                coarseResult,
                finalists.fineResult,
                finalists.spawnValidationCount,
                finalists.spawnRejectedCandidateCount,
                queries,
                finalists.spawnRejectionCounts);
        List<SiteCandidate> acceptedCandidates = finalists.sites;
        BudgetInterruption interruption = finalists.interruption;
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

    /**
     * Splits a slice's ranked candidates into the best rotation of each place
     * and all the others, both kept in rank order.
     */
    private static void separateRotations(
            List<CoarseCandidate> ranked,
            List<CoarseCandidate> best,
            List<CoarseCandidate> others) {
        Set<Long> places = new HashSet<>();
        for (CoarseCandidate candidate : ranked) {
            if (places.add(place(candidate.centerX(), candidate.centerZ()))) {
                best.add(candidate);
            } else {
                others.add(candidate);
            }
        }
    }

    private static long place(int centerX, int centerZ) {
        return ((long) centerX << 32) ^ (centerZ & 0xFFFFFFFFL);
    }

    /**
     * How far around the marker the spawn area has to be proven safe.
     *
     * <p>Reading one column of world noise is the most expensive query in
     * planning, and proving a default spawn radius means reading 441 of them
     * for every finalist. That is worth paying only when vanilla is free to
     * put a player on any of those columns. Once arriving players are moved
     * onto the marker, they are not: the marker's own column is the only one
     * anyone stands on, and the other 440 buy a guarantee about places nobody
     * reaches.
     *
     * <p>The radius actually used is stored with the chosen site, and the
     * check after placement repeats it with that stored value, so turning the
     * marker placement off later cannot retroactively fail a finished world.
     */
    static int spawnValidationRadius(
            ConfigSnapshot config,
            int configuredSpawnRadius) {
        if (!config.requireSafeSpawnArea()
                || config.placePlayerAtSpawnMarker()) {
            return 0;
        }
        return configuredSpawnRadius;
    }

    static int phaseLimit(int budget, int sharePercent) {
        return Math.max(1, (int) ((long) budget * sharePercent / 100L));
    }

    private record BudgetInterruption(
            String phase,
            GeneratorQueryBudgetExceededException failure) {
    }

    /**
     * The fine and spawn stages, fed a few candidates at a time, and what
     * they have produced so far.
     */
    private static final class Finalists {
        private final ConfigSnapshot config;
        private final PlannerQueryContext queries;
        private final StructureDefinition structure;
        private final int configuredSpawnRadius;
        private final int validationRadius;
        private final TheoreticalTerrainSource terrain;
        private final PlanningTelemetry measurements;
        private final EnumMap<SpawnRejectionReason, Integer> spawnRejectionCounts =
                new EnumMap<>(SpawnRejectionReason.class);
        private final List<SiteCandidate> sites = new ArrayList<>();
        private final Set<Long> sitePlaces = new HashSet<>();
        private FineSearchResult fineResult;
        private int spawnValidationCount;
        private int spawnRejectedCandidateCount;
        private BudgetInterruption interruption;

        private Finalists(
                ConfigSnapshot config,
                PlannerQueryContext queries,
                StructureDefinition structure,
                int configuredSpawnRadius,
                PlanningTelemetry measurements) {
            this.config = config;
            this.queries = queries;
            this.structure = structure;
            this.configuredSpawnRadius = configuredSpawnRadius;
            this.validationRadius =
                    spawnValidationRadius(config, configuredSpawnRadius);
            this.terrain = queries::baseColumn;
            this.measurements = measurements;
        }

        /**
         * Evaluates up to {@code finalistBudget} of the candidates and
         * validates the spawn of each one accepted, returning how many were
         * evaluated.
         */
        private int evaluate(
                List<CoarseCandidate> candidates,
                int finalistBudget) {
            int budget = config.maximumGeneratorQueries();
            queries.limitNextPhase(
                    phaseLimit(budget, FINE_QUERY_SHARE_PERCENT));
            measurements.begin();
            FineSearchResult fine = FineSiteEvaluator.evaluate(
                    config,
                    queries,
                    candidates,
                    finalistBudget);
            measurements.end("fine");
            fineResult = fineResult == null
                    ? fine
                    : fineResult.merge(fine);
            fine.budgetFailure().ifPresent(
                    failure -> interrupt("fine", failure));

            queries.limitNextPhase(budget);
            measurements.begin();
            for (FineCandidateEvaluation evaluation
                    : fine.acceptedCandidates()) {
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
                    interrupt("spawn", failure);
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

                SiteCandidate site = createSiteCandidate(
                        structure,
                        evaluation,
                        configuredSpawnRadius,
                        spawnValidation);
                sites.add(site);
                sitePlaces.add(place(site.centerX(), site.centerZ()));
            }
            measurements.end("spawn");
            return fine.evaluations().size();
        }

        /**
         * Takes out of {@code heldBack} the next rotation of every place that
         * has no site yet, in the order held. The rest of a place that has a
         * site is dropped on the way.
         */
        private List<CoarseCandidate> nextRotations(
                List<CoarseCandidate> heldBack) {
            List<CoarseCandidate> next = new ArrayList<>();
            List<CoarseCandidate> stillHeld = new ArrayList<>();
            Set<Long> offered = new HashSet<>();
            for (CoarseCandidate candidate : heldBack) {
                long place = place(candidate.centerX(), candidate.centerZ());
                if (sitePlaces.contains(place)) {
                    continue;
                }
                if (offered.add(place)) {
                    next.add(candidate);
                } else {
                    stillHeld.add(candidate);
                }
            }
            heldBack.clear();
            heldBack.addAll(stillHeld);
            return next;
        }

        /** The first budget failure stands; later ones are its consequences. */
        private void interrupt(
                String phase,
                GeneratorQueryBudgetExceededException failure) {
            if (interruption == null) {
                interruption = new BudgetInterruption(phase, failure);
            }
        }

        private boolean finished() {
            return interruption != null
                    || sites.size() >= EARLY_EXIT_TARGET_SITES;
        }
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
