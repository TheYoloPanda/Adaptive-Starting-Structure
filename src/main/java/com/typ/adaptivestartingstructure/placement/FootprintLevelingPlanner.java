package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.FootprintColumn;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class FootprintLevelingPlanner {
    private static final Comparator<FootprintColumn> COLUMN_ORDER =
            Comparator.comparingInt(FootprintColumn::x)
                    .thenComparingInt(FootprintColumn::z);
    private static final Comparator<ChunkPos> CHUNK_ORDER =
            Comparator.comparingInt((ChunkPos chunk) -> chunk.x)
                    .thenComparingInt(chunk -> chunk.z);
    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt(
                            (BlockPos position) -> position.getX())
                    .thenComparingInt(BlockPos::getZ)
                    .thenComparingInt(BlockPos::getY);

    private FootprintLevelingPlanner() {
    }

    static TerrainTransformationPlan plan(
            TerrainSnapshot snapshot,
            RotatedStructureView structure,
            BlockPos placementOrigin,
            ConfigSnapshot config) {
        List<FootprintColumn> footprint = structure.footprint().stream()
                .sorted(COLUMN_ORDER)
                .toList();
        if (footprint.isEmpty()) {
            throw new PlacementPreparationException(
                    "Cannot level an empty structure footprint");
        }

        List<TerrainWrite> writes = new ArrayList<>();
        Set<Long> absoluteFootprint = new LinkedHashSet<>();
        for (FootprintColumn relative : footprint) {
            int x = addExact(
                    placementOrigin.getX(),
                    relative.x(),
                    "footprint X");
            int z = addExact(
                    placementOrigin.getZ(),
                    relative.z(),
                    "footprint Z");
            absoluteFootprint.add(TerrainSnapshot.pack(x, z));
            TerrainColumnSnapshot column = snapshot.column(x, z);
            int targetY = snapshot.targetGroundY();
            int signedError = column.groundY() - targetY;
            if (signedError > config.maximumCutDepth()
                    || -signedError > config.maximumFillDepth()) {
                throw UnsuitableGeneratedSiteException
                        .cutFillThresholdExceeded(
                                "Generated footprint",
                                column,
                                targetY,
                                config.maximumCutDepth(),
                                config.maximumFillDepth());
            }
            if (signedError > 0) {
                planCut(writes, column, targetY);
            } else if (signedError < 0) {
                planFill(writes, column, targetY);
            }
        }

        Map<ChunkPos, List<TerrainWrite>> grouped =
                new TreeMap<>(CHUNK_ORDER);
        for (TerrainWrite write : writes) {
            grouped.computeIfAbsent(
                            new ChunkPos(write.position()),
                            ignored -> new ArrayList<>())
                    .add(write);
        }
        Map<ChunkPos, List<TerrainWrite>> ordered =
                new LinkedHashMap<>();
        for (Map.Entry<ChunkPos, List<TerrainWrite>> entry
                : grouped.entrySet()) {
            entry.getValue().sort(
                    Comparator.comparing(
                                    (TerrainWrite write) ->
                                            write.position(),
                                    POSITION_ORDER)
                            .thenComparing(write ->
                                    write.kind().ordinal()));
            ordered.put(entry.getKey(), entry.getValue());
        }

        Set<BlockPos> structuralAir = structure
                .explicitAirPositions()
                .stream()
                .map(relative -> offsetExact(
                        placementOrigin,
                        relative))
                .sorted(POSITION_ORDER)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll);
        return new TerrainTransformationPlan(
                snapshot,
                absoluteFootprint,
                ordered,
                structuralAir);
    }

    private static void planCut(
            List<TerrainWrite> writes,
            TerrainColumnSnapshot column,
            int targetY) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = column.groundY(); y > targetY; y--) {
            addWrite(
                    writes,
                    column,
                    y,
                    air,
                    TerrainWrite.Kind.FOOTPRINT_CUT);
        }
        addWrite(
                writes,
                column,
                targetY,
                column.surfaceMaterial(),
                TerrainWrite.Kind.FOOTPRINT_RESURFACE);
    }

    private static void planFill(
            List<TerrainWrite> writes,
            TerrainColumnSnapshot column,
            int targetY) {
        for (int y = column.groundY() + 1;
                y <= targetY;
                y++) {
            BlockState target = y == targetY
                    ? column.surfaceMaterial()
                    : column.fillerMaterial();
            addWrite(
                    writes,
                    column,
                    y,
                    target,
                    TerrainWrite.Kind.FOOTPRINT_FILL);
        }
    }

    private static void addWrite(
            List<TerrainWrite> writes,
            TerrainColumnSnapshot column,
            int y,
            BlockState target,
            TerrainWrite.Kind kind) {
        BlockState original = column.stateAt(y);
        if (!original.equals(target)) {
            writes.add(new TerrainWrite(
                    new BlockPos(column.x(), y, column.z()),
                    original,
                    target,
                    kind));
        }
    }

    private static BlockPos offsetExact(
            BlockPos origin,
            BlockPos relative) {
        return new BlockPos(
                addExact(
                        origin.getX(),
                        relative.getX(),
                        "structural air X"),
                addExact(
                        origin.getY(),
                        relative.getY(),
                        "structural air Y"),
                addExact(
                        origin.getZ(),
                        relative.getZ(),
                        "structural air Z"));
    }

    private static int addExact(int first, int second, String name) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new PlacementPreparationException(
                    "Calculated " + name + " exceeds world coordinates");
        }
    }
}
