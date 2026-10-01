package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/*
 * One unit on purpose: finding the trees a placement touches, deciding which
 * of them may go and what goes with them are steps of a single rule over the
 * same private indexes of logs, leaves and their owners. Split apart, every
 * piece would need those indexes exported to do one step of that rule.
 */
final class TreeCleanupPlanner {
    private static final int MAX_LEAF_DISTANCE = 7;
    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt(
                            (BlockPos position) -> position.getX())
                    .thenComparingInt(BlockPos::getZ)
                    .thenComparingInt(BlockPos::getY);
    private static final List<BlockPos> LOG_NEIGHBOR_OFFSETS =
            createLogNeighborOffsets();

    private TreeCleanupPlanner() {
    }

    static Result plan(
            PreparedTerrainLeveling leveling,
            List<BlendColumnTarget> blendTargets,
            RotatedStructureView structure,
            BlockPos placementOrigin,
            Set<BlockPos> occupiedWritePositions) {
        Objects.requireNonNull(leveling, "leveling");
        Objects.requireNonNull(blendTargets, "blendTargets");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(placementOrigin, "placementOrigin");
        Objects.requireNonNull(
                occupiedWritePositions,
                "occupiedWritePositions");

        TerrainSnapshot snapshot = leveling.snapshot();
        Inventory inventory = inventory(snapshot);
        if (inventory.logs.isEmpty() && inventory.leaves.isEmpty()) {
            return Result.empty();
        }

        LogIndex logIndex = indexLogs(snapshot, inventory.logs.keySet());
        Map<BlockPos, LeafOwnership> leafOwnership =
                assignLeaves(
                        inventory.leaves.keySet(),
                        logIndex.ownerByLog,
                        inventory.states);
        List<Set<Integer>> supports = supportingComponents(
                logIndex,
                inventory.leaves.keySet());
        boolean[] grounded = grounded(logIndex, supports);

        Map<Long, BlendColumnTarget> targetsByColumn =
                new HashMap<>(blendTargets.size());
        for (BlendColumnTarget target : blendTargets) {
            targetsByColumn.put(
                    TerrainSnapshot.pack(target.x(), target.z()),
                    target);
        }
        Set<BlockPos> templatePositions = templatePositions(
                structure,
                placementOrigin);
        TreeSet<Integer> selectedOwners = new TreeSet<>();

        for (BlockPos log : sorted(inventory.logs.keySet())) {
            if (!interferes(
                    log,
                    leveling.plan(),
                    targetsByColumn,
                    templatePositions)) {
                continue;
            }
            int owner = logIndex.ownerByLog.get(log);
            requireGrounded(grounded[owner], log, "log");
            selectedOwners.add(owner);
        }
        Set<BlockPos> trunklessContacts = new LinkedHashSet<>();
        for (BlockPos leaf : sorted(inventory.leaves.keySet())) {
            if (!interferes(
                    leaf,
                    leveling.plan(),
                    targetsByColumn,
                    templatePositions)) {
                continue;
            }
            LeafOwnership ownership = leafOwnership.get(leaf);
            if (ownership == null || ownership.owners.isEmpty()) {
                trunklessContacts.add(leaf);
                continue;
            }
            for (int owner : ownership.owners) {
                requireGrounded(grounded[owner], leaf, "foliage");
                selectedOwners.add(owner);
            }
        }
        Set<BlockPos> trunklessFoliage = trunklessFoliage(
                trunklessContacts,
                inventory.leaves.keySet(),
                leafOwnership);
        if (selectedOwners.isEmpty() && trunklessFoliage.isEmpty()) {
            return Result.empty();
        }
        addRiders(selectedOwners, supports);

        Set<BlockPos> selectedLogs = new LinkedHashSet<>();
        for (int owner : selectedOwners) {
            selectedLogs.addAll(logIndex.components.get(owner).logs);
        }
        Set<BlockPos> selectedLeaves = new LinkedHashSet<>(trunklessFoliage);
        for (Map.Entry<BlockPos, LeafOwnership> entry
                : leafOwnership.entrySet()) {
            if (selectedOwners.containsAll(entry.getValue().owners)) {
                selectedLeaves.add(entry.getKey());
            }
        }

        requireComplete(snapshot.bounds(), selectedLogs, "logs");
        requireComplete(snapshot.bounds(), selectedLeaves, "foliage");

        Set<BlockPos> selectedTree = new HashSet<>(selectedLogs);
        selectedTree.addAll(selectedLeaves);
        Set<BlockPos> preservedTree = new HashSet<>(inventory.logs.keySet());
        preservedTree.addAll(inventory.leaves.keySet());
        preservedTree.removeAll(selectedTree);
        Set<BlockPos> accessories = selectAccessories(
                snapshot.bounds(),
                inventory,
                selectedTree,
                preservedTree);

        Set<BlockPos> cleanupPositions = new LinkedHashSet<>();
        cleanupPositions.addAll(sorted(selectedLogs));
        cleanupPositions.addAll(sorted(selectedLeaves));
        cleanupPositions.addAll(sorted(accessories));

        List<TerrainWrite> writes = new ArrayList<>();
        for (BlockPos position : cleanupPositions) {
            if (occupiedWritePositions.contains(position)
                    || templatePositions.contains(position)) {
                continue;
            }
            BlockState original = inventory.states.get(position);
            BlockState target = original.getFluidState().isEmpty()
                    ? Blocks.AIR.defaultBlockState()
                    : original.getFluidState().createLegacyBlock();
            if (!original.equals(target)) {
                writes.add(new TerrainWrite(
                        position,
                        original,
                        target,
                        TerrainWrite.Kind.VEGETATION_CLEAR));
            }
        }
        writes.sort(Comparator.comparing(
                TerrainWrite::position,
                POSITION_ORDER));
        return new Result(
                selectedOwners.size(),
                cleanupPositions.size(),
                accessories.size(),
                writes);
    }

    private static Inventory inventory(TerrainSnapshot snapshot) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        Map<BlockPos, BlockState> logs = new LinkedHashMap<>();
        Map<BlockPos, BlockState> leaves = new LinkedHashMap<>();
        Set<BlockPos> vines = new LinkedHashSet<>();
        Set<BlockPos> cocoa = new LinkedHashSet<>();
        Set<BlockPos> beeNests = new LinkedHashSet<>();
        for (TerrainColumnSnapshot column : snapshot.columns()) {
            for (int y = column.groundY() + 1;
                    y < column.maximumCapturedYExclusive();
                    y++) {
                BlockState state = column.stateAt(y);
                BlockPos position = new BlockPos(
                        column.x(),
                        y,
                        column.z());
                if (TerrainSurfaceClassifier.isTreeLog(state)) {
                    logs.put(position, state);
                } else if (TerrainSurfaceClassifier.isTreeLeaf(state)
                        || isGiantMushroomPart(state)) {
                    leaves.put(position, state);
                } else if (state.getBlock() instanceof VineBlock) {
                    vines.add(position);
                } else if (state.getBlock() instanceof CocoaBlock) {
                    cocoa.add(position);
                } else if (state.getBlock() instanceof BeehiveBlock) {
                    beeNests.add(position);
                } else {
                    continue;
                }
                states.put(position, state);
            }
        }
        return new Inventory(
                states,
                logs,
                leaves,
                vines,
                cocoa,
                beeNests);
    }

    private static LogIndex indexLogs(
            TerrainSnapshot snapshot,
            Set<BlockPos> logs) {
        Map<BlockPos, Integer> ownerByLog = new HashMap<>();
        List<LogComponent> components = new ArrayList<>();
        for (BlockPos start : sorted(logs)) {
            if (ownerByLog.containsKey(start)) {
                continue;
            }
            int id = components.size();
            Queue<BlockPos> pending = new ArrayDeque<>();
            List<BlockPos> componentLogs = new ArrayList<>();
            ownerByLog.put(start, id);
            pending.add(start);
            boolean rooted = false;
            while (!pending.isEmpty()) {
                BlockPos current = pending.remove();
                componentLogs.add(current);
                TerrainColumnSnapshot column = snapshot.column(
                        current.getX(),
                        current.getZ());
                if (current.getY() == column.groundY() + 1) {
                    rooted = true;
                }
                for (BlockPos offset : LOG_NEIGHBOR_OFFSETS) {
                    BlockPos neighbor = current.offset(offset);
                    if (logs.contains(neighbor)
                            && ownerByLog.putIfAbsent(neighbor, id) == null) {
                        pending.add(neighbor);
                    }
                }
            }
            componentLogs.sort(POSITION_ORDER);
            components.add(new LogComponent(
                    List.copyOf(componentLogs),
                    rooted));
        }
        return new LogIndex(
                Map.copyOf(ownerByLog),
                List.copyOf(components));
    }

    /*
     * A giant mushroom's cap is the foliage of its stem, which already counts
     * as a trunk, and goes with it. Caps only belong to stems and leaves only
     * to trunks: otherwise removing an oak would take the cap blocks it
     * happens to touch, and removing a mushroom the leaves next to its stem.
     */
    private static Map<BlockPos, LeafOwnership> assignLeaves(
            Set<BlockPos> leaves,
            Map<BlockPos, Integer> ownerByLog,
            Map<BlockPos, BlockState> states) {
        Map<BlockPos, LeafOwnership> ownership = new HashMap<>();
        Queue<LeafVisit> pending = new ArrayDeque<>();
        for (BlockPos leaf : sorted(leaves)) {
            for (Direction direction : Direction.values()) {
                BlockPos log = leaf.relative(direction);
                Integer owner = ownerByLog.get(log);
                if (owner != null
                        && sameFoliageFamily(
                                states.get(leaf),
                                states.get(log))) {
                    updateOwnership(
                            ownership,
                            pending,
                            leaf,
                            owner,
                            1);
                }
            }
        }
        while (!pending.isEmpty()) {
            LeafVisit visit = pending.remove();
            LeafOwnership current = ownership.get(visit.position);
            if (current == null
                    || current.distance != visit.distance
                    || !current.owners.contains(visit.owner)
                    || visit.distance >= MAX_LEAF_DISTANCE) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = visit.position.relative(direction);
                if (leaves.contains(neighbor)
                        && sameFoliageFamily(
                                states.get(visit.position),
                                states.get(neighbor))) {
                    updateOwnership(
                            ownership,
                            pending,
                            neighbor,
                            visit.owner,
                            visit.distance + 1);
                }
            }
        }
        return ownership;
    }

    private static boolean sameFoliageFamily(
            BlockState first,
            BlockState second) {
        return isGiantMushroomPart(first) == isGiantMushroomPart(second);
    }

    private static boolean isGiantMushroomPart(BlockState state) {
        return state.getBlock() instanceof HugeMushroomBlock;
    }

    private static void updateOwnership(
            Map<BlockPos, LeafOwnership> ownership,
            Queue<LeafVisit> pending,
            BlockPos position,
            int owner,
            int distance) {
        LeafOwnership current = ownership.get(position);
        if (current == null || distance < current.distance) {
            TreeSet<Integer> owners = new TreeSet<>();
            owners.add(owner);
            ownership.put(
                    position,
                    new LeafOwnership(distance, owners));
            pending.add(new LeafVisit(position, owner, distance));
        } else if (distance == current.distance
                && current.owners.add(owner)) {
            pending.add(new LeafVisit(position, owner, distance));
        }
    }

    private static Set<BlockPos> templatePositions(
            RotatedStructureView structure,
            BlockPos origin) {
        Set<BlockPos> positions = new HashSet<>();
        structure.placementElements().forEach(element ->
                positions.add(offsetExact(origin, element.position())));
        structure.positionsToClear().forEach(position ->
                positions.add(offsetExact(origin, position)));
        return Set.copyOf(positions);
    }

    private static boolean interferes(
            BlockPos position,
            TerrainTransformationPlan leveling,
            Map<Long, BlendColumnTarget> targetsByColumn,
            Set<BlockPos> templatePositions) {
        if (leveling.isFootprintColumn(
                position.getX(),
                position.getZ())
                || templatePositions.contains(position)) {
            return true;
        }
        BlendColumnTarget target = targetsByColumn.get(
                TerrainSnapshot.pack(
                        position.getX(),
                        position.getZ()));
        return target != null && target.modifiesHeight();
    }

    /*
     * For every log component that does not stand on the ground, the other
     * components it rests on: those whose log is right under one of its logs,
     * or whose logs the leaf under it reaches through leaves. World generation
     * can drop a bush onto the canopy of another tree, where none of its logs
     * touches the ground.
     */
    private static List<Set<Integer>> supportingComponents(
            LogIndex logIndex,
            Set<BlockPos> leaves) {
        List<Set<Integer>> supports =
                new ArrayList<>(logIndex.components.size());
        for (int id = 0; id < logIndex.components.size(); id++) {
            LogComponent component = logIndex.components.get(id);
            Set<Integer> supporting = new TreeSet<>();
            if (!component.rooted) {
                for (BlockPos log : component.logs) {
                    addSupport(
                            log.below(),
                            id,
                            logIndex.ownerByLog,
                            leaves,
                            supporting);
                }
            }
            supports.add(Set.copyOf(supporting));
        }
        return List.copyOf(supports);
    }

    private static void addSupport(
            BlockPos below,
            int component,
            Map<BlockPos, Integer> ownerByLog,
            Set<BlockPos> leaves,
            Set<Integer> supporting) {
        Integer owner = ownerByLog.get(below);
        if (owner != null) {
            if (owner != component) {
                supporting.add(owner);
            }
            return;
        }
        if (!leaves.contains(below)) {
            return;
        }
        Map<BlockPos, Integer> distances = new HashMap<>();
        Queue<BlockPos> pending = new ArrayDeque<>();
        distances.put(below, 1);
        pending.add(below);
        while (!pending.isEmpty()) {
            BlockPos leaf = pending.remove();
            int distance = distances.get(leaf);
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = leaf.relative(direction);
                Integer neighborOwner = ownerByLog.get(neighbor);
                if (neighborOwner != null) {
                    if (neighborOwner != component) {
                        supporting.add(neighborOwner);
                    }
                } else if (distance < MAX_LEAF_DISTANCE
                        && leaves.contains(neighbor)
                        && distances.putIfAbsent(
                                neighbor,
                                distance + 1) == null) {
                    pending.add(neighbor);
                }
            }
        }
    }

    /*
     * Wood is taken when it stands on the ground or rests on wood that does.
     * Leaves are not required: stumps and fallen logs have none. What neither
     * stands nor rests on a tree could be part of something built, so it
     * rejects the site; wood inside a generated structure's pieces is rejected
     * later by the collision check whatever this decides.
     */
    private static boolean[] grounded(
            LogIndex logIndex,
            List<Set<Integer>> supports) {
        boolean[] grounded = new boolean[logIndex.components.size()];
        for (int id = 0; id < grounded.length; id++) {
            grounded[id] = logIndex.components.get(id).rooted;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int id = 0; id < grounded.length; id++) {
                if (!grounded[id]
                        && supports.get(id).stream()
                                .anyMatch(support -> grounded[support])) {
                    grounded[id] = true;
                    changed = true;
                }
            }
        }
        return grounded;
    }

    private static void requireGrounded(
            boolean grounded,
            BlockPos contact,
            String kind) {
        if (!grounded) {
            throw unresolved(
                    contact,
                    "contacting " + kind
                            + " belongs to wood that neither stands on the ground"
                            + " nor rests on a tree");
        }
    }

    /*
     * Leaves that no log reaches within the leaf distance belong to no tree,
     * so taking them cannot cut one in half. They go as the whole cluster of
     * such leaves around the contact, so that none is left floating.
     */
    private static Set<BlockPos> trunklessFoliage(
            Set<BlockPos> contacts,
            Set<BlockPos> leaves,
            Map<BlockPos, LeafOwnership> leafOwnership) {
        Set<BlockPos> cluster = new LinkedHashSet<>(contacts);
        Queue<BlockPos> pending = new ArrayDeque<>(contacts);
        while (!pending.isEmpty()) {
            BlockPos leaf = pending.remove();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = leaf.relative(direction);
                if (leaves.contains(neighbor)
                        && !leafOwnership.containsKey(neighbor)
                        && cluster.add(neighbor)) {
                    pending.add(neighbor);
                }
            }
        }
        return cluster;
    }

    /*
     * A component resting only on trees that are being removed would float
     * once they are gone, so it goes with them.
     */
    private static void addRiders(
            Set<Integer> selectedOwners,
            List<Set<Integer>> supports) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int id = 0; id < supports.size(); id++) {
                Set<Integer> supporting = supports.get(id);
                if (!supporting.isEmpty()
                        && !selectedOwners.contains(id)
                        && selectedOwners.containsAll(supporting)) {
                    selectedOwners.add(id);
                    changed = true;
                }
            }
        }
    }

    private static void requireComplete(
            PlacementBounds bounds,
            Set<BlockPos> positions,
            String kind) {
        for (BlockPos position : positions) {
            if (touchesHorizontalBoundary(bounds, position)) {
                throw unresolved(
                        position,
                        "selected tree " + kind
                                + " reaches the prepared-area boundary");
            }
        }
    }

    private static Set<BlockPos> selectAccessories(
            PlacementBounds bounds,
            Inventory inventory,
            Set<BlockPos> selectedTree,
            Set<BlockPos> preservedTree) {
        Set<BlockPos> selected = new LinkedHashSet<>();
        selectDirectAccessories(
                inventory.cocoa,
                selectedTree,
                preservedTree,
                selected);
        selectDirectAccessories(
                inventory.beeNests,
                selectedTree,
                preservedTree,
                selected);

        Set<BlockPos> visitedVines = new HashSet<>();
        for (BlockPos start : sorted(inventory.vines)) {
            if (!visitedVines.add(start)) {
                continue;
            }
            Queue<BlockPos> pending = new ArrayDeque<>();
            List<BlockPos> component = new ArrayList<>();
            pending.add(start);
            boolean touchesSelected = false;
            boolean touchesPreserved = false;
            while (!pending.isEmpty()) {
                BlockPos current = pending.remove();
                component.add(current);
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = current.relative(direction);
                    touchesSelected |= selectedTree.contains(neighbor);
                    touchesPreserved |= preservedTree.contains(neighbor);
                    if (inventory.vines.contains(neighbor)
                            && visitedVines.add(neighbor)) {
                        pending.add(neighbor);
                    }
                }
            }
            if (touchesSelected && !touchesPreserved) {
                component.sort(POSITION_ORDER);
                selected.addAll(component);
            }
        }
        requireComplete(bounds, selected, "attachments");
        return selected;
    }

    private static void selectDirectAccessories(
            Set<BlockPos> candidates,
            Set<BlockPos> selectedTree,
            Set<BlockPos> preservedTree,
            Set<BlockPos> selected) {
        for (BlockPos position : sorted(candidates)) {
            boolean touchesSelected = false;
            boolean touchesPreserved = false;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = position.relative(direction);
                touchesSelected |= selectedTree.contains(neighbor);
                touchesPreserved |= preservedTree.contains(neighbor);
            }
            if (touchesSelected && !touchesPreserved) {
                selected.add(position);
            }
        }
    }

    private static boolean touchesHorizontalBoundary(
            PlacementBounds bounds,
            BlockPos position) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                long neighborX = (long) position.getX() + offsetX;
                long neighborZ = (long) position.getZ() + offsetZ;
                if ((offsetX != 0 || offsetZ != 0)
                        && (neighborX < Integer.MIN_VALUE
                                || neighborX > Integer.MAX_VALUE
                                || neighborZ < Integer.MIN_VALUE
                                || neighborZ > Integer.MAX_VALUE
                                || !bounds.containsHorizontal(
                                        (int) neighborX,
                                        (int) neighborZ))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static UnsuitableGeneratedSiteException unresolved(
            BlockPos position,
            String reason) {
        return new UnsuitableGeneratedSiteException(
                "Cannot resolve complete tree cleanup at "
                        + position + ": " + reason);
    }

    private static BlockPos offsetExact(
            BlockPos first,
            BlockPos second) {
        try {
            return new BlockPos(
                    Math.addExact(first.getX(), second.getX()),
                    Math.addExact(first.getY(), second.getY()),
                    Math.addExact(first.getZ(), second.getZ()));
        } catch (ArithmeticException exception) {
            throw new PlacementPreparationException(
                    "Tree-cleanup template position exceeds world coordinates");
        }
    }

    private static List<BlockPos> sorted(Set<BlockPos> positions) {
        return positions.stream().sorted(POSITION_ORDER).toList();
    }

    private static List<BlockPos> createLogNeighborOffsets() {
        List<BlockPos> offsets = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        offsets.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(offsets);
    }

    record Result(
            int selectedTreeCount,
            int selectedBlockCount,
            int selectedAccessoryCount,
            List<TerrainWrite> writes) {
        Result {
            writes = List.copyOf(writes);
            if (selectedTreeCount < 0
                    || selectedBlockCount < 0
                    || selectedAccessoryCount < 0
                    || selectedAccessoryCount > selectedBlockCount) {
                throw new IllegalArgumentException(
                        "Tree-cleanup counts must be consistent");
            }
        }

        static Result empty() {
            return new Result(0, 0, 0, List.of());
        }
    }

    private record Inventory(
            Map<BlockPos, BlockState> states,
            Map<BlockPos, BlockState> logs,
            Map<BlockPos, BlockState> leaves,
            Set<BlockPos> vines,
            Set<BlockPos> cocoa,
            Set<BlockPos> beeNests) {
    }

    private record LogIndex(
            Map<BlockPos, Integer> ownerByLog,
            List<LogComponent> components) {
    }

    private record LogComponent(
            List<BlockPos> logs,
            boolean rooted) {
    }

    private static final class LeafOwnership {
        private final int distance;
        private final TreeSet<Integer> owners;

        private LeafOwnership(
                int distance,
                TreeSet<Integer> owners) {
            this.distance = distance;
            this.owners = owners;
        }
    }

    private record LeafVisit(
            BlockPos position,
            int owner,
            int distance) {
    }
}
