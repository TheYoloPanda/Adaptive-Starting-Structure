package com.typ.adaptivestartingstructure.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

public final class RotatedStructureView {
    private final Rotation rotation;
    private final Vec3i size;
    private final StructureBounds bounds;
    private final StructureMarkers markers;
    private final int groundPlaneY;
    private final List<StructureElement> placementElements;
    private final Map<BlockPos, StructureElement> elementsByPosition;
    private final Set<BlockPos> explicitAirPositions;
    private final Set<BlockPos> positionsToClear;
    private final Map<BlockPos, StructureDefinition.CellKind> explicitCells;
    private final Set<FootprintColumn> footprint;

    private RotatedStructureView(
            Rotation rotation,
            Vec3i size,
            StructureBounds bounds,
            StructureMarkers markers,
            int groundPlaneY,
            List<StructureElement> placementElements,
            Map<BlockPos, StructureElement> elementsByPosition,
            Set<BlockPos> explicitAirPositions,
            Set<BlockPos> positionsToClear,
            Map<BlockPos, StructureDefinition.CellKind> explicitCells,
            Set<FootprintColumn> footprint) {
        this.rotation = rotation;
        this.size = size;
        this.bounds = bounds;
        this.markers = markers;
        this.groundPlaneY = groundPlaneY;
        this.placementElements = List.copyOf(placementElements);
        this.elementsByPosition = Collections.unmodifiableMap(new LinkedHashMap<>(elementsByPosition));
        this.explicitAirPositions =
                Collections.unmodifiableSet(new LinkedHashSet<>(explicitAirPositions));
        this.positionsToClear = Collections.unmodifiableSet(new LinkedHashSet<>(positionsToClear));
        this.explicitCells = Collections.unmodifiableMap(new LinkedHashMap<>(explicitCells));
        this.footprint = Collections.unmodifiableSet(new LinkedHashSet<>(footprint));
    }

    static RotatedStructureView create(StructureDefinition definition, Rotation rotation) {
        Vec3i sourceSize = definition.size();
        Vec3i transformedSize = StructureTransforms.transformedSize(sourceSize, rotation);
        StructureBounds bounds = StructureBounds.relative(transformedSize);
        BlockPos transformedSpawn = StructureTransforms.transform(
                definition.markers().spawn(),
                sourceSize,
                rotation);
        BlockPos transformedGround = StructureTransforms.transform(
                definition.markers().groundLevel(),
                sourceSize,
                rotation);
        if (!bounds.contains(transformedSpawn) || !bounds.contains(transformedGround)) {
            throw new IllegalStateException(
                    "Transformed structure markers must remain inside relative bounds");
        }
        StructureMarkers markers = new StructureMarkers(transformedSpawn, transformedGround);
        int groundPlaneY = transformedGround.getY() - 1;

        List<StructureElement> placementElements =
                new ArrayList<>(definition.placementElements().size());
        Map<BlockPos, StructureElement> elementsByPosition = new LinkedHashMap<>();
        Set<BlockPos> explicitAirPositions = new LinkedHashSet<>();
        Set<BlockPos> positionsToClear = new LinkedHashSet<>();
        Map<BlockPos, StructureDefinition.CellKind> explicitCells = new LinkedHashMap<>();
        Set<FootprintColumn> footprint = new LinkedHashSet<>();

        for (StructureElement sourceElement : definition.placementElements()) {
            BlockPos position = StructureTransforms.transform(
                    sourceElement.position(),
                    sourceSize,
                    rotation);
            List<BlockState> states = sourceElement.paletteStates().stream()
                    .map(state -> rotateState(state, rotation))
                    .toList();
            StructureElement transformedElement = new StructureElement(
                    position,
                    states,
                    sourceElement.kind(),
                    sourceElement.copyBlockEntityNbtOrNull());
            placementElements.add(transformedElement);
            elementsByPosition.put(position, transformedElement);
            StructureDefinition.CellKind cellKind =
                    sourceElement.kind() == StructureElement.Kind.BLOCK
                            ? StructureDefinition.CellKind.BLOCK
                            : StructureDefinition.CellKind.EXPLICIT_AIR;
            explicitCells.put(position, cellKind);
            if (sourceElement.kind() == StructureElement.Kind.EXPLICIT_AIR) {
                explicitAirPositions.add(position);
                positionsToClear.add(position);
            }
            if (position.getY() <= groundPlaneY) {
                footprint.add(new FootprintColumn(position.getX(), position.getZ()));
            }
        }

        explicitCells.put(transformedSpawn, StructureDefinition.CellKind.SPAWN_MARKER);
        explicitCells.put(transformedGround, StructureDefinition.CellKind.GROUND_LEVEL_MARKER);
        positionsToClear.add(transformedSpawn);
        positionsToClear.add(transformedGround);
        return new RotatedStructureView(
                rotation,
                transformedSize,
                bounds,
                markers,
                groundPlaneY,
                placementElements,
                elementsByPosition,
                explicitAirPositions,
                positionsToClear,
                explicitCells,
                footprint);
    }

    @SuppressWarnings("deprecation")
    private static BlockState rotateState(BlockState state, Rotation rotation) {
        // StructureTemplate uses this level-independent overload while preparing placement.
        return state.rotate(rotation);
    }

    public Rotation rotation() {
        return rotation;
    }

    public Vec3i size() {
        return size;
    }

    public StructureBounds bounds() {
        return bounds;
    }

    public StructureMarkers markers() {
        return markers;
    }

    public int groundPlaneY() {
        return groundPlaneY;
    }

    public List<StructureElement> placementElements() {
        return placementElements;
    }

    public Optional<StructureElement> elementAt(BlockPos position) {
        return Optional.ofNullable(elementsByPosition.get(position));
    }

    public Set<BlockPos> explicitAirPositions() {
        return explicitAirPositions;
    }

    public Set<BlockPos> positionsToClear() {
        return positionsToClear;
    }

    public Optional<StructureDefinition.CellKind> cellKindAt(BlockPos position) {
        return Optional.ofNullable(explicitCells.get(position));
    }

    public Set<FootprintColumn> footprint() {
        return footprint;
    }
}
