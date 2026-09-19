package com.typ.adaptivestartingstructure.structure;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.phys.Vec3;

public final class StructureDefinition {
    public static final String SPAWN_MARKER = AdaptiveStartingStructure.MOD_ID + ":spawn";
    public static final String GROUND_LEVEL_MARKER =
            AdaptiveStartingStructure.MOD_ID + ":ground_level";

    private final StructureSource source;
    private final Vec3i size;
    private final String sha256;
    private final String placementSha256;
    private final UnavailableBlockReport unavailableBlockReport;
    private final int sourceDataVersion;
    private final int loadedDataVersion;
    private final int paletteCount;
    private final List<StructureElement> placementElements;
    private final Map<BlockPos, StructureElement> elementsByPosition;
    private final Set<BlockPos> explicitAirPositions;
    private final Set<BlockPos> positionsToClear;
    private final Map<BlockPos, CellKind> explicitCells;
    private final StructureMarkers markers;
    private final int blockCount;
    private final int blockEntityCount;
    private final List<StructureEntityData> entities;
    private final Map<Rotation, RotatedStructureView> rotatedViews;

    private StructureDefinition(
            ValidatedStructureTemplate validated,
            int paletteCount,
            List<StructureElement> placementElements,
            Map<BlockPos, StructureElement> elementsByPosition,
            Set<BlockPos> explicitAirPositions,
            Set<BlockPos> positionsToClear,
            Map<BlockPos, CellKind> explicitCells,
            StructureMarkers markers,
            int blockCount,
            int blockEntityCount,
            List<StructureEntityData> entities) {
        this.source = validated.source();
        this.size = validated.size();
        this.sha256 = validated.sha256();
        this.placementSha256 = validated.placementSha256();
        this.unavailableBlockReport = validated.unavailableBlockReport();
        this.sourceDataVersion = validated.sourceDataVersion();
        this.loadedDataVersion = validated.loadedDataVersion();
        this.paletteCount = paletteCount;
        this.placementElements = List.copyOf(placementElements);
        this.elementsByPosition = Collections.unmodifiableMap(new LinkedHashMap<>(elementsByPosition));
        this.explicitAirPositions =
                Collections.unmodifiableSet(new LinkedHashSet<>(explicitAirPositions));
        this.positionsToClear = Collections.unmodifiableSet(new LinkedHashSet<>(positionsToClear));
        this.explicitCells = Collections.unmodifiableMap(new LinkedHashMap<>(explicitCells));
        this.markers = Objects.requireNonNull(markers, "markers");
        this.blockCount = blockCount;
        this.blockEntityCount = blockEntityCount;
        this.entities = List.copyOf(entities);
        this.rotatedViews = createRotatedViews();
    }

    static StructureDefinition from(
            ValidatedStructureTemplate validated,
            HolderGetter<Block> blockGetter) throws StructureLoadException {
        Objects.requireNonNull(validated, "validated");
        Objects.requireNonNull(blockGetter, "blockGetter");
        CompoundTag root = validated.verifiedNbt();
        List<List<BlockState>> palettes = readPalettes(root, blockGetter);
        ListTag blocks = root.getList("blocks", CompoundTag.TAG_COMPOUND);

        List<StructureElement> placementElements = new ArrayList<>(blocks.size());
        Map<BlockPos, StructureElement> elementsByPosition = new LinkedHashMap<>();
        Set<BlockPos> explicitAirPositions = new LinkedHashSet<>();
        Set<BlockPos> positionsToClear = new LinkedHashSet<>();
        Map<BlockPos, CellKind> explicitCells = new LinkedHashMap<>();
        BlockPos spawnMarker = null;
        BlockPos groundLevelMarker = null;
        int blockCount = 0;
        int blockEntityCount = 0;

        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            CompoundTag blockTag = blocks.getCompound(blockIndex);
            ListTag positionTag = blockTag.getList("pos", CompoundTag.TAG_INT);
            BlockPos position = new BlockPos(
                    positionTag.getInt(0),
                    positionTag.getInt(1),
                    positionTag.getInt(2));
            int stateIndex = blockTag.getInt("state");
            List<BlockState> states = palettes.stream()
                    .map(palette -> palette.get(stateIndex))
                    .toList();
            CompoundTag blockEntityNbt =
                    blockTag.contains("nbt", CompoundTag.TAG_COMPOUND)
                            ? blockTag.getCompound("nbt")
                            : null;
            if (blockEntityNbt != null) {
                blockEntityCount++;
            }

            CellKind kind = classify(states, blockEntityNbt, position, validated.source().path());
            explicitCells.put(position, kind);
            switch (kind) {
                case BLOCK -> {
                    StructureElement element = new StructureElement(
                            position,
                            states,
                            StructureElement.Kind.BLOCK,
                            blockEntityNbt);
                    placementElements.add(element);
                    elementsByPosition.put(position, element);
                    blockCount++;
                }
                case EXPLICIT_AIR -> {
                    StructureElement element = new StructureElement(
                            position,
                            states,
                            StructureElement.Kind.EXPLICIT_AIR,
                            blockEntityNbt);
                    placementElements.add(element);
                    elementsByPosition.put(position, element);
                    explicitAirPositions.add(position);
                    positionsToClear.add(position);
                }
                case SPAWN_MARKER -> {
                    if (spawnMarker != null) {
                        throw invalid(
                                validated.source().path(),
                                "Duplicate marker '" + SPAWN_MARKER + "' at "
                                        + spawnMarker + " and " + position);
                    }
                    spawnMarker = position;
                    positionsToClear.add(position);
                }
                case GROUND_LEVEL_MARKER -> {
                    if (groundLevelMarker != null) {
                        throw invalid(
                                validated.source().path(),
                                "Duplicate marker '" + GROUND_LEVEL_MARKER + "' at "
                                        + groundLevelMarker + " and " + position);
                    }
                    groundLevelMarker = position;
                    positionsToClear.add(position);
                }
            }
        }

        if (spawnMarker == null) {
            throw invalid(validated.source().path(), "Missing marker '" + SPAWN_MARKER + "'");
        }
        if (groundLevelMarker == null) {
            throw invalid(
                    validated.source().path(),
                    "Missing marker '" + GROUND_LEVEL_MARKER + "'");
        }

        StructureMarkers markers = new StructureMarkers(spawnMarker, groundLevelMarker);
        List<StructureEntityData> entities = readEntities(root);
        return new StructureDefinition(
                validated,
                palettes.size(),
                placementElements,
                elementsByPosition,
                explicitAirPositions,
                positionsToClear,
                explicitCells,
                markers,
                blockCount,
                blockEntityCount,
                entities);
    }

    public StructureSource source() {
        return source;
    }

    public Vec3i size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    public String placementSha256() {
        return placementSha256;
    }

    public UnavailableBlockReport unavailableBlockReport() {
        return unavailableBlockReport;
    }

    public int sourceDataVersion() {
        return sourceDataVersion;
    }

    public int loadedDataVersion() {
        return loadedDataVersion;
    }

    public int paletteCount() {
        return paletteCount;
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

    public Optional<CellKind> cellKindAt(BlockPos position) {
        return Optional.ofNullable(explicitCells.get(position));
    }

    public StructureMarkers markers() {
        return markers;
    }

    public int blockCount() {
        return blockCount;
    }

    public int explicitAirCount() {
        return explicitAirPositions.size();
    }

    public int blockEntityCount() {
        return blockEntityCount;
    }

    public int entityCount() {
        return entities.size();
    }

    public List<StructureEntityData> entities() {
        return entities;
    }

    public String summary() {
        return source.id() + " [" + size.getX() + "x" + size.getY() + "x" + size.getZ()
                + ", blocks=" + blockCount
                + ", explicitAir=" + explicitAirCount()
                + ", blockEntities=" + blockEntityCount
                + ", entities=" + entityCount()
                + ", replacedUnavailableWithAir="
                + unavailableBlockReport.replacedPositionCount()
                + ", palettes=" + paletteCount + "]";
    }

    public RotatedStructureView view(Rotation rotation) {
        RotatedStructureView view = rotatedViews.get(Objects.requireNonNull(rotation, "rotation"));
        if (view == null) {
            throw new IllegalArgumentException("Unsupported structure rotation: " + rotation);
        }
        return view;
    }

    private Map<Rotation, RotatedStructureView> createRotatedViews() {
        EnumMap<Rotation, RotatedStructureView> views = new EnumMap<>(Rotation.class);
        for (Rotation rotation : Rotation.values()) {
            views.put(rotation, RotatedStructureView.create(this, rotation));
        }
        return Collections.unmodifiableMap(views);
    }

    private static List<List<BlockState>> readPalettes(
            CompoundTag root,
            HolderGetter<Block> blockGetter) {
        List<List<BlockState>> palettes = new ArrayList<>();
        if (root.contains("palettes", CompoundTag.TAG_LIST)) {
            ListTag palettesTag = root.getList("palettes", CompoundTag.TAG_LIST);
            for (int index = 0; index < palettesTag.size(); index++) {
                palettes.add(readPalette(palettesTag.getList(index), blockGetter));
            }
        } else {
            palettes.add(readPalette(
                    root.getList("palette", CompoundTag.TAG_COMPOUND),
                    blockGetter));
        }
        return List.copyOf(palettes);
    }

    private static List<StructureEntityData> readEntities(
            CompoundTag root) {
        if (!root.contains(
                "entities",
                CompoundTag.TAG_LIST)) {
            return List.of();
        }
        ListTag entityTags = root.getList(
                "entities",
                CompoundTag.TAG_COMPOUND);
        List<StructureEntityData> entities =
                new ArrayList<>(entityTags.size());
        for (int index = 0;
                index < entityTags.size();
                index++) {
            CompoundTag source =
                    entityTags.getCompound(index);
            ListTag position =
                    source.getList(
                            "pos",
                            CompoundTag.TAG_DOUBLE);
            ListTag blockPosition =
                    source.getList(
                            "blockPos",
                            CompoundTag.TAG_INT);
            CompoundTag entityNbt =
                    source.getCompound("nbt");
            Optional<String> authoredId =
                    entityNbt.contains(
                                    "id",
                                    CompoundTag.TAG_STRING)
                            ? Optional.of(
                                    entityNbt.getString("id"))
                            : Optional.empty();
            entities.add(new StructureEntityData(
                    index,
                    new Vec3(
                            position.getDouble(0),
                            position.getDouble(1),
                            position.getDouble(2)),
                    new BlockPos(
                            blockPosition.getInt(0),
                            blockPosition.getInt(1),
                            blockPosition.getInt(2)),
                    entityNbt,
                    authoredId));
        }
        return List.copyOf(entities);
    }

    private static List<BlockState> readPalette(
            ListTag paletteTag,
            HolderGetter<Block> blockGetter) {
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int index = 0; index < paletteTag.size(); index++) {
            palette.add(NbtUtils.readBlockState(blockGetter, paletteTag.getCompound(index)));
        }
        return List.copyOf(palette);
    }

    private static CellKind classify(
            List<BlockState> states,
            CompoundTag blockEntityNbt,
            BlockPos position,
            Path sourcePath) throws StructureLoadException {
        boolean allAir = states.stream().allMatch(BlockState::isAir);
        boolean anyAir = states.stream().anyMatch(BlockState::isAir);
        boolean allStructureBlocks = states.stream().allMatch(state -> state.is(Blocks.STRUCTURE_BLOCK));
        boolean anyStructureBlock = states.stream().anyMatch(state -> state.is(Blocks.STRUCTURE_BLOCK));

        if (anyAir && !allAir) {
            throw invalid(
                    sourcePath,
                    "Palette alternatives disagree whether position " + position + " is air");
        }
        if (anyStructureBlock && !allStructureBlocks) {
            throw invalid(
                    sourcePath,
                    "Palette alternatives disagree whether position " + position
                            + " is a structure marker");
        }
        if (allAir) {
            return CellKind.EXPLICIT_AIR;
        }
        if (!allStructureBlocks) {
            return CellKind.BLOCK;
        }

        if (blockEntityNbt == null) {
            throw invalid(sourcePath, "Structure marker at " + position + " has no BlockEntity NBT");
        }
        for (BlockState state : states) {
            if (state.getValue(StructureBlock.MODE) != StructureMode.DATA) {
                throw invalid(sourcePath, "Structure marker at " + position + " is not in DATA mode");
            }
        }
        if (!blockEntityNbt.contains("mode", CompoundTag.TAG_STRING)
                || !"DATA".equals(blockEntityNbt.getString("mode"))) {
            throw invalid(
                    sourcePath,
                    "Structure marker BlockEntity at " + position + " is not in DATA mode");
        }
        if (!blockEntityNbt.contains("metadata", CompoundTag.TAG_STRING)) {
            throw invalid(
                    sourcePath,
                    "Structure marker BlockEntity at " + position + " has no string metadata");
        }

        return switch (blockEntityNbt.getString("metadata")) {
            case SPAWN_MARKER -> CellKind.SPAWN_MARKER;
            case GROUND_LEVEL_MARKER -> CellKind.GROUND_LEVEL_MARKER;
            default -> throw invalid(
                    sourcePath,
                    "Unknown structure marker metadata '"
                            + blockEntityNbt.getString("metadata") + "' at " + position);
        };
    }

    private static StructureLoadException invalid(Path sourcePath, String message) {
        return new StructureLoadException(
                StructureLoadException.Category.INVALID_CONTENT,
                sourcePath,
                message + " in " + sourcePath);
    }

    public enum CellKind {
        BLOCK,
        EXPLICIT_AIR,
        SPAWN_MARKER,
        GROUND_LEVEL_MARKER
    }
}
