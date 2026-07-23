package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.FootprintColumn;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class FootprintDistanceIndex {
    private final Set<Long> footprint;
    private final Node root;

    private FootprintDistanceIndex(
            Set<Long> footprint,
            Node root) {
        this.footprint = Set.copyOf(footprint);
        this.root = root;
    }

    static FootprintDistanceIndex create(
            RotatedStructureView structure,
            BlockPos placementOrigin) {
        List<Point> points =
                new ArrayList<>(structure.footprint().size());
        Set<Long> footprint =
                new HashSet<>(structure.footprint().size());
        for (FootprintColumn relative : structure.footprint()) {
            int x = addExact(
                    placementOrigin.getX(),
                    relative.x());
            int z = addExact(
                    placementOrigin.getZ(),
                    relative.z());
            points.add(new Point(x, z));
            footprint.add(TerrainSnapshot.pack(x, z));
        }
        if (points.isEmpty()) {
            throw new PlacementPreparationException(
                    "Cannot index an empty structure footprint");
        }
        return new FootprintDistanceIndex(
                footprint,
                build(points, 0));
    }

    boolean contains(int x, int z) {
        return footprint.contains(TerrainSnapshot.pack(x, z));
    }

    double distance(int x, int z) {
        if (contains(x, z)) {
            return 0.0D;
        }
        return Math.sqrt(nearestSquared(root, x, z, Double.POSITIVE_INFINITY));
    }

    private static Node build(
            List<Point> points,
            int depth) {
        if (points.isEmpty()) {
            return null;
        }
        int axis = depth & 1;
        points.sort(axis == 0
                ? Comparator.comparingInt(Point::x)
                        .thenComparingInt(Point::z)
                : Comparator.comparingInt(Point::z)
                        .thenComparingInt(Point::x));
        int middle = points.size() / 2;
        Point point = points.get(middle);
        return new Node(
                point,
                axis,
                build(
                        new ArrayList<>(
                                points.subList(0, middle)),
                        depth + 1),
                build(
                        new ArrayList<>(
                                points.subList(
                                        middle + 1,
                                        points.size())),
                        depth + 1));
    }

    private static double nearestSquared(
            Node node,
            int x,
            int z,
            double best) {
        if (node == null) {
            return best;
        }
        double deltaX = (double) x - node.point.x;
        double deltaZ = (double) z - node.point.z;
        double candidate = deltaX * deltaX + deltaZ * deltaZ;
        double nearest = Math.min(best, candidate);
        double axisDelta =
                node.axis == 0 ? deltaX : deltaZ;
        Node near = axisDelta < 0.0D
                ? node.lower
                : node.upper;
        Node far = axisDelta < 0.0D
                ? node.upper
                : node.lower;
        nearest = nearestSquared(
                near,
                x,
                z,
                nearest);
        if (axisDelta * axisDelta <= nearest) {
            nearest = nearestSquared(
                    far,
                    x,
                    z,
                    nearest);
        }
        return nearest;
    }

    private static int addExact(int first, int second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new PlacementPreparationException(
                    "Footprint coordinate exceeds world bounds");
        }
    }

    private record Point(int x, int z) {
    }

    private record Node(
            Point point,
            int axis,
            Node lower,
            Node upper) {
    }
}
