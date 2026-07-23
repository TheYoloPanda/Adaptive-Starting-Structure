package com.typ.adaptivestartingstructure.structure;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

final class StructureNbtValidator {
    static final int MAX_AXIS_SIZE = 256;
    static final long MAX_VOLUME = 2_000_000L;

    private final HolderGetter<Block> blockGetter;
    private final Path sourcePath;

    StructureNbtValidator(HolderGetter<Block> blockGetter, Path sourcePath) {
        this.blockGetter = blockGetter;
        this.sourcePath = sourcePath;
    }

    StructureNbtValidation validate(CompoundTag root) throws StructureLoadException {
        Vec3i size = validateSize(root);
        PaletteValidation palettes = validatePalettes(root);
        validateBlocks(root, size, palettes.paletteSize());
        validateEntities(root, size);
        return new StructureNbtValidation(
                size,
                palettes.paletteSize(),
                palettes.unavailableBlocksByStateIndex());
    }

    private Vec3i validateSize(CompoundTag root) throws StructureLoadException {
        ListTag sizeTag = requireList(root, "size", Tag.TAG_INT, false, "root");
        if (sizeTag.size() != 3) {
            invalid("root.size must contain exactly three integers");
        }

        int sizeX = sizeTag.getInt(0);
        int sizeY = sizeTag.getInt(1);
        int sizeZ = sizeTag.getInt(2);
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            invalid("Structure dimensions must all be positive, found "
                    + sizeX + " x " + sizeY + " x " + sizeZ);
        }
        if (sizeX > MAX_AXIS_SIZE || sizeY > MAX_AXIS_SIZE || sizeZ > MAX_AXIS_SIZE) {
            invalid("Structure dimensions exceed the maximum of " + MAX_AXIS_SIZE
                    + " blocks per axis: " + sizeX + " x " + sizeY + " x " + sizeZ);
        }

        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) {
            invalid("Structure bounding-box volume " + volume
                    + " exceeds the maximum of " + MAX_VOLUME);
        }
        return new Vec3i(sizeX, sizeY, sizeZ);
    }

    private PaletteValidation validatePalettes(CompoundTag root)
            throws StructureLoadException {
        boolean hasPalette = root.contains("palette");
        boolean hasPalettes = root.contains("palettes");
        if (hasPalette == hasPalettes) {
            invalid("Structure must contain exactly one of root.palette or root.palettes");
        }

        if (hasPalette) {
            ListTag palette = requireList(root, "palette", Tag.TAG_COMPOUND, false, "root");
            Map<Integer, List<ResourceLocation>> unavailable = new LinkedHashMap<>();
            validatePalette(palette, "root.palette", unavailable);
            return new PaletteValidation(palette.size(), unavailable);
        }

        ListTag palettes = requireList(root, "palettes", Tag.TAG_LIST, false, "root");
        if (palettes.isEmpty()) {
            invalid("root.palettes must contain at least one palette");
        }

        int expectedSize = -1;
        Map<Integer, List<ResourceLocation>> unavailable = new LinkedHashMap<>();
        for (int paletteIndex = 0; paletteIndex < palettes.size(); paletteIndex++) {
            ListTag palette = requireNestedList(
                    palettes.get(paletteIndex),
                    Tag.TAG_COMPOUND,
                    false,
                    "root.palettes[" + paletteIndex + "]");
            validatePalette(
                    palette,
                    "root.palettes[" + paletteIndex + "]",
                    unavailable);
            if (expectedSize == -1) {
                expectedSize = palette.size();
            } else if (palette.size() != expectedSize) {
                invalid("All palettes must contain the same number of block states");
            }
        }
        return new PaletteValidation(expectedSize, unavailable);
    }

    private void validatePalette(
            ListTag palette,
            String location,
            Map<Integer, List<ResourceLocation>> unavailableBlocksByStateIndex)
            throws StructureLoadException {
        if (palette.isEmpty()) {
            invalid(location + " must contain at least one block state");
        }
        for (int index = 0; index < palette.size(); index++) {
            ResourceLocation unavailable = validateBlockState(
                    (CompoundTag) palette.get(index),
                    location + "[" + index + "]");
            if (unavailable != null) {
                unavailableBlocksByStateIndex
                        .computeIfAbsent(index, ignored -> new ArrayList<>())
                        .add(unavailable);
            }
        }
    }

    private ResourceLocation validateBlockState(
            CompoundTag stateTag,
            String location) throws StructureLoadException {
        if (!stateTag.contains("Name", Tag.TAG_STRING)) {
            invalid(location + ".Name must be a string");
        }

        String encodedName = stateTag.getString("Name");
        ResourceLocation blockId = ResourceLocation.tryParse(encodedName);
        if (blockId == null || blockId.getPath().isEmpty()) {
            invalid(location + ".Name is not a valid resource location: '" + encodedName + "'");
        }

        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, blockId);
        Holder.Reference<Block> blockHolder = blockGetter.get(blockKey).orElse(null);

        if (!stateTag.contains("Properties")) {
            return blockHolder == null ? blockId : null;
        }
        if (!stateTag.contains("Properties", Tag.TAG_COMPOUND)) {
            invalid(location + ".Properties must be a compound");
        }

        CompoundTag properties = stateTag.getCompound("Properties");
        for (String propertyName : properties.getAllKeys()) {
            if (!properties.contains(propertyName, Tag.TAG_STRING)) {
                invalid(location + ".Properties." + propertyName + " must be a string");
            }

            if (blockHolder == null) {
                continue;
            }

            Property<?> property = blockHolder.value().getStateDefinition().getProperty(propertyName);
            if (property == null) {
                invalid(location + " references unknown property '" + propertyName
                        + "' for block " + blockId);
            }

            String propertyValue = properties.getString(propertyName);
            if (property.getValue(propertyValue).isEmpty()) {
                invalid(location + " has invalid value '" + propertyValue + "' for property '"
                        + propertyName + "' on block " + blockId);
            }
        }
        return blockHolder == null ? blockId : null;
    }

    private void validateBlocks(CompoundTag root, Vec3i size, int paletteSize)
            throws StructureLoadException {
        ListTag blocks = requireList(root, "blocks", Tag.TAG_COMPOUND, true, "root");
        Set<Integer> occupiedPositions = new HashSet<>(Math.min(blocks.size(), 1 << 16));
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag block = (CompoundTag) blocks.get(index);
            String location = "root.blocks[" + index + "]";
            ListTag position = requireList(block, "pos", Tag.TAG_INT, false, location);
            if (position.size() != 3) {
                invalid(location + ".pos must contain exactly three integers");
            }
            if (!block.contains("state", Tag.TAG_INT)) {
                invalid(location + ".state must be an integer");
            }

            int stateIndex = block.getInt("state");
            if (stateIndex < 0 || stateIndex >= paletteSize) {
                invalid(location + ".state index " + stateIndex
                        + " is outside palette bounds [0, " + paletteSize + ")");
            }

            int x = position.getInt(0);
            int y = position.getInt(1);
            int z = position.getInt(2);
            validateBlockPosition(x, y, z, size, location + ".pos");
            int linearPosition = (y * size.getZ() + z) * size.getX() + x;
            if (!occupiedPositions.add(linearPosition)) {
                invalid(location + ".pos duplicates another explicit block position");
            }

            if (block.contains("nbt") && !block.contains("nbt", Tag.TAG_COMPOUND)) {
                invalid(location + ".nbt must be a compound when present");
            }
        }
    }

    private void validateEntities(CompoundTag root, Vec3i size) throws StructureLoadException {
        if (!root.contains("entities")) {
            return;
        }

        ListTag entities = requireList(root, "entities", Tag.TAG_COMPOUND, true, "root");
        for (int index = 0; index < entities.size(); index++) {
            CompoundTag entity = (CompoundTag) entities.get(index);
            String location = "root.entities[" + index + "]";

            ListTag position = requireList(entity, "pos", Tag.TAG_DOUBLE, false, location);
            if (position.size() != 3) {
                invalid(location + ".pos must contain exactly three doubles");
            }
            for (int axis = 0; axis < 3; axis++) {
                if (!Double.isFinite(position.getDouble(axis))) {
                    invalid(location + ".pos contains a non-finite coordinate");
                }
            }
            if (position.getDouble(0) < 0.0 || position.getDouble(0) >= size.getX()
                    || position.getDouble(1) < 0.0 || position.getDouble(1) >= size.getY()
                    || position.getDouble(2) < 0.0 || position.getDouble(2) >= size.getZ()) {
                invalid(location + ".pos lies outside the declared structure bounds");
            }

            ListTag blockPosition = requireList(entity, "blockPos", Tag.TAG_INT, false, location);
            if (blockPosition.size() != 3) {
                invalid(location + ".blockPos must contain exactly three integers");
            }
            validateBlockPosition(
                    blockPosition.getInt(0),
                    blockPosition.getInt(1),
                    blockPosition.getInt(2),
                    size,
                    location + ".blockPos");

            if (!entity.contains("nbt", Tag.TAG_COMPOUND)) {
                invalid(location + ".nbt must be a compound");
            }
        }
    }

    private void validateBlockPosition(int x, int y, int z, Vec3i size, String location)
            throws StructureLoadException {
        if (x < 0 || x >= size.getX()
                || y < 0 || y >= size.getY()
                || z < 0 || z >= size.getZ()) {
            invalid(location + " [" + x + ", " + y + ", " + z
                    + "] lies outside bounds [0, " + size.getX() + ") x [0, "
                    + size.getY() + ") x [0, " + size.getZ() + ")");
        }
    }

    private ListTag requireList(
            CompoundTag parent,
            String key,
            int elementType,
            boolean allowEmpty,
            String parentLocation) throws StructureLoadException {
        if (!parent.contains(key, Tag.TAG_LIST)) {
            invalid(parentLocation + "." + key + " must be a list");
        }
        return requireNestedList(
                parent.get(key),
                elementType,
                allowEmpty,
                parentLocation + "." + key);
    }

    private ListTag requireNestedList(Tag tag, int elementType, boolean allowEmpty, String location)
            throws StructureLoadException {
        if (!(tag instanceof ListTag list)) {
            invalid(location + " must be a list");
            throw new AssertionError("unreachable");
        }
        if (list.isEmpty()) {
            if (!allowEmpty) {
                invalid(location + " must not be empty");
            }
        } else if (list.getElementType() != elementType) {
            invalid(location + " must contain only " + tagTypeName(elementType) + " entries");
        }
        return list;
    }

    private void invalid(String message) throws StructureLoadException {
        throw new StructureLoadException(
                StructureLoadException.Category.INVALID_CONTENT,
                sourcePath,
                message + " in " + sourcePath);
    }

    private static String tagTypeName(int tagType) {
        return switch (tagType) {
            case Tag.TAG_INT -> "integer";
            case Tag.TAG_DOUBLE -> "double";
            case Tag.TAG_LIST -> "list";
            case Tag.TAG_COMPOUND -> "compound";
            default -> "NBT type " + tagType;
        };
    }

    private record PaletteValidation(
            int paletteSize,
            Map<Integer, List<ResourceLocation>> unavailableBlocksByStateIndex) {
    }
}
