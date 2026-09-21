package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

public final class FineSiteEvaluator {
    private static final Comparator<FineCandidateEvaluation> ACCEPTED_ORDER =
            Comparator.comparing(
                            (FineCandidateEvaluation evaluation) ->
                                    evaluation.candidate()
                                            .metrics()
                                            .structureConflict())
                    .thenComparingDouble(evaluation ->
                            requiredMetrics(evaluation).fitCost())
                    .thenComparingInt(evaluation ->
                            requiredMetrics(evaluation).biomeClassification().ordinal())
                    .thenComparingLong(evaluation ->
                            evaluation.candidate().distanceSquared())
                    .thenComparingInt(evaluation ->
                            evaluation.candidate().rotation().ordinal())
                    .thenComparingInt(evaluation ->
                            evaluation.candidate().centerX())
                    .thenComparingInt(evaluation ->
                            evaluation.candidate().centerZ());

    private FineSiteEvaluator() {
    }

    public static FineSearchResult evaluate(
            ConfigSnapshot config,
            PlannerQueryContext queries,
            List<CoarseCandidate> coarseCandidates) {
        Objects.requireNonNull(config, "config");
        return evaluate(
                config,
                queries,
                coarseCandidates,
                config.fineCandidateCount());
    }

    /**
     * Evaluates at most {@code finalistBudget} candidates.
     *
     * <p>A caller that walks the search in slices spends one budget across all
     * of them rather than a fresh one per slice: otherwise stopping early in
     * the coarse search would be paid for by running the fine stage many times
     * over, and the saving would move rather than exist.
     */
    public static FineSearchResult evaluate(
            ConfigSnapshot config,
            PlannerQueryContext queries,
            List<CoarseCandidate> coarseCandidates,
            int finalistBudget) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(coarseCandidates, "coarseCandidates");
        if (finalistBudget <= 0) {
            return new FineSearchResult(List.of(), List.of());
        }

        List<CoarseCandidate> finalists = selectFinalists(
                coarseCandidates,
                finalistBudget);
        int finalistCount = finalists.size();
        List<FineCandidateEvaluation> evaluations = new ArrayList<>(finalistCount);
        List<FineCandidateEvaluation> accepted = new ArrayList<>(finalistCount);
        GeneratorQueryBudgetExceededException budgetFailure = null;
        Map<Rotation, Map<Long, ColumnAir>> airByRotation = new HashMap<>();
        for (int index = 0; index < finalistCount; index++) {
            CoarseCandidate candidate = Objects.requireNonNull(
                    finalists.get(index),
                    "coarseCandidate");
            FineCandidateEvaluation evaluation;
            try {
                evaluation = evaluateCandidate(
                        config,
                        queries,
                        candidate,
                        airByRotation.computeIfAbsent(
                                candidate.rotation(),
                                ignored -> explicitAirByColumn(candidate.structure())));
            } catch (GeneratorQueryBudgetExceededException failure) {
                /*
                 * The finalist under evaluation is incomplete and cannot be
                 * counted either way, but the ones already evaluated are
                 * whole and stay in the result.
                 */
                budgetFailure = failure;
                break;
            }
            evaluations.add(evaluation);
            if (evaluation.accepted()) {
                accepted.add(evaluation);
            }
        }
        accepted.sort(ACCEPTED_ORDER);
        return new FineSearchResult(
                evaluations,
                accepted,
                Optional.ofNullable(budgetFailure));
    }

    /**
     * The candidates worth a full evaluation, one position at a time.
     *
     * <p>The coarse search ranks every rotation of a position separately, and
     * rotations that share a bounding box score identically, so the top of the
     * list can be four entries for the same place. Spending the finalist slots
     * that way narrows the search to a handful of positions; taking each
     * position's best rotation first and only then filling with the remaining
     * rotations keeps the same count of slots covering far more ground.
     */
    private static List<CoarseCandidate> selectFinalists(
            List<CoarseCandidate> coarseCandidates,
            int finalistCount) {
        int limit = Math.min(finalistCount, coarseCandidates.size());
        List<CoarseCandidate> finalists = new ArrayList<>(limit);
        List<CoarseCandidate> duplicates = new ArrayList<>();
        Set<Long> seenCenters = new HashSet<>();
        for (CoarseCandidate candidate : coarseCandidates) {
            if (finalists.size() == limit) {
                break;
            }
            if (seenCenters.add(pack(candidate.centerX(), candidate.centerZ()))) {
                finalists.add(candidate);
            } else if (duplicates.size() < limit) {
                duplicates.add(candidate);
            }
        }
        for (CoarseCandidate candidate : duplicates) {
            if (finalists.size() == limit) {
                break;
            }
            finalists.add(candidate);
        }
        return finalists;
    }

    private static FineCandidateEvaluation evaluateCandidate(
            ConfigSnapshot config,
            PlannerQueryContext queries,
            CoarseCandidate candidate,
            Map<Long, ColumnAir> explicitAirByColumn) {
        EnumSet<FineRejectionReason> reasons =
                EnumSet.noneOf(FineRejectionReason.class);
        FineSamplingGeometry geometry = FineSamplingGeometry.create(candidate, config);
        if (geometry == null) {
            reasons.add(candidate.structure().footprint().isEmpty()
                    ? FineRejectionReason.EMPTY_FOOTPRINT
                    : FineRejectionReason.AREA_OUTSIDE_WORLD_LIMITS);
            return FineCandidateEvaluation.incomplete(candidate, reasons);
        }
        if (!geometry.isInsideWorldBorder(candidate, queries)) {
            reasons.add(FineRejectionReason.OUTSIDE_WORLD_BORDER);
            return FineCandidateEvaluation.incomplete(candidate, reasons);
        }

        ColumnObservation[] observations =
                new ColumnObservation[geometry.samplePoints().size()];
        boolean allHeightsValid = true;
        BiomeClassifier.Classification siteBiome =
                BiomeClassifier.Classification.PREFERRED;
        for (int index = 0; index < geometry.samplePoints().size(); index++) {
            FineSamplingGeometry.SamplePoint point = geometry.samplePoints().get(index);
            int terrainHeight = queries.baseHeight(
                    point.x(),
                    point.z(),
                    Heightmap.Types.OCEAN_FLOOR_WG);
            int worldSurfaceHeight =
                    queries.baseHeight(
                            point.x(),
                            point.z(),
                            Heightmap.Types.WORLD_SURFACE_WG);
            if (!isValidHeight(terrainHeight, queries)
                    || !isValidHeight(worldSurfaceHeight, queries)
                    || worldSurfaceHeight < terrainHeight) {
                reasons.add(FineRejectionReason.INVALID_TERRAIN_HEIGHT);
                allHeightsValid = false;
                continue;
            }

            int groundY = terrainHeight - 1;
            BiomeClassifier.Classification biome =
                    queries.classifyBiomeAt(point.x(), groundY, point.z());
            siteBiome = combineBiomeClassifications(siteBiome, biome);
            if (biome == BiomeClassifier.Classification.EXCLUDED) {
                reasons.add(FineRejectionReason.EXCLUDED_BIOME);
            } else if (biome == BiomeClassifier.Classification.UNLISTED) {
                reasons.add(FineRejectionReason.UNLISTED_BIOME);
            }
            observations[index] = new ColumnObservation(
                    groundY,
                    terrainHeight,
                    worldSurfaceHeight,
                    biome);
        }
        if (!allHeightsValid) {
            return FineCandidateEvaluation.incomplete(candidate, reasons);
        }

        int[] footprintHeights = new int[geometry.footprintSampleCount()];
        int footprintIndex = 0;
        for (int index = 0; index < geometry.samplePoints().size(); index++) {
            if (geometry.samplePoints().get(index).footprint()) {
                footprintHeights[footprintIndex++] = observations[index].groundY;
            }
        }
        int groundSurfaceY = medianGroundY(footprintHeights);
        FineCandidatePlan plan = createPlan(candidate, geometry, groundSurfaceY);
        if (plan.structureBounds().minimum().getY() < queries.minBuildHeight()
                || plan.structureBounds().maximum().getY() >= queries.maxBuildHeight()) {
            reasons.add(FineRejectionReason.STRUCTURE_OUTSIDE_BUILD_HEIGHT);
        }

        int placementOriginY = plan.placementOrigin().getY();
        int waterColumns = 0;
        for (int index = 0; index < geometry.samplePoints().size(); index++) {
            FineSamplingGeometry.SamplePoint point = geometry.samplePoints().get(index);
            ColumnObservation observation = observations[index];
            boolean inspectSurface =
                    observation.worldSurfaceHeight > observation.terrainHeight;
            ColumnAir columnAir = point.footprint()
                    ? explicitAirByColumn.get(localKey(candidate, point))
                    : null;
            /*
             * Explicit air that sits entirely at or above the worldgen surface
             * cannot meet a fluid: the noise column is air up there by
             * definition. Reading the column anyway made the most expensive
             * query in planning near-mandatory for every footprint sample of
             * any structure with a hollow interior.
             */
            boolean inspectAir = columnAir != null
                    && (long) placementOriginY + columnAir.minimumRelativeY()
                            < observation.worldSurfaceHeight;
            if (!inspectSurface && !inspectAir) {
                continue;
            }

            TerrainColumn column = queries.baseColumn(point.x(), point.z());
            SurfaceFluid fluid = inspectSurface
                    ? classifyFluidRange(
                            column,
                            observation.terrainHeight,
                            observation.worldSurfaceHeight)
                    : SurfaceFluid.NONE;
            if (inspectAir) {
                fluid = combineFluids(
                        fluid,
                        classifyExplicitAirFluids(
                                column,
                                columnAir.relativeY(),
                                placementOriginY));
            }
            if (fluid == SurfaceFluid.UNSUPPORTED) {
                reasons.add(FineRejectionReason.LAVA_OR_UNSUPPORTED_FLUID);
            } else if (fluid == SurfaceFluid.WATER) {
                waterColumns++;
            }
        }

        FineCandidateMetrics metrics = calculateMetrics(
                geometry,
                observations,
                groundSurfaceY,
                waterColumns,
                siteBiome);
        if (metrics.elevationRange() > config.maximumElevationRange()) {
            reasons.add(FineRejectionReason.ELEVATION_RANGE_EXCEEDED);
        }
        if (metrics.maximumCutDepth() > config.maximumCutDepth()) {
            reasons.add(FineRejectionReason.CUT_DEPTH_EXCEEDED);
        }
        if (metrics.maximumFillDepth() > config.maximumFillDepth()) {
            reasons.add(FineRejectionReason.FILL_DEPTH_EXCEEDED);
        }
        if (metrics.maximumPerimeterError() > config.maximumPerimeterError()) {
            reasons.add(FineRejectionReason.PERIMETER_ERROR_EXCEEDED);
        }
        if (metrics.waterFraction() > config.maximumWaterFraction()) {
            reasons.add(FineRejectionReason.WATER_FRACTION_EXCEEDED);
        }
        return FineCandidateEvaluation.complete(candidate, plan, metrics, reasons);
    }

    private static FineCandidateMetrics calculateMetrics(
            FineSamplingGeometry geometry,
            ColumnObservation[] observations,
            int groundSurfaceY,
            int waterColumns,
            BiomeClassifier.Classification siteBiome) {
        int minimumGroundY = Integer.MAX_VALUE;
        int maximumGroundY = Integer.MIN_VALUE;
        int maximumPerimeterError = 0;
        int maximumCutDepth = 0;
        int maximumFillDepth = 0;
        double totalError = 0.0D;
        double totalCut = 0.0D;
        double totalFill = 0.0D;

        for (int index = 0; index < geometry.samplePoints().size(); index++) {
            FineSamplingGeometry.SamplePoint point = geometry.samplePoints().get(index);
            if (!point.footprint()) {
                continue;
            }
            int groundY = observations[index].groundY;
            minimumGroundY = Math.min(minimumGroundY, groundY);
            maximumGroundY = Math.max(maximumGroundY, groundY);
            // Normal cut/fill is only the surface-to-contact-plane delta.
            // Explicit template air is authorized excavation and is checked for fluids separately.
            int signedError = groundY - groundSurfaceY;
            int absoluteError = Math.abs(signedError);
            totalError += absoluteError;
            if (signedError > 0) {
                maximumCutDepth = Math.max(maximumCutDepth, signedError);
                totalCut += signedError;
            } else {
                int fill = -signedError;
                maximumFillDepth = Math.max(maximumFillDepth, fill);
                totalFill += fill;
            }
            if (point.perimeter()) {
                maximumPerimeterError =
                        Math.max(maximumPerimeterError, absoluteError);
            }
        }

        int footprintCount = geometry.footprintSampleCount();
        return new FineCandidateMetrics(
                footprintCount,
                geometry.perimeterSampleCount(),
                geometry.blendSampleCount(),
                geometry.samplePoints().size(),
                minimumGroundY,
                maximumGroundY,
                totalError / footprintCount,
                maximumPerimeterError,
                maximumCutDepth,
                maximumFillDepth,
                totalCut / footprintCount,
                totalFill / footprintCount,
                waterColumns,
                siteBiome);
    }

    private static FineCandidatePlan createPlan(
            CoarseCandidate candidate,
            FineSamplingGeometry geometry,
            int groundSurfaceY) {
        long placementOriginY = (long) groundSurfaceY + 1L
                - candidate.structure().markers().groundLevel().getY();
        if (placementOriginY < Integer.MIN_VALUE || placementOriginY > Integer.MAX_VALUE) {
            throw new IllegalStateException("Fine placement Y is outside integer coordinates");
        }
        int originY = (int) placementOriginY;
        BlockPos placementOrigin =
                new BlockPos(candidate.minimumX(), originY, candidate.minimumZ());
        StructureBounds relativeBounds = candidate.structure().bounds();
        StructureBounds absoluteBounds = new StructureBounds(
                placementOrigin.offset(relativeBounds.minimum()),
                placementOrigin.offset(relativeBounds.maximum()));
        return new FineCandidatePlan(
                groundSurfaceY,
                placementOrigin,
                absoluteBounds,
                geometry.minimumBlendX(),
                geometry.maximumBlendX(),
                geometry.minimumBlendZ(),
                geometry.maximumBlendZ());
    }

    /**
     * The explicit-air heights of one rotated structure, grouped by column and
     * carrying each column's lowest one.
     *
     * <p>This depends only on the rotated structure, not on where it is being
     * placed, so it is built once per rotation instead of once per finalist: a
     * structure with a hollow interior can hold tens of thousands of these
     * positions.
     */
    private static Map<Long, ColumnAir> explicitAirByColumn(
            RotatedStructureView structure) {
        Map<Long, List<Integer>> grouped = new HashMap<>();
        for (BlockPos position : structure.explicitAirPositions()) {
            grouped.computeIfAbsent(
                            pack(position.getX(), position.getZ()),
                            ignored -> new ArrayList<>())
                    .add(position.getY());
        }
        Map<Long, ColumnAir> columns = new HashMap<>(grouped.size());
        grouped.forEach((key, heights) -> {
            int minimum = Integer.MAX_VALUE;
            for (int height : heights) {
                minimum = Math.min(minimum, height);
            }
            columns.put(key, new ColumnAir(List.copyOf(heights), minimum));
        });
        return columns;
    }

    private record ColumnAir(List<Integer> relativeY, int minimumRelativeY) {
    }

    private static SurfaceFluid classifyExplicitAirFluids(
            TerrainColumn column,
            List<Integer> relativeY,
            int placementOriginY) {
        SurfaceFluid result = SurfaceFluid.NONE;
        for (int y : relativeY) {
            long absoluteY = (long) placementOriginY + y;
            if (absoluteY < column.minY() || absoluteY >= column.maxYExclusive()) {
                continue;
            }
            result = combineFluids(
                    result,
                    classifyFluidState(column.blockState((int) absoluteY)));
            if (result == SurfaceFluid.UNSUPPORTED) {
                return result;
            }
        }
        return result;
    }

    private static SurfaceFluid classifyFluidRange(
            TerrainColumn column,
            int lowerYInclusive,
            int upperYExclusive) {
        SurfaceFluid result = SurfaceFluid.NONE;
        int lowerY = Math.max(lowerYInclusive, column.minY());
        int upperY = Math.min(upperYExclusive, column.maxYExclusive());
        for (int y = lowerY; y < upperY; y++) {
            result = combineFluids(result, classifyFluidState(column.blockState(y)));
            if (result == SurfaceFluid.UNSUPPORTED) {
                return result;
            }
        }
        return result;
    }

    private static SurfaceFluid classifyFluidState(
            net.minecraft.world.level.block.state.BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return SurfaceFluid.NONE;
        }
        return (fluid.is(FluidTags.WATER)
                        || fluid.is(Fluids.WATER)
                        || fluid.is(Fluids.FLOWING_WATER))
                ? SurfaceFluid.WATER
                : SurfaceFluid.UNSUPPORTED;
    }

    private static SurfaceFluid combineFluids(
            SurfaceFluid first,
            SurfaceFluid second) {
        if (first == SurfaceFluid.UNSUPPORTED || second == SurfaceFluid.UNSUPPORTED) {
            return SurfaceFluid.UNSUPPORTED;
        }
        if (first == SurfaceFluid.WATER || second == SurfaceFluid.WATER) {
            return SurfaceFluid.WATER;
        }
        return SurfaceFluid.NONE;
    }

    static int medianGroundY(int[] heights) {
        if (heights.length == 0) {
            throw new IllegalArgumentException("Median requires at least one height");
        }
        int[] sorted = heights.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        if ((sorted.length & 1) == 1) {
            return sorted[middle];
        }
        // A half-block plane is impossible, so even medians deterministically use the lower block.
        return (int) Math.floorDiv(
                (long) sorted[middle - 1] + sorted[middle],
                2L);
    }

    static Comparator<FineCandidateEvaluation> acceptedOrder() {
        return ACCEPTED_ORDER;
    }

    private static FineCandidateMetrics requiredMetrics(
            FineCandidateEvaluation evaluation) {
        return evaluation.metrics().orElseThrow(
                () -> new IllegalArgumentException(
                        "Accepted candidate ordering requires complete metrics"));
    }

    private static BiomeClassifier.Classification combineBiomeClassifications(
            BiomeClassifier.Classification first,
            BiomeClassifier.Classification second) {
        if (first == BiomeClassifier.Classification.EXCLUDED
                || second == BiomeClassifier.Classification.EXCLUDED) {
            return BiomeClassifier.Classification.EXCLUDED;
        }
        if (first == BiomeClassifier.Classification.UNLISTED
                || second == BiomeClassifier.Classification.UNLISTED) {
            return BiomeClassifier.Classification.UNLISTED;
        }
        if (first == BiomeClassifier.Classification.FALLBACK_LAND
                || second == BiomeClassifier.Classification.FALLBACK_LAND) {
            return BiomeClassifier.Classification.FALLBACK_LAND;
        }
        return BiomeClassifier.Classification.PREFERRED;
    }

    private static boolean isValidHeight(
            int height,
            PlannerQueryContext queries) {
        return height > queries.minBuildHeight() && height <= queries.maxBuildHeight();
    }

    private static long localKey(
            CoarseCandidate candidate,
            FineSamplingGeometry.SamplePoint point) {
        return pack(
                point.x() - candidate.minimumX(),
                point.z() - candidate.minimumZ());
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private record ColumnObservation(
            int groundY,
            int terrainHeight,
            int worldSurfaceHeight,
            BiomeClassifier.Classification biome) {
    }

    private enum SurfaceFluid {
        NONE,
        WATER,
        UNSUPPORTED
    }
}
