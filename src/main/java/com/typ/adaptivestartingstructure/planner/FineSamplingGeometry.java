package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.structure.FootprintColumn;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FineSamplingGeometry {
    private final List<SamplePoint> samplePoints;
    private final int footprintSampleCount;
    private final int perimeterSampleCount;
    private final int blendSampleCount;
    private final int minimumBlendX;
    private final int maximumBlendX;
    private final int minimumBlendZ;
    private final int maximumBlendZ;
    private final int minimumFootprintX;
    private final int maximumFootprintX;
    private final int minimumFootprintZ;
    private final int maximumFootprintZ;

    private FineSamplingGeometry(
            List<SamplePoint> samplePoints,
            int footprintSampleCount,
            int perimeterSampleCount,
            int blendSampleCount,
            int minimumBlendX,
            int maximumBlendX,
            int minimumBlendZ,
            int maximumBlendZ,
            int minimumFootprintX,
            int maximumFootprintX,
            int minimumFootprintZ,
            int maximumFootprintZ) {
        this.samplePoints = List.copyOf(samplePoints);
        this.footprintSampleCount = footprintSampleCount;
        this.perimeterSampleCount = perimeterSampleCount;
        this.blendSampleCount = blendSampleCount;
        this.minimumBlendX = minimumBlendX;
        this.maximumBlendX = maximumBlendX;
        this.minimumBlendZ = minimumBlendZ;
        this.maximumBlendZ = maximumBlendZ;
        this.minimumFootprintX = minimumFootprintX;
        this.maximumFootprintX = maximumFootprintX;
        this.minimumFootprintZ = minimumFootprintZ;
        this.maximumFootprintZ = maximumFootprintZ;
    }

    static FineSamplingGeometry create(
            CoarseCandidate candidate,
            ConfigSnapshot config) {
        RotatedStructureView structure = candidate.structure();
        if (structure.footprint().isEmpty()) {
            return null;
        }

        Set<Long> footprint = new HashSet<>(structure.footprint().size());
        int minimumLocalX = Integer.MAX_VALUE;
        int maximumLocalX = Integer.MIN_VALUE;
        int minimumLocalZ = Integer.MAX_VALUE;
        int maximumLocalZ = Integer.MIN_VALUE;
        for (FootprintColumn column : structure.footprint()) {
            footprint.add(pack(column.x(), column.z()));
            minimumLocalX = Math.min(minimumLocalX, column.x());
            maximumLocalX = Math.max(maximumLocalX, column.x());
            minimumLocalZ = Math.min(minimumLocalZ, column.z());
            maximumLocalZ = Math.max(maximumLocalZ, column.z());
        }

        long minimumFootprintX = (long) candidate.minimumX() + minimumLocalX;
        long maximumFootprintX = (long) candidate.minimumX() + maximumLocalX;
        long minimumFootprintZ = (long) candidate.minimumZ() + minimumLocalZ;
        long maximumFootprintZ = (long) candidate.minimumZ() + maximumLocalZ;
        long minimumBlendX = minimumFootprintX - config.blendWidth();
        long maximumBlendX = maximumFootprintX + config.blendWidth();
        long minimumBlendZ = minimumFootprintZ - config.blendWidth();
        long maximumBlendZ = maximumFootprintZ + config.blendWidth();
        if (!fitsInt(minimumBlendX)
                || !fitsInt(maximumBlendX)
                || !fitsInt(minimumBlendZ)
                || !fitsInt(maximumBlendZ)) {
            return null;
        }

        Map<Long, MutableSamplePoint> samples = new HashMap<>();
        List<FootprintColumn> orderedFootprint = structure.footprint().stream()
                .sorted(Comparator.comparingInt(FootprintColumn::x)
                        .thenComparingInt(FootprintColumn::z))
                .toList();
        for (FootprintColumn column : orderedFootprint) {
            boolean xAnchor = isSamplingAnchor(
                    column.x(),
                    minimumLocalX,
                    maximumLocalX,
                    config.fineSampleStep());
            boolean zAnchor = isSamplingAnchor(
                    column.z(),
                    minimumLocalZ,
                    maximumLocalZ,
                    config.fineSampleStep());
            boolean north = !footprint.contains(pack(column.x(), column.z() - 1));
            boolean south = !footprint.contains(pack(column.x(), column.z() + 1));
            boolean west = !footprint.contains(pack(column.x() - 1, column.z()));
            boolean east = !footprint.contains(pack(column.x() + 1, column.z()));
            boolean perimeter = ((north || south) && xAnchor)
                    || ((west || east) && zAnchor);
            if (xAnchor && zAnchor) {
                addSample(samples, candidate, column.x(), column.z(), true, perimeter, false);
            } else if (perimeter) {
                addSample(samples, candidate, column.x(), column.z(), true, true, false);
            }
        }
        if (samples.values().stream().noneMatch(point -> point.footprint)) {
            FootprintColumn first = orderedFootprint.getFirst();
            addSample(samples, candidate, first.x(), first.z(), true, false, false);
        }
        if (samples.values().stream().noneMatch(point -> point.perimeter)) {
            for (FootprintColumn column : orderedFootprint) {
                if (isBoundary(footprint, column.x(), column.z())) {
                    addSample(samples, candidate, column.x(), column.z(), true, true, false);
                    break;
                }
            }
        }

        List<MutableSamplePoint> perimeterPoints = samples.values().stream()
                .filter(point -> point.perimeter)
                .toList();
        int diagonalOffset =
                (int) Math.floor(config.blendWidth() / Math.sqrt(2.0D));
        for (MutableSamplePoint point : perimeterPoints) {
            int localX = point.x - candidate.minimumX();
            int localZ = point.z - candidate.minimumZ();
            boolean north = !footprint.contains(pack(localX, localZ - 1));
            boolean south = !footprint.contains(pack(localX, localZ + 1));
            boolean west = !footprint.contains(pack(localX - 1, localZ));
            boolean east = !footprint.contains(pack(localX + 1, localZ));
            if (north) {
                addBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        0,
                        -config.blendWidth());
            }
            if (south) {
                addBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        0,
                        config.blendWidth());
            }
            if (west) {
                addBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        -config.blendWidth(),
                        0);
            }
            if (east) {
                addBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        config.blendWidth(),
                        0);
            }
            if (diagonalOffset > 0) {
                addDiagonalBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        north,
                        west,
                        -diagonalOffset,
                        -diagonalOffset);
                addDiagonalBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        north,
                        east,
                        diagonalOffset,
                        -diagonalOffset);
                addDiagonalBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        south,
                        west,
                        -diagonalOffset,
                        diagonalOffset);
                addDiagonalBlendSample(
                        samples,
                        footprint,
                        candidate,
                        localX,
                        localZ,
                        south,
                        east,
                        diagonalOffset,
                        diagonalOffset);
            }
        }

        List<SamplePoint> samplePoints = samples.values().stream()
                .map(MutableSamplePoint::freeze)
                .sorted(Comparator.comparingInt(SamplePoint::x)
                        .thenComparingInt(SamplePoint::z))
                .toList();
        int footprintCount =
                (int) samplePoints.stream().filter(SamplePoint::footprint).count();
        int perimeterCount =
                (int) samplePoints.stream().filter(SamplePoint::perimeter).count();
        int blendCount =
                (int) samplePoints.stream().filter(SamplePoint::blend).count();
        return new FineSamplingGeometry(
                samplePoints,
                footprintCount,
                perimeterCount,
                blendCount,
                (int) minimumBlendX,
                (int) maximumBlendX,
                (int) minimumBlendZ,
                (int) maximumBlendZ,
                (int) minimumFootprintX,
                (int) maximumFootprintX,
                (int) minimumFootprintZ,
                (int) maximumFootprintZ);
    }

    boolean isInsideWorldBorder(
            CoarseCandidate candidate,
            PlannerQueryContext queries) {
        for (SamplePoint point : samplePoints) {
            if (!queries.isWithinWorldBorder(point.x, point.z)) {
                return false;
            }
        }
        if (!queries.isWithinWorldBorder(candidate.minimumX(), candidate.minimumZ())
                || !queries.isWithinWorldBorder(candidate.maximumX(), candidate.minimumZ())
                || !queries.isWithinWorldBorder(candidate.minimumX(), candidate.maximumZ())
                || !queries.isWithinWorldBorder(candidate.maximumX(), candidate.maximumZ())) {
            return false;
        }
        int anchorX = minimumFootprintX
                + (maximumFootprintX - minimumFootprintX) / 2;
        int anchorZ = minimumFootprintZ
                + (maximumFootprintZ - minimumFootprintZ) / 2;
        return queries.isWithinWorldBorder(minimumBlendX, anchorZ)
                && queries.isWithinWorldBorder(maximumBlendX, anchorZ)
                && queries.isWithinWorldBorder(anchorX, minimumBlendZ)
                && queries.isWithinWorldBorder(anchorX, maximumBlendZ);
    }

    List<SamplePoint> samplePoints() {
        return samplePoints;
    }

    int footprintSampleCount() {
        return footprintSampleCount;
    }

    int perimeterSampleCount() {
        return perimeterSampleCount;
    }

    int blendSampleCount() {
        return blendSampleCount;
    }

    int minimumBlendX() {
        return minimumBlendX;
    }

    int maximumBlendX() {
        return maximumBlendX;
    }

    int minimumBlendZ() {
        return minimumBlendZ;
    }

    int maximumBlendZ() {
        return maximumBlendZ;
    }

    private static void addBlendSample(
            Map<Long, MutableSamplePoint> samples,
            Set<Long> footprint,
            CoarseCandidate candidate,
            int localX,
            int localZ,
            int offsetX,
            int offsetZ) {
        int targetX = localX + offsetX;
        int targetZ = localZ + offsetZ;
        if (!footprint.contains(pack(targetX, targetZ))) {
            addSample(samples, candidate, targetX, targetZ, false, false, true);
        }
    }

    private static void addDiagonalBlendSample(
            Map<Long, MutableSamplePoint> samples,
            Set<Long> footprint,
            CoarseCandidate candidate,
            int localX,
            int localZ,
            boolean firstSide,
            boolean secondSide,
            int offsetX,
            int offsetZ) {
        if (firstSide && secondSide) {
            addBlendSample(
                    samples,
                    footprint,
                    candidate,
                    localX,
                    localZ,
                    offsetX,
                    offsetZ);
        }
    }

    private static void addSample(
            Map<Long, MutableSamplePoint> samples,
            CoarseCandidate candidate,
            int localX,
            int localZ,
            boolean footprint,
            boolean perimeter,
            boolean blend) {
        long absoluteX = (long) candidate.minimumX() + localX;
        long absoluteZ = (long) candidate.minimumZ() + localZ;
        if (!fitsInt(absoluteX) || !fitsInt(absoluteZ)) {
            return;
        }
        long key = pack((int) absoluteX, (int) absoluteZ);
        MutableSamplePoint point = samples.computeIfAbsent(
                key,
                ignored -> new MutableSamplePoint((int) absoluteX, (int) absoluteZ));
        point.footprint |= footprint;
        point.perimeter |= perimeter;
        point.blend |= blend;
    }

    private static boolean isBoundary(Set<Long> footprint, int x, int z) {
        return !footprint.contains(pack(x - 1, z))
                || !footprint.contains(pack(x + 1, z))
                || !footprint.contains(pack(x, z - 1))
                || !footprint.contains(pack(x, z + 1));
    }

    private static boolean isSamplingAnchor(
            int coordinate,
            int minimum,
            int maximum,
            int step) {
        return coordinate == minimum
                || coordinate == maximum
                || Math.floorMod(coordinate - minimum, step) == 0;
    }

    private static boolean fitsInt(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    record SamplePoint(
            int x,
            int z,
            boolean footprint,
            boolean perimeter,
            boolean blend) {
    }

    private static final class MutableSamplePoint {
        private final int x;
        private final int z;
        private boolean footprint;
        private boolean perimeter;
        private boolean blend;

        private MutableSamplePoint(int x, int z) {
            this.x = x;
            this.z = z;
        }

        private SamplePoint freeze() {
            return new SamplePoint(x, z, footprint, perimeter, blend);
        }
    }
}
