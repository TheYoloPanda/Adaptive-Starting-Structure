package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.structure.FootprintColumn;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class TheoreticalSpawnValidator {
    public static final int MAXIMUM_SAFE_FALL_DISTANCE =
            SpawnAreaValidator.MAXIMUM_SAFE_FALL_DISTANCE;

    // Collision-only proof state. M14-M15 still choose the actual local terrain material.
    private static final BlockState MODELED_SOLID_TERRAIN =
            Blocks.STONE.defaultBlockState();
    private static final BlockState MODELED_AIR =
            Blocks.AIR.defaultBlockState();

    private TheoreticalSpawnValidator() {
    }

    public static SpawnValidationResult validate(
            GameRules gameRules,
            RotatedStructureView structure,
            FineCandidatePlan plan,
            TheoreticalTerrainSource terrain) {
        Objects.requireNonNull(gameRules, "gameRules");
        return validate(
                gameRules.getInt(GameRules.RULE_SPAWN_RADIUS),
                structure,
                plan,
                terrain);
    }

    public static SpawnValidationResult validate(
            int configuredRadius,
            RotatedStructureView structure,
            FineCandidatePlan plan,
            TheoreticalTerrainSource terrain) {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(terrain, "terrain");

        BlockPos spawnFeet = plan.placementOrigin()
                .offset(structure.markers().spawn());
        return SpawnAreaValidator.validate(
                configuredRadius,
                spawnFeet,
                plan.structureBounds(),
                () -> createPaletteViews(
                        structure,
                        plan,
                        terrain,
                        spawnFeet));
    }

    private static List<? extends BlockGetter> createPaletteViews(
            RotatedStructureView structure,
            FineCandidatePlan plan,
            TheoreticalTerrainSource terrain,
            BlockPos spawnFeet) {
        Map<Long, TerrainColumn> terrainColumns =
                new HashMap<>();
        TerrainColumn referenceColumn = terrainColumn(
                terrain,
                terrainColumns,
                spawnFeet.getX(),
                spawnFeet.getZ());
        int minBuildHeight = referenceColumn.minY();
        int maxBuildHeight =
                referenceColumn.maxYExclusive();
        int paletteCount = paletteCount(structure);
        List<BlockGetter> views =
                new ArrayList<>(paletteCount);
        for (int paletteIndex = 0;
                paletteIndex < paletteCount;
                paletteIndex++) {
            views.add(new TheoreticalBlockView(
                    structure,
                    plan,
                    terrain,
                    terrainColumns,
                    paletteIndex,
                    minBuildHeight,
                    maxBuildHeight));
        }
        return List.copyOf(views);
    }

    private static int paletteCount(
            RotatedStructureView structure) {
        if (structure.placementElements().isEmpty()) {
            return 1;
        }
        return structure.placementElements()
                .getFirst()
                .paletteStates()
                .size();
    }

    private static TerrainColumn terrainColumn(
            TheoreticalTerrainSource terrain,
            Map<Long, TerrainColumn> terrainColumns,
            int x,
            int z) {
        long key = ((long) x << 32)
                ^ (z & 0xFFFFFFFFL);
        return terrainColumns.computeIfAbsent(
                key,
                ignored -> Objects.requireNonNull(
                        terrain.column(x, z),
                        "terrain column"));
    }

    private static final class TheoreticalBlockView
            implements BlockGetter {
        private final RotatedStructureView structure;
        private final FineCandidatePlan plan;
        private final TheoreticalTerrainSource terrain;
        private final Map<Long, TerrainColumn> terrainColumns;
        private final int paletteIndex;
        private final int minBuildHeight;
        private final int maxBuildHeight;

        private TheoreticalBlockView(
                RotatedStructureView structure,
                FineCandidatePlan plan,
                TheoreticalTerrainSource terrain,
                Map<Long, TerrainColumn> terrainColumns,
                int paletteIndex,
                int minBuildHeight,
                int maxBuildHeight) {
            this.structure = structure;
            this.plan = plan;
            this.terrain = terrain;
            this.terrainColumns = terrainColumns;
            this.paletteIndex = paletteIndex;
            this.minBuildHeight = minBuildHeight;
            this.maxBuildHeight = maxBuildHeight;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockPos relative =
                    pos.subtract(plan.placementOrigin());
            StructureElement element =
                    structure.elementAt(relative).orElse(null);
            if (element != null) {
                return element.kind()
                                == StructureElement.Kind.EXPLICIT_AIR
                        ? MODELED_AIR
                        : element.stateForPalette(paletteIndex);
            }
            if (structure.positionsToClear().contains(relative)) {
                return MODELED_AIR;
            }
            if (structure.footprint().contains(
                    new FootprintColumn(
                            relative.getX(),
                            relative.getZ()))) {
                return pos.getY() <= plan.groundSurfaceY()
                        ? MODELED_SOLID_TERRAIN
                        : MODELED_AIR;
            }
            TerrainColumn column =
                    terrainColumn(pos.getX(), pos.getZ());
            if (!column.containsY(pos.getY())) {
                return MODELED_AIR;
            }
            return column.blockState(pos.getY());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return maxBuildHeight - minBuildHeight;
        }

        @Override
        public int getMinBuildHeight() {
            return minBuildHeight;
        }

        private TerrainColumn terrainColumn(int x, int z) {
            TerrainColumn column =
                    TheoreticalSpawnValidator.terrainColumn(
                            terrain,
                            terrainColumns,
                            x,
                            z);
            if (column.minY() != minBuildHeight
                    || column.maxYExclusive()
                            != maxBuildHeight) {
                throw new IllegalArgumentException(
                        "Theoretical terrain columns must share one build-height range");
            }
            return column;
        }
    }
}
