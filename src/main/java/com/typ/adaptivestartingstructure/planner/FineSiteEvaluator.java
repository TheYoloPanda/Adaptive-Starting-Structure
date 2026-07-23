package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

public final class FineSiteEvaluator {
    private static final Comparator<FineCandidateEvaluation> ACCEPTED_ORDER =
            Comparator.comparingDouble(
                            (FineCandidateEvaluation evaluation) ->
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
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(coarseCandidates, "coarseCandidates");

        int finalistCount = Math.min(config.fineCandidateCount(), coarseCandidates.size());
        List<FineCandidateEvaluation> evaluations = new ArrayList<>(finalistCount);
        List<FineCandidateEvaluation> accepted = new ArrayList<>(finalistCount);
        for (int index = 0; index < finalistCount; index++) {
            FineCandidateEvaluation evaluation = evaluateCandidate(
                    config,
                    queries,
                    Objects.requireNonNull(coarseCandidates.get(index), "coarseCandidate"));
            evaluations.add(evaluation);
            if (evaluation.accepted()) {
                accepted.add(evaluation);
            }
        }
        accepted.sort(ACCEPTED_ORDER);
        return new FineSearchResult(evaluations, accepted);
    }

    private static FineCandidateEvaluation evaluateCandidate(
            ConfigSnapshot config,
            PlannerQueryContext queries,
            CoarseCandidate candidate) {
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

        Map<Long, List<Integer>> explicitAirByColumn =
                explicitAirByColumn(candidate.structure());
        int waterColumns = 0;
        for (int index = 0; index < geometry.samplePoints().size(); index++) {
            FineSamplingGeometry.SamplePoint point = geometry.samplePoints().get(index);
            ColumnObservation observation = observations[index];
            boolean inspectSurface =
                    observation.worldSurfaceHeight > observation.terrainHeight;
            List<Integer> explicitAirY = point.footprint()
                    ? explicitAirByColumn.get(localKey(candidate, point))
                    : null;
            if (!inspectSurface && explicitAirY == null) {
                continue;
            }

            TerrainColumn column = queries.baseColumn(point.x(), point.z());
            SurfaceFluid fluid = inspectSurface
                    ? classifyFluidRange(
                            column,
                            observation.terrainHeight,
                            observation.worldSurfaceHeight)
                    : SurfaceFluid.NONE;
            if (explicitAirY != null) {
                fluid = combineFluids(
                        fluid,
                        classifyExplicitAirFluids(
                                column,
                                explicitAirY,
                                plan.placementOrigin().getY()));
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

    private static Map<Long, List<Integer>> explicitAirByColumn(
            RotatedStructureView structure) {
        Map<Long, List<Integer>> positions = new HashMap<>();
        for (BlockPos position : structure.explicitAirPositions()) {
            positions.computeIfAbsent(
                            pack(position.getX(), position.getZ()),
                            ignored -> new ArrayList<>())
                    .add(position.getY());
        }
        return positions;
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
