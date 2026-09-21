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
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

public final class CoarseSiteSearch {
    static final int SAMPLE_COUNT = 9;

    private static final int MAX_LATTICE_POSITIONS = 1 << 16;
    private static final int MAX_LATTICE_EXTENT = 1 << 10;

    /**
     * Band first, then cost.
     *
     * <p>Ranking on flatness alone let a site four kilometres away win over one
     * at a hundred metres for a tenth of a block of average error, which is not
     * a trade a player asked for. Distance stays a late tie-break: inside a
     * band every position is equally acceptable, so the band boundary carries
     * the preference and the search order carries the rest.
     */
    private static final Comparator<CoarseCandidate> COARSE_ORDER =
            Comparator.comparingInt(
                            (CoarseCandidate candidate) ->
                                    candidate.searchBand().ordinal())
                    .thenComparing(candidate ->
                            candidate.metrics().structureConflict())
                    .thenComparingDouble(candidate ->
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

    /**
     * Searches every band.
     *
     * <p>The world seed is not an input: positions come from a lattice, so the
     * same configuration and the same terrain give the same candidates. What
     * the seed decides is the terrain itself, which the queries already carry.
     */
    public static CoarseSearchResult searchDetailed(
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure) {
        CoarseSearchResult near = searchBand(
                config,
                queries,
                structure,
                CoarseCandidate.SearchBand.NEAR);
        if (near.budgetFailure().isPresent()) {
            return near;
        }
        return sorted(near.merge(searchBand(
                config,
                queries,
                structure,
                CoarseCandidate.SearchBand.FAR)));
    }

    private static CoarseSearchResult sorted(CoarseSearchResult result) {
        List<CoarseCandidate> candidates =
                new ArrayList<>(result.acceptedCandidates());
        candidates.sort(COARSE_ORDER);
        return result.withCandidates(candidates);
    }

    /**
     * Searches one band on its own.
     */
    public static CoarseSearchResult searchBand(
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            CoarseCandidate.SearchBand band) {
        return searchPositions(
                config,
                queries,
                structure,
                band,
                bandPositions(config, band));
    }

    /**
     * The positions of one band, nearest first, already cut to what the
     * candidate budget allows.
     *
     * <p>Exposed so that a caller can walk them a slice at a time and stop
     * once it has enough: finishing a whole band before deciding costs the
     * positions it never needed, and inside a band a kilometre wide it also
     * lets a flatter site far out beat a good one close by, which is not the
     * preference the ordering is meant to express.
     */
    public static List<LatticePosition> bandPositions(
            ConfigSnapshot config,
            CoarseCandidate.SearchBand band) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(band, "band");
        return latticePositions(band, positionBudget(config, band), config);
    }

    private static int positionBudget(
            ConfigSnapshot config,
            CoarseCandidate.SearchBand band) {
        int locationBudget =
                config.maximumCoarseCandidates() / config.allowedRotations().size();
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
        BandBudget bandBudget =
                distributeBudget(locationBudget, nearCapacity, farCapacity);
        return band == CoarseCandidate.SearchBand.NEAR
                ? bandBudget.near()
                : bandBudget.far();
    }

    /** Evaluates exactly the positions given, in the order given. */
    public static CoarseSearchResult searchPositions(
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            CoarseCandidate.SearchBand band,
            List<LatticePosition> positions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(positions, "positions");

        List<Rotation> rotations = config.allowedRotations();
        int locationBudget = config.maximumCoarseCandidates() / rotations.size();
        if (locationBudget == 0) {
            throw new IllegalArgumentException(
                    "maximumCoarseCandidates must allow one complete set of rotations");
        }

        int expectedCandidates = (int) Math.min(
                4096L,
                Math.min(
                        config.maximumCoarseCandidates(),
                        (long) positions.size() * rotations.size()));
        List<CoarseCandidate> candidates = new ArrayList<>(expectedCandidates);
        Set<Long> visitedCenters = new HashSet<>();
        BlockPos origin = queries.suggestedSpawnOrigin();
        SearchDiagnostics diagnostics = new SearchDiagnostics();

        GeneratorQueryBudgetExceededException budgetFailure = evaluateBand(
                band,
                positions,
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
        return diagnostics.result(candidates, budgetFailure);
    }

    /**
     * Evaluates one band and returns the budget failure that stopped it,
     * or {@code null} when the band completed. Stopping keeps every
     * candidate found so far: the query budget bounds how far the search
     * looks, not whether its work survives.
     */
    private static GeneratorQueryBudgetExceededException evaluateBand(
            CoarseCandidate.SearchBand band,
            List<LatticePosition> positions,
            BlockPos origin,
            ConfigSnapshot config,
            PlannerQueryContext queries,
            StructureDefinition structure,
            List<Rotation> rotations,
            Set<Long> visitedCenters,
            List<CoarseCandidate> candidates,
            SearchDiagnostics diagnostics) {
        for (LatticePosition position : positions) {
            long centerX = (long) origin.getX() + position.offsetX();
            long centerZ = (long) origin.getZ() + position.offsetZ();
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

            long distanceSquared = position.distanceSquared();
            for (Rotation rotation : rotations) {
                diagnostics.beginCandidate();
                RotatedStructureView view = structure.view(rotation);
                CoarseCandidate candidate;
                try {
                    candidate = evaluateCandidate(
                            candidateX,
                            candidateZ,
                            band,
                            distanceSquared,
                            view,
                            config,
                            queries,
                            diagnostics);
                } catch (GeneratorQueryBudgetExceededException budgetFailure) {
                    diagnostics.abandonCandidate();
                    return budgetFailure;
                }
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return null;
    }

    private static CoarseCandidate evaluateCandidate(
            int centerX,
            int centerZ,
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

        /*
         * Ground height and biome first, across every sample. Both can reject
         * the candidate on their own, and interleaving the surface heightmap
         * with them paid for surface queries on samples that a later sample
         * was about to throw away anyway.
         */
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
        }

        /* Only a candidate that survived the first pass is worth a surface query. */
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            int x = sampleX[sample];
            int z = sampleZ[sample];
            int terrainHeight = groundHeights[sample] + 1;
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
                siteBiome,
                mayContainStructureStart(bounds, config, queries));
        return new CoarseCandidate(
                centerX,
                centerZ,
                band,
                structure,
                bounds.minimumX,
                bounds.maximumX,
                bounds.minimumZ,
                bounds.maximumZ,
                distanceSquared,
                metrics);
    }

    /**
     * Whether a generated structure may start where this site would be built,
     * asked only once the site has otherwise been accepted.
     *
     * <p>The flattest ground a search can find is usually a village, and
     * discovering that only after the area has been generated in full costs
     * seconds of synchronous worldgen for every retry, and moves the world
     * spawn after chunks around the old one already exist. The blend margin is
     * included because the site modifies terrain out to it.
     */
    private static boolean mayContainStructureStart(
            CandidateBounds bounds,
            ConfigSnapshot config,
            PlannerQueryContext queries) {
        int margin = config.blendWidth();
        return queries.mayContainStructureStart(
                chunkOf((long) bounds.minimumX - margin),
                chunkOf((long) bounds.minimumZ - margin),
                chunkOf((long) bounds.maximumX + margin),
                chunkOf((long) bounds.maximumZ + margin));
    }

    private static int chunkOf(long blockCoordinate) {
        long clamped = Math.max(
                Integer.MIN_VALUE,
                Math.min(Integer.MAX_VALUE, blockCoordinate));
        return SectionPos.blockToSectionCoord(clamped);
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

    /**
     * The positions of one band, nearest first.
     *
     * <p>Candidates sit on a square lattice anchored on the origin and are
     * visited in order of true distance, so the search reaches a usable site
     * near the vanilla spawn before it spends anything on far ones. The
     * lattice also keeps the whole thing free of trigonometry: {@code sin} and
     * {@code cos} are allowed to differ by an ulp between the interpreter, the
     * JIT and another CPU, which was enough to move a rounded centre and break
     * the promise that one seed gives one result everywhere.
     */
    static List<LatticePosition> latticePositions(
            CoarseCandidate.SearchBand band,
            int positionBudget,
            ConfigSnapshot config) {
        if (positionBudget <= 0) {
            return List.of();
        }
        boolean near = band == CoarseCandidate.SearchBand.NEAR;
        long innerRadius = near ? -1L : config.preferredSearchRadius();
        long outerRadius = near
                ? config.preferredSearchRadius()
                : config.maximumSearchRadius();
        if (outerRadius <= innerRadius) {
            return List.of();
        }
        int step = boundedStep(
                near ? config.nearCandidateSpacing() : config.farCandidateSpacing(),
                innerRadius,
                outerRadius);
        long innerSquared = innerRadius < 0L ? -1L : innerRadius * innerRadius;
        long outerSquared = outerRadius * outerRadius;
        int extent = (int) Math.min(
                (long) MAX_LATTICE_EXTENT,
                outerRadius / step);

        List<LatticePosition> positions = new ArrayList<>();
        for (int i = -extent; i <= extent; i++) {
            long offsetX = (long) i * step;
            for (int j = -extent; j <= extent; j++) {
                long offsetZ = (long) j * step;
                long distanceSquared = offsetX * offsetX + offsetZ * offsetZ;
                if (distanceSquared <= innerSquared
                        || distanceSquared > outerSquared) {
                    continue;
                }
                positions.add(new LatticePosition(
                        offsetX,
                        offsetZ,
                        distanceSquared));
            }
        }
        positions.sort(LATTICE_ORDER);
        return thinToBudget(positions, positionBudget);
    }

    /**
     * Keeps {@code positionBudget} positions spread evenly through the ordered
     * band, first and last included.
     *
     * <p>Simply cutting the list short would crowd every position against the
     * band's inner edge and leave its outer part unsearched, and widening the
     * lattice until it happens to hold the right number of points is only
     * approximate: it overshoots badly for small budgets, where one position
     * can end up standing for a whole band. Thinning an exact list is exact.
     */
    private static List<LatticePosition> thinToBudget(
            List<LatticePosition> ordered,
            int positionBudget) {
        int size = ordered.size();
        if (size <= positionBudget) {
            return ordered;
        }
        if (positionBudget == 1) {
            return List.of(ordered.getFirst());
        }
        List<LatticePosition> thinned = new ArrayList<>(positionBudget);
        for (int index = 0; index < positionBudget; index++) {
            long scaled = (long) index * (size - 1);
            thinned.add(ordered.get((int) (scaled / (positionBudget - 1))));
        }
        return thinned;
    }

    /**
     * The configured spacing, widened only as far as it takes to keep the
     * generated lattice within {@link #MAX_LATTICE_POSITIONS}. Density is the
     * budget's business; this only stops an extreme radius and spacing from
     * asking for an unbounded list.
     */
    private static int boundedStep(
            int configuredSpacing,
            long innerRadius,
            long outerRadius) {
        double inner = Math.max(0L, innerRadius);
        double area = Math.PI
                * ((double) outerRadius * outerRadius - inner * inner);
        double required = Math.sqrt(area / MAX_LATTICE_POSITIONS);
        if (!Double.isFinite(required) || required <= configuredSpacing) {
            return configuredSpacing;
        }
        return (int) Math.min(
                (long) Integer.MAX_VALUE,
                (long) Math.ceil(required));
    }

    private static int saturatedAdd(int first, int second) {
        long sum = (long) first + second;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static final Comparator<LatticePosition> LATTICE_ORDER =
            Comparator.comparingLong(LatticePosition::distanceSquared)
                    .thenComparingLong(LatticePosition::offsetX)
                    .thenComparingLong(LatticePosition::offsetZ);

    record LatticePosition(
            long offsetX,
            long offsetZ,
            long distanceSquared) {
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

        /*
         * A candidate cut short by the query budget is neither accepted nor
         * rejected, so it must leave the evaluated count untouched.
         */
        private void abandonCandidate() {
            evaluatedCandidateCount =
                    Math.decrementExact(evaluatedCandidateCount);
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
                List<CoarseCandidate> acceptedCandidates,
                GeneratorQueryBudgetExceededException budgetFailure) {
            return new CoarseSearchResult(
                    acceptedCandidates,
                    evaluatedCandidateCount,
                    rejectedCandidateCount,
                    rejectionCounts,
                    Optional.ofNullable(budgetFailure));
        }
    }

    private enum SurfaceFluid {
        NONE,
        WATER,
        UNSUPPORTED
    }
}
