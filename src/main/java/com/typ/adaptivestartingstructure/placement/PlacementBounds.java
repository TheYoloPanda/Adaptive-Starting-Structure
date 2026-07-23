package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.FootprintColumn;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

public record PlacementBounds(
        StructureBounds structureBounds,
        int minimumBlendX,
        int maximumBlendX,
        int minimumBlendZ,
        int maximumBlendZ) {

    public PlacementBounds {
        structureBounds =
                Objects.requireNonNull(structureBounds, "structureBounds");
        if (minimumBlendX > maximumBlendX
                || minimumBlendZ > maximumBlendZ) {
            throw new IllegalArgumentException(
                    "Placement blend bounds must be ordered");
        }
    }

    public static PlacementBounds calculate(
            RotatedStructureView structure,
            BlockPos placementOrigin,
            int blendWidth) {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(placementOrigin, "placementOrigin");
        if (blendWidth < 0) {
            throw new IllegalArgumentException(
                    "blendWidth must not be negative");
        }
        if (structure.footprint().isEmpty()) {
            throw new PlacementPreparationException(
                    "Selected structure has an empty terrain footprint");
        }

        StructureBounds structureBounds = new StructureBounds(
                offsetExact(
                        placementOrigin,
                        structure.bounds().minimum(),
                        "minimum structure bound"),
                offsetExact(
                        placementOrigin,
                        structure.bounds().maximum(),
                        "maximum structure bound"));
        int minimumLocalX = Integer.MAX_VALUE;
        int maximumLocalX = Integer.MIN_VALUE;
        int minimumLocalZ = Integer.MAX_VALUE;
        int maximumLocalZ = Integer.MIN_VALUE;
        for (FootprintColumn column : structure.footprint()) {
            minimumLocalX = Math.min(minimumLocalX, column.x());
            maximumLocalX = Math.max(maximumLocalX, column.x());
            minimumLocalZ = Math.min(minimumLocalZ, column.z());
            maximumLocalZ = Math.max(maximumLocalZ, column.z());
        }

        return new PlacementBounds(
                structureBounds,
                exactCoordinate(
                        (long) placementOrigin.getX()
                                + minimumLocalX
                                - blendWidth,
                        "minimum blend X"),
                exactCoordinate(
                        (long) placementOrigin.getX()
                                + maximumLocalX
                                + blendWidth,
                        "maximum blend X"),
                exactCoordinate(
                        (long) placementOrigin.getZ()
                                + minimumLocalZ
                                - blendWidth,
                        "minimum blend Z"),
                exactCoordinate(
                        (long) placementOrigin.getZ()
                                + maximumLocalZ
                                + blendWidth,
                        "maximum blend Z"));
    }

    public boolean containsHorizontal(int x, int z) {
        boolean insideStructure =
                x >= structureBounds.minimum().getX()
                        && x <= structureBounds.maximum().getX()
                        && z >= structureBounds.minimum().getZ()
                        && z <= structureBounds.maximum().getZ();
        boolean insideBlend =
                x >= minimumBlendX
                        && x <= maximumBlendX
                        && z >= minimumBlendZ
                        && z <= maximumBlendZ;
        return insideStructure || insideBlend;
    }

    public int minimumAffectedX() {
        return Math.min(
                structureBounds.minimum().getX(),
                minimumBlendX);
    }

    public int maximumAffectedX() {
        return Math.max(
                structureBounds.maximum().getX(),
                maximumBlendX);
    }

    public int minimumAffectedZ() {
        return Math.min(
                structureBounds.minimum().getZ(),
                minimumBlendZ);
    }

    public int maximumAffectedZ() {
        return Math.max(
                structureBounds.maximum().getZ(),
                maximumBlendZ);
    }

    public List<ChunkPos> requiredChunks() {
        Map<Long, ChunkPos> chunks = new LinkedHashMap<>();
        addChunks(
                chunks,
                structureBounds.minimum().getX(),
                structureBounds.maximum().getX(),
                structureBounds.minimum().getZ(),
                structureBounds.maximum().getZ());
        addChunks(
                chunks,
                minimumBlendX,
                maximumBlendX,
                minimumBlendZ,
                maximumBlendZ);
        return List.copyOf(chunks.values());
    }

    public long affectedColumnCount() {
        long structureArea = area(
                structureBounds.minimum().getX(),
                structureBounds.maximum().getX(),
                structureBounds.minimum().getZ(),
                structureBounds.maximum().getZ());
        long blendArea = area(
                minimumBlendX,
                maximumBlendX,
                minimumBlendZ,
                maximumBlendZ);
        int intersectionMinimumX = Math.max(
                structureBounds.minimum().getX(),
                minimumBlendX);
        int intersectionMaximumX = Math.min(
                structureBounds.maximum().getX(),
                maximumBlendX);
        int intersectionMinimumZ = Math.max(
                structureBounds.minimum().getZ(),
                minimumBlendZ);
        int intersectionMaximumZ = Math.min(
                structureBounds.maximum().getZ(),
                maximumBlendZ);
        long intersection = intersectionMinimumX <= intersectionMaximumX
                        && intersectionMinimumZ <= intersectionMaximumZ
                ? area(
                        intersectionMinimumX,
                        intersectionMaximumX,
                        intersectionMinimumZ,
                        intersectionMaximumZ)
                : 0L;
        return Math.addExact(
                structureArea,
                Math.subtractExact(blendArea, intersection));
    }

    private static void addChunks(
            Map<Long, ChunkPos> chunks,
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ) {
        int minimumChunkX = SectionPos.blockToSectionCoord(minimumX);
        int maximumChunkX = SectionPos.blockToSectionCoord(maximumX);
        int minimumChunkZ = SectionPos.blockToSectionCoord(minimumZ);
        int maximumChunkZ = SectionPos.blockToSectionCoord(maximumZ);
        for (int chunkX = minimumChunkX; ; chunkX++) {
            for (int chunkZ = minimumChunkZ; ; chunkZ++) {
                ChunkPos position = new ChunkPos(chunkX, chunkZ);
                chunks.putIfAbsent(position.toLong(), position);
                if (chunkZ == maximumChunkZ) {
                    break;
                }
            }
            if (chunkX == maximumChunkX) {
                break;
            }
        }
    }

    private static long area(
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ) {
        long width = (long) maximumX - minimumX + 1L;
        long depth = (long) maximumZ - minimumZ + 1L;
        return Math.multiplyExact(width, depth);
    }

    private static int exactCoordinate(long value, String name) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException exception) {
            throw new PlacementPreparationException(
                    "Calculated " + name + " exceeds world coordinates");
        }
    }

    private static BlockPos offsetExact(
            BlockPos origin,
            BlockPos offset,
            String name) {
        return new BlockPos(
                exactCoordinate(
                        (long) origin.getX() + offset.getX(),
                        name + " X"),
                exactCoordinate(
                        (long) origin.getY() + offset.getY(),
                        name + " Y"),
                exactCoordinate(
                        (long) origin.getZ() + offset.getZ(),
                        name + " Z"));
    }
}
