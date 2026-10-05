package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class TerrainBlendPlanner {
    private static final int FILLER_DEPTH = 3;
    private static final int CUT_PROFILE_DEPTH = 4;
    private static final Comparator<ChunkPos> CHUNK_ORDER =
            Comparator.comparingInt((ChunkPos chunk) -> chunk.x)
                    .thenComparingInt(chunk -> chunk.z);
    private static final Comparator<TerrainWrite> WRITE_ORDER =
            Comparator.comparingInt(
                            (TerrainWrite write) ->
                                    write.position().getX())
                    .thenComparingInt(write ->
                            write.position().getZ())
                    .thenComparingInt(write ->
                            write.position().getY())
                    .thenComparingInt(write ->
                            write.kind().ordinal());
    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt(
                            (BlockPos position) ->
                                    position.getX())
                    .thenComparingInt(BlockPos::getZ)
                    .thenComparingInt(BlockPos::getY);

    private TerrainBlendPlanner() {
    }

    static TerrainBlendPlan plan(
            TerrainLevelingResult leveling,
            RotatedStructureView structure,
            BlockPos placementOrigin,
            ConfigSnapshot config,
            SnowCoverPlanner.Climate climate) {
        return plan(
                new PreparedTerrainLeveling(
                        leveling.snapshot(),
                        leveling.plan()),
                structure,
                placementOrigin,
                config,
                climate);
    }

    static TerrainBlendPlan plan(
            PreparedTerrainLeveling leveling,
            RotatedStructureView structure,
            BlockPos placementOrigin,
            ConfigSnapshot config,
            SnowCoverPlanner.Climate climate) {
        TerrainSnapshot snapshot = leveling.snapshot();
        if (leveling.plan().snapshot() != snapshot) {
            throw new IllegalArgumentException(
                    "Leveling result and plan use different snapshots");
        }
        PlacementBounds interventionBounds =
                PlacementBounds.calculate(
                        structure,
                        placementOrigin,
                        config.blendWidth());
        if (!snapshot.bounds().contains(interventionBounds)) {
            throw new IllegalArgumentException(
                    "Terrain snapshot does not contain placement intervention bounds");
        }
        FootprintDistanceIndex distances =
                FootprintDistanceIndex.create(
                        structure,
                        placementOrigin);
        Block siteSurface = TemplateSiteAdaptation.materials(
                        snapshot,
                        interventionBounds)
                .surface();
        Map<BlockPos, BlockState> stateAfterLeveling =
                new HashMap<>(leveling.plan().totalWrites());
        for (TerrainWrite write : leveling.plan().writes()) {
            stateAfterLeveling.put(
                    write.position(),
                    write.targetState());
        }

        /*
         * The ring only eases the land into the structure's level, so a few
         * columns a little deeper than the footprint may go are a filled pit
         * or a trimmed knoll, not a foundation. The footprint itself is held
         * to the plain limits before blending runs.
         */
        int maximumBlendCut = config.maximumCutDepth()
                + config.blendDepthAllowance();
        int maximumBlendFill = config.maximumFillDepth()
                + config.blendDepthAllowance();
        List<BlendColumnTarget> targets =
                new ArrayList<>(snapshot.columns().size());
        Map<BlockPos, TerrainWrite> writes =
                new LinkedHashMap<>();
        int resurfacedTrunkGrounds = 0;
        for (TerrainColumnSnapshot column
                : snapshot.columns()) {
            double distance =
                    distances.distance(column.x(), column.z());
            double interpolation = Math.min(
                    1.0D,
                    distance / config.blendWidth());
            int targetY = interpolatedHeight(
                    snapshot.targetGroundY(),
                    column.groundY(),
                    interpolation);
            BlendColumnTarget target =
                    new BlendColumnTarget(
                            column.x(),
                            column.z(),
                            distance,
                            interpolation,
                            column.groundY(),
                            targetY);
            targets.add(target);
            int signedError = column.groundY() - targetY;
            if (signedError > maximumBlendCut
                    || -signedError > maximumBlendFill) {
                throw UnsuitableGeneratedSiteException
                        .blendThresholdExceeded(
                                column,
                                targetY,
                                config.maximumCutDepth(),
                                config.maximumFillDepth(),
                                config.blendDepthAllowance());
            }
            if (target.modifiesHeight()) {
                BlockState surface = surfaceMaterial(
                        column,
                        distances.contains(column.x(), column.z()),
                        siteSurface);
                if (!surface.equals(column.surfaceMaterial())) {
                    resurfacedTrunkGrounds++;
                }
                planTerrainColumn(
                        writes,
                        snapshot,
                        column,
                        targetY,
                        surface,
                        stateAfterLeveling);
            }
            if (target.modifiesHeight()
                    || distances.contains(
                            column.x(),
                            column.z())) {
                planVegetation(
                        writes,
                        snapshot,
                        column,
                        targetY,
                        stateAfterLeveling);
            }
        }

        Set<BlockPos> occupiedWritePositions = new HashSet<>();
        for (TerrainWrite write : leveling.plan().writes()) {
            occupiedWritePositions.add(write.position());
        }
        occupiedWritePositions.addAll(writes.keySet());
        TreeCleanupPlanner.Result treeCleanup =
                TreeCleanupPlanner.plan(
                        leveling,
                        targets,
                        structure,
                        placementOrigin,
                        occupiedWritePositions,
                        siteSurface);
        Set<BlockPos> treeCleanupWritePositions =
                new LinkedHashSet<>();
        for (TerrainWrite write : treeCleanup.writes()) {
            addWrite(
                    writes,
                    write.position(),
                    write.originalState(),
                    write.targetState(),
                    write.kind());
            treeCleanupWritePositions.add(write.position());
        }
        SnowCoverPlanner.cover(
                writes,
                changedColumnsAround(
                        snapshot,
                        targets,
                        treeCleanupWritePositions,
                        distances),
                SnowCoverPlanner.lightSources(structure, placementOrigin),
                climate);
        /*
         * Beyond the blend only tree cleanup may write, up to the observation
         * margin, so the snow it uncovers there is applied and checked with
         * the tree-cleanup writes.
         */
        for (TerrainWrite write : writes.values()) {
            if (write.kind() == TerrainWrite.Kind.SNOW_COVER
                    && !interventionBounds.containsHorizontal(
                            write.position().getX(),
                            write.position().getZ())) {
                treeCleanupWritePositions.add(write.position());
            }
        }

        Map<ChunkPos, List<TerrainWrite>> grouped =
                new TreeMap<>(CHUNK_ORDER);
        for (TerrainWrite write : writes.values()) {
            grouped.computeIfAbsent(
                            new ChunkPos(write.position()),
                            ignored -> new ArrayList<>())
                    .add(write);
        }
        Map<ChunkPos, List<TerrainWrite>> ordered =
                new LinkedHashMap<>();
        for (Map.Entry<ChunkPos, List<TerrainWrite>> entry
                : grouped.entrySet()) {
            entry.getValue().sort(WRITE_ORDER);
            ordered.put(entry.getKey(), entry.getValue());
        }
        List<TerrainWrite> fluidRelevantWrites =
                new ArrayList<>(
                        leveling.plan().totalWrites()
                                + writes.size());
        fluidRelevantWrites.addAll(
                leveling.plan().writes());
        fluidRelevantWrites.addAll(writes.values());
        Set<BlockPos> fluidUpdates = collectFluidUpdates(
                snapshot,
                fluidRelevantWrites);
        return new TerrainBlendPlan(
                snapshot,
                interventionBounds,
                targets,
                ordered,
                fluidUpdates,
                treeCleanupWritePositions,
                treeCleanup.selectedTreeCount(),
                treeCleanup.selectedBlockCount(),
                treeCleanup.selectedAccessoryCount(),
                resurfacedTrunkGrounds
                        + treeCleanup.resurfacedTrunkGrounds());
    }

    /*
     * The columns whose surface the mod changes outside the structure: the
     * reshaped part of the ring and every column tree cleanup clears. The
     * footprint is the template's own surface.
     */
    private static List<TerrainColumnSnapshot> changedColumnsAround(
            TerrainSnapshot snapshot,
            List<BlendColumnTarget> targets,
            Set<BlockPos> treeCleanupWritePositions,
            FootprintDistanceIndex distances) {
        Set<Long> changed = new LinkedHashSet<>();
        for (BlendColumnTarget target : targets) {
            if (target.modifiesHeight()
                    && !distances.contains(target.x(), target.z())) {
                changed.add(TerrainSnapshot.pack(target.x(), target.z()));
            }
        }
        for (BlockPos position : treeCleanupWritePositions) {
            if (!distances.contains(position.getX(), position.getZ())) {
                changed.add(TerrainSnapshot.pack(
                        position.getX(),
                        position.getZ()));
            }
        }
        List<TerrainColumnSnapshot> columns = new ArrayList<>(changed.size());
        for (TerrainColumnSnapshot column : snapshot.columns()) {
            if (changed.contains(TerrainSnapshot.pack(column.x(), column.z()))) {
                columns.add(column);
            }
        }
        return columns;
    }

    static double smoothstep(double value) {
        double clamped = Math.max(
                0.0D,
                Math.min(1.0D, value));
        return clamped * clamped
                * (3.0D - 2.0D * clamped);
    }

    static int interpolatedHeight(
            int targetGroundY,
            int originalGroundY,
            double interpolation) {
        double weight = smoothstep(interpolation);
        double height = targetGroundY
                + (originalGroundY - targetGroundY)
                        * weight;
        long rounded = Math.round(height);
        if (rounded < Integer.MIN_VALUE
                || rounded > Integer.MAX_VALUE) {
            throw new PlacementPreparationException(
                    "Blended terrain height exceeds world coordinates");
        }
        return (int) rounded;
    }

    /*
     * The dirt a tree feature put under a trunk is not the column's own
     * surface. Every trunk in a reshaped column goes, so the site's surface
     * takes the top there instead. The footprint keeps it, as tree cleanup
     * leaves it: the ground there is the template's to set.
     */
    private static BlockState surfaceMaterial(
            TerrainColumnSnapshot column,
            boolean footprint,
            Block siteSurface) {
        return !footprint
                && TerrainSurfaceClassifier.isTrunkGround(
                        column.surfaceMaterial(),
                        column.stateAboveGround())
                ? siteSurface.defaultBlockState()
                : column.surfaceMaterial();
    }

    private static void planTerrainColumn(
            Map<BlockPos, TerrainWrite> writes,
            TerrainSnapshot snapshot,
            TerrainColumnSnapshot column,
            int targetY,
            BlockState surface,
            Map<BlockPos, BlockState> stateAfterLeveling) {
        if (targetY < column.groundY()) {
            for (int y = column.groundY();
                    y > targetY;
                    y--) {
                BlockState expected = expectedState(
                        snapshot,
                        stateAfterLeveling,
                        column.x(),
                        y,
                        column.z());
                if (expected.getFluidState().isEmpty()) {
                    addWrite(
                            writes,
                            new BlockPos(
                                    column.x(),
                                    y,
                                    column.z()),
                            expected,
                            Blocks.AIR.defaultBlockState(),
                            TerrainWrite.Kind.BLEND_CUT);
                }
            }
            int minimumProfileY = Math.max(
                    column.minimumCapturedY(),
                    targetY - CUT_PROFILE_DEPTH);
            for (int y = minimumProfileY;
                    y <= targetY;
                    y++) {
                int depth = targetY - y;
                BlockState material = materialAtDepth(
                        column,
                        surface,
                        depth);
                addWrite(
                        writes,
                        new BlockPos(
                                column.x(),
                                y,
                                column.z()),
                        expectedState(
                                snapshot,
                                stateAfterLeveling,
                                column.x(),
                                y,
                                column.z()),
                        material,
                        TerrainWrite.Kind.BLEND_MATERIAL);
            }
        } else {
            for (int y = column.groundY();
                    y <= targetY;
                    y++) {
                int depth = targetY - y;
                addWrite(
                        writes,
                        new BlockPos(
                                column.x(),
                                y,
                                column.z()),
                        expectedState(
                                snapshot,
                                stateAfterLeveling,
                                column.x(),
                                y,
                                column.z()),
                        materialAtDepth(column, surface, depth),
                        TerrainWrite.Kind.BLEND_FILL);
            }
        }
    }

    private static void planVegetation(
            Map<BlockPos, TerrainWrite> writes,
            TerrainSnapshot snapshot,
            TerrainColumnSnapshot column,
            int targetY,
            Map<BlockPos, BlockState> stateAfterLeveling) {
        for (BlockPos position : column.vegetationPositions()) {
            if (position.getY() <= targetY
                    || writes.containsKey(position)) {
                continue;
            }
            BlockState expected = expectedState(
                    snapshot,
                    stateAfterLeveling,
                    position.getX(),
                    position.getY(),
                    position.getZ());
            if (!expected.isAir()
                    && expected.getFluidState().isEmpty()
                    && TerrainSurfaceClassifier
                            .isLightweightVegetation(expected)) {
                addWrite(
                        writes,
                        position,
                        expected,
                        Blocks.AIR.defaultBlockState(),
                        TerrainWrite.Kind.VEGETATION_CLEAR);
            }
        }
    }

    private static BlockState materialAtDepth(
            TerrainColumnSnapshot column,
            BlockState surface,
            int depth) {
        if (depth == 0) {
            return surface;
        }
        if (depth <= FILLER_DEPTH) {
            return column.fillerMaterial();
        }
        return column.deepMaterial();
    }

    private static BlockState expectedState(
            TerrainSnapshot snapshot,
            Map<BlockPos, BlockState> stateAfterLeveling,
            int x,
            int y,
            int z) {
        BlockPos position = new BlockPos(x, y, z);
        BlockState leveled = stateAfterLeveling.get(position);
        return leveled != null
                ? leveled
                : snapshot.column(x, z).stateAt(y);
    }

    private static void addWrite(
            Map<BlockPos, TerrainWrite> writes,
            BlockPos position,
            BlockState original,
            BlockState target,
            TerrainWrite.Kind kind) {
        if (original.equals(target)) {
            return;
        }
        TerrainWrite previous = writes.put(
                position,
                new TerrainWrite(
                        position,
                        original,
                        target,
                        kind));
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Multiple blend writes target " + position);
        }
    }

    private static Set<BlockPos> collectFluidUpdates(
            TerrainSnapshot snapshot,
            Iterable<TerrainWrite> writes) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (TerrainWrite write : writes) {
            addFluidIfCaptured(
                    snapshot,
                    write.position(),
                    positions);
            for (Direction direction : Direction.values()) {
                addFluidIfCaptured(
                        snapshot,
                        write.position().relative(direction),
                        positions);
            }
        }
        return positions.stream()
                .sorted(POSITION_ORDER)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll);
    }

    private static void addFluidIfCaptured(
            TerrainSnapshot snapshot,
            BlockPos position,
            Set<BlockPos> positions) {
        if (!snapshot.bounds().containsHorizontal(
                position.getX(),
                position.getZ())) {
            return;
        }
        TerrainColumnSnapshot column = snapshot.column(
                position.getX(),
                position.getZ());
        if (position.getY() < column.minimumCapturedY()
                || position.getY()
                        >= column.maximumCapturedYExclusive()) {
            return;
        }
        if (!column.stateAt(position.getY())
                .getFluidState()
                .isEmpty()) {
            positions.add(position.immutable());
        }
    }
}
