package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

public final class CoarseSiteSearch {
    static final int SAMPLE_COUNT = 9;

    private static final double TWO_PI = Math.PI * 2.0D;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0D - Math.sqrt(5.0D));
    private static final long NEAR_PHASE_SALT = 0x243F6A8885A308D3L;
    private static final long FAR_PHASE_SALT = 0x13198A2E03707344L;
    private static final Comparator<CoarseCandidate> COARSE_ORDER =
            Comparator.comparingDouble(
                            (CoarseCandidate candidate) ->
                                    candidate.metrics().meanAbsoluteGroundError())
                    .thenComparingInt(candidate -> candidate.metrics().elevationRange())
                    .thenComparingDouble(candidate -> candidate.metrics().fluidFraction())
                    .thenComparingInt(candidate ->
                            candidate.metrics().biomeClassification().ordinal())
                    .thenComparingLong(CoarseCandidate::distanceSquared)
                    .thenComparingInt(candidate -> candidate.rotation().ordinal())
                    .thenComparingInt(CoarseCandidate::centerX)
                    .thenComparingInt(CoarseCandidate::centerZ);

    private CoarseSiteSearch() {
    }

    public static List<CoarseCandidate> search(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure) {
        return searchDetailed(seed, config, queries, structure)
                .acceptedCandidates();
    }

    public static CoarseSearchResult searchDetailed(
            long seed,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(structure, "structure");

        List<Rotation> rotations = config.allowedRotations();
        int locationBudget = config.maximumCoarseCandidates() / rotations.size();
        if (locationBudget == 0) {
            throw new IllegalArgumentException(
                    "maximumCoarseCandidates must allow one complete set of rotations");
        }

        int nearCapacity = estimatedCapacity(
                0,
                config.preferredSearchRadius(),
                config.nearCandidateSpacing(),
                true);
        int farCapacity = estimatedCapacity(
                config.preferredSearchRadius(),
                config.maximumSearchRadius(),
                config.farCandidateSpacing(),
                false);
        locationBudget = Math.min(
                locationBudget,
                saturatedAdd(nearCapacity, farCapacity));

        BandBudget bandBudget = distributeBudget(locationBudget, nearCapacity, farCapacity);
        int expectedCandidates = (int) Math.min(
                4096L,
                Math.min(
                        config.maximumCoarseCandidates(),
                        (long) locationBudget * rotations.size()));
        List<CoarseCandidate> candidates = new ArrayList<>(expectedCandidates);
        Set<Long> visitedCenters = new HashSet<>();
        BlockPos origin = queries.suggestedSpawnOrigin();
        SearchDiagnostics diagnostics = new SearchDiagnostics();

        evaluateBand(
                seed,
                CoarseCandidate.SearchBand.NEAR,
                bandBudget.near(),
                origin,
                config,
                queries,
                structure,
                rotations,
                visitedCenters,
                candidates,
                diagnostics);
        evaluateBand(
                seed,
                CoarseCandidate.SearchBand.FAR,
                bandBudget.far(),
                origin,
                config,
                queries,
                structure,
                rotations,
                visitedCenters,
                candidates,
                diagnostics);

        candidates.sort(COARSE_ORDER);
        if (candidates.size() > config.maximumCoarseCandidates()) {
            throw new IllegalStateException(
                    "Coarse search exceeded maximumCoarseCandidates");
        }
        return diagnostics.result(candidates);
    }

    private static void evaluateBand(
            long seed,
            CoarseCandidate.SearchBand band,
            int positionCount,
            BlockPos origin,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            List<Rotation> rotations,
            Set<Long> visitedCenters,
            List<CoarseCandidate> candidates,
            SearchDiagnostics diagnostics) {
        double basePhase = phase(seed, band);
        for (int positionIndex = 0; positionIndex < positionCount; positionIndex++) {
            int radius = radiusFor(band, positionIndex, positionCount, config);
            double angle = basePhase + GOLDEN_ANGLE * positionIndex;
            long centerX = (long) origin.getX() + Math.round(Math.cos(angle) * radius);
            long centerZ = (long) origin.getZ() + Math.round(Math.sin(angle) * radius);
            if (centerX < Integer.MIN_VALUE || centerX > Integer.MAX_VALUE
                    || centerZ < Integer.MIN_VALUE || centerZ > Integer.MAX_VALUE) {
                diagnostics.rejectCandidates(
                        rotations.size(),
                        CoarseRejectionReason.AREA_OUTSIDE_WORLD_LIMITS);
                continue;
            }
            int candidateX = (int) centerX;
            int candidateZ = (int) centerZ;
            if (!visitedCenters.add(pack(candidateX, candidateZ))) {
                continue;
            }

            long deltaX = centerX - origin.getX();
            long deltaZ = centerZ - origin.getZ();
            long distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            for (Rotation rotation : rotations) {
                diagnostics.beginCandidate();
                RotatedStructureView view = structure.view(rotation);
                CoarseCandidate candidate = evaluateCandidate(
                        candidateX,
                        candidateZ,
                        radius,
                        band,
                        distanceSquared,
                        view,
                        config,
                        queries,
                        diagnostics);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
    }

    private static CoarseCandidate evaluateCandidate(
            int centerX,
            int centerZ,
            int ringRadius,
            CoarseCandidate.SearchBand band,
            long distanceSquared,
            RotatedStructureView structure,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            SearchDiagnostics diagnostics) {
        CandidateBounds bounds = centeredBounds(centerX, centerZ, structure);
        if (bounds == null) {
            return diagnostics.reject(
                    CoarseRejectionReason.AREA_OUTSIDE_WORLD_LIMITS);
        }

        int middleX = bounds.minimumX + (bounds.maximumX - bounds.minimumX) / 2;
        int middleZ = bounds.minimumZ + (bounds.maximumZ - bounds.minimumZ) / 2;
        int[] sampleX = {
            middleX,
            bounds.minimumX,
            bounds.maximumX,
            bounds.maximumX,
            bounds.minimumX,
            middleX,
            bounds.maximumX,
            middleX,
            bounds.minimumX
        };
        int[] sampleZ = {
            middleZ,
            bounds.minimumZ,
            bounds.minimumZ,
            bounds.maximumZ,
            bounds.maximumZ,
            bounds.minimumZ,
            middleZ,
            bounds.maximumZ,
            middleZ
        };

        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            if (!queries.isWithinWorldBorder(sampleX[sample], sampleZ[sample])) {
                return diagnostics.reject(
                        CoarseRejectionReason.OUTSIDE_WORLD_BORDER);
            }
        }

        int[] groundHeights = new int[SAMPLE_COUNT];
        int minimumGroundY = Integer.MAX_VALUE;
        int maximumGroundY = Integer.MIN_VALUE;
        int fluidColumns = 0;
        BiomeClassifier.Classification siteBiome =
                BiomeClassifier.Classification.PREFERRED;

        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            int x = sampleX[sample];
            int z = sampleZ[sample];
            int terrainHeight = queries.baseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG);
            if (!isValidHeight(terrainHeight, queries)) {
                return diagnostics.reject(
                        CoarseRejectionReason.INVALID_TERRAIN_HEIGHT);
            }
            int groundY = terrainHeight - 1;
            groundHeights[sample] = groundY;
            minimumGroundY = Math.min(minimumGroundY, groundY);
            maximumGroundY = Math.max(maximumGroundY, groundY);
            if (maximumGroundY - minimumGroundY > config.maximumElevationRange()) {
                return diagnostics.reject(
                        CoarseRejectionReason.ELEVATION_RANGE_EXCEEDED);
            }

            BiomeClassifier.Classification classification =
                    queries.classifyBiomeAt(x, groundY, z);
            if (classification == BiomeClassifier.Classification.EXCLUDED) {
                return diagnostics.reject(
                        CoarseRejectionReason.EXCLUDED_BIOME);
            }
            if (classification == BiomeClassifier.Classification.UNLISTED) {
                return diagnostics.reject(
                        CoarseRejectionReason.UNLISTED_BIOME);
            }
            if (classification == BiomeClassifier.Classification.FALLBACK_LAND) {
                siteBiome = BiomeClassifier.Classification.FALLBACK_LAND;
            }

            int worldSurfaceHeight =
                    queries.baseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG);
            if (!isValidHeight(worldSurfaceHeight, queries)) {
                return diagnostics.reject(
                        CoarseRejectionReason.INVALID_TERRAIN_HEIGHT);
            }
            if (worldSurfaceHeight > terrainHeight) {
                SurfaceFluid surfaceFluid = classifySurfaceFluid(
                        queries.baseColumn(x, z),
                        terrainHeight,
                        worldSurfaceHeight);
                if (surfaceFluid == SurfaceFluid.UNSUPPORTED) {
                    return diagnostics.reject(
                            CoarseRejectionReason.LAVA_OR_UNSUPPORTED_FLUID);
                }
                if (surfaceFluid == SurfaceFluid.WATER) {
                    fluidColumns++;
                    if (fluidColumns > config.maximumWaterFraction() * SAMPLE_COUNT) {
                        return diagnostics.reject(
                                CoarseRejectionReason.WATER_FRACTION_EXCEEDED);
                    }
                }
            }
        }

        if (!canFitStructureVertically(
                minimumGroundY,
                maximumGroundY,
                structure,
                queries)) {
            return diagnostics.reject(
                    CoarseRejectionReason.STRUCTURE_OUTSIDE_BUILD_HEIGHT);
        }

        double averageGroundY = 0.0D;
        for (int groundHeight : groundHeights) {
            averageGroundY += groundHeight;
        }
        averageGroundY /= SAMPLE_COUNT;
        double meanAbsoluteGroundError = 0.0D;
        for (int groundHeight : groundHeights) {
            meanAbsoluteGroundError += Math.abs(groundHeight - averageGroundY);
        }
        meanAbsoluteGroundError /= SAMPLE_COUNT;

        CoarseCandidateMetrics metrics = new CoarseCandidateMetrics(
                minimumGroundY,
                maximumGroundY,
                averageGroundY,
                meanAbsoluteGroundError,
                SAMPLE_COUNT,
                fluidColumns,
                siteBiome);
        return new CoarseCandidate(
                centerX,
                centerZ,
                ringRadius,
                band,
                structure,
                bounds.minimumX,
                bounds.maximumX,
                bounds.minimumZ,
                bounds.maximumZ,
                distanceSquared,
                metrics);
    }

    private static CandidateBounds centeredBounds(
            int centerX,
            int centerZ,
            RotatedStructureView structure) {
        long minimumX = (long) centerX - (structure.size().getX() - 1L) / 2L;
        long maximumX = minimumX + structure.size().getX() - 1L;
        long minimumZ = (long) centerZ - (structure.size().getZ() - 1L) / 2L;
        long maximumZ = minimumZ + structure.size().getZ() - 1L;
        if (minimumX < Integer.MIN_VALUE || maximumX > Integer.MAX_VALUE
                || minimumZ < Integer.MIN_VALUE || maximumZ > Integer.MAX_VALUE) {
            return null;
        }
        return new CandidateBounds(
                (int) minimumX,
                (int) maximumX,
                (int) minimumZ,
                (int) maximumZ);
    }

    private static boolean isValidHeight(int height, PlannerQueryContext queries) {
        return height > queries.minBuildHeight() && height <= queries.maxBuildHeight();
    }

    private static SurfaceFluid classifySurfaceFluid(
            TerrainColumn column,
            int lowerYInclusive,
            int upperYExclusive) {
        int lowerY = Math.max(lowerYInclusive, column.minY());
        int upperY = Math.min(upperYExclusive, column.maxYExclusive());
        boolean waterFound = false;
        for (int y = lowerY; y < upperY; y++) {
            var fluid = column.blockState(y).getFluidState();
            if (fluid.isEmpty()) {
                continue;
            }
            if (!fluid.is(FluidTags.WATER)
                    && !fluid.is(Fluids.WATER)
                    && !fluid.is(Fluids.FLOWING_WATER)) {
                return SurfaceFluid.UNSUPPORTED;
            }
            waterFound = true;
        }
        return waterFound ? SurfaceFluid.WATER : SurfaceFluid.NONE;
    }

    private static boolean canFitStructureVertically(
            int minimumGroundY,
            int maximumGroundY,
            RotatedStructureView structure,
            PlannerQueryContext queries) {
        long groundMarkerY = structure.markers().groundLevel().getY();
        long maximumRelativeY = structure.bounds().maximum().getY();
        long minimumAllowedGroundY =
                (long) queries.minBuildHeight() + groundMarkerY - 1L;
        long maximumAllowedGroundY =
                (long) queries.maxBuildHeight() - 2L + groundMarkerY - maximumRelativeY;
        return maximumGroundY >= minimumAllowedGroundY
                && minimumGroundY <= maximumAllowedGroundY;
    }

    private static BandBudget distributeBudget(
            int locationBudget,
            int nearCapacity,
            int farCapacity) {
        if (locationBudget <= 0) {
            return new BandBudget(0, 0);
        }
        if (farCapacity == 0) {
            return new BandBudget(Math.min(locationBudget, nearCapacity), 0);
        }
        if (nearCapacity == 0 || locationBudget == 1) {
            return new BandBudget(0, 1);
        }

        long totalCapacity = (long) nearCapacity + farCapacity;
        int near = (int) Math.round((double) locationBudget * nearCapacity / totalCapacity);
        near = Math.max(1, Math.min(nearCapacity, near));
        int far = Math.min(farCapacity, locationBudget - near);
        if (far == 0) {
            far = 1;
            near--;
        }
        if (near + far < locationBudget) {
            int nearRoom = nearCapacity - near;
            int addNear = Math.min(nearRoom, locationBudget - near - far);
            near += addNear;
            far += Math.min(farCapacity - far, locationBudget - near - far);
        }
        return new BandBudget(near, far);
    }

    private static int estimatedCapacity(
            int innerRadius,
            int outerRadius,
            int spacing,
            boolean includeCenter) {
        if (outerRadius < innerRadius || (!includeCenter && outerRadius == innerRadius)) {
            return 0;
        }
        double area = Math.PI
                * ((double) outerRadius * outerRadius - (double) innerRadius * innerRadius);
        double estimated = area / ((double) spacing * spacing) + (includeCenter ? 1.0D : 0.0D);
        if (estimated >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(includeCenter ? 1 : 0, (int) Math.ceil(estimated));
    }

    private static int radiusFor(
            CoarseCandidate.SearchBand band,
            int index,
            int count,
            ConfigSnapshot config) {
        if (band == CoarseCandidate.SearchBand.NEAR) {
            if (count == 1) {
                return config.maximumSearchRadius() == config.preferredSearchRadius()
                        ? config.preferredSearchRadius()
                        : 0;
            }
            if (index == 0 || config.preferredSearchRadius() == 0) {
                return 0;
            }
            if (index == count - 1) {
                return config.preferredSearchRadius();
            }
            double quantile = (double) index / (count - 1);
            double radius = config.preferredSearchRadius() * Math.sqrt(quantile);
            return snapRadius(
                    radius,
                    0,
                    config.preferredSearchRadius(),
                    config.nearCandidateSpacing());
        }

        if (index == count - 1) {
            return config.maximumSearchRadius();
        }
        double quantile = (double) (index + 1) / count;
        double innerSquared = (double) config.preferredSearchRadius()
                * config.preferredSearchRadius();
        double outerSquared = (double) config.maximumSearchRadius()
                * config.maximumSearchRadius();
        double radius = Math.sqrt(innerSquared + quantile * (outerSquared - innerSquared));
        return snapRadius(
                radius,
                config.preferredSearchRadius(),
                config.maximumSearchRadius(),
                config.farCandidateSpacing());
    }

    private static int snapRadius(
            double radius,
            int innerRadius,
            int outerRadius,
            int spacing) {
        long step = Math.max(1L, Math.round((radius - innerRadius) / spacing));
        long snapped = (long) innerRadius + step * spacing;
        return (int) Math.min(outerRadius, snapped);
    }

    private static double phase(long seed, CoarseCandidate.SearchBand band) {
        long salt = band == CoarseCandidate.SearchBand.NEAR
                ? NEAR_PHASE_SALT
                : FAR_PHASE_SALT;
        long mixed = mix64(seed ^ salt);
        return (mixed >>> 11) * 0x1.0p-53 * TWO_PI;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static int saturatedAdd(int first, int second) {
        long sum = (long) first + second;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private record BandBudget(int near, int far) {
    }

    private record CandidateBounds(
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ) {
    }

    private static final class SearchDiagnostics {
        private final EnumMap<CoarseRejectionReason, Integer> rejectionCounts =
                new EnumMap<>(CoarseRejectionReason.class);
        private int evaluatedCandidateCount;
        private int rejectedCandidateCount;

        private void beginCandidate() {
            evaluatedCandidateCount =
                    Math.incrementExact(evaluatedCandidateCount);
        }

        private void rejectCandidates(
                int candidateCount,
                CoarseRejectionReason reason) {
            evaluatedCandidateCount =
                    Math.addExact(evaluatedCandidateCount, candidateCount);
            rejectedCandidateCount =
                    Math.addExact(rejectedCandidateCount, candidateCount);
            rejectionCounts.merge(reason, candidateCount, Math::addExact);
        }

        private CoarseCandidate reject(CoarseRejectionReason reason) {
            rejectionCounts.merge(reason, 1, Math::addExact);
            rejectedCandidateCount =
                    Math.incrementExact(rejectedCandidateCount);
            return null;
        }

        private CoarseSearchResult result(
                List<CoarseCandidate> acceptedCandidates) {
            return new CoarseSearchResult(
                    acceptedCandidates,
                    evaluatedCandidateCount,
                    rejectedCandidateCount,
                    rejectionCounts);
        }
    }

    private enum SurfaceFluid {
        NONE,
        WATER,
        UNSUPPORTED
    }
}
