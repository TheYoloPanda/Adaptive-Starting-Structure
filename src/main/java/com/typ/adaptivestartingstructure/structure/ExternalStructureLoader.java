package com.typ.adaptivestartingstructure.structure;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class ExternalStructureLoader {
    public static final long MAX_COMPRESSED_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_NBT_ALLOCATION_BYTES = 256L * 1024L * 1024L;

    private static final int LEGACY_DATA_VERSION_FALLBACK = 500;

    private final HolderGetter<Block> blockGetter;
    private final int currentDataVersion;
    private final DataFixerUpdater dataFixerUpdater;

    public ExternalStructureLoader(HolderGetter<Block> blockGetter) {
        this(
                blockGetter,
                SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                (tag, sourceVersion, targetVersion) ->
                        DataFixTypes.STRUCTURE.update(
                                DataFixers.getDataFixer(),
                                tag,
                                sourceVersion,
                                targetVersion));
    }

    ExternalStructureLoader(
            HolderGetter<Block> blockGetter,
            int currentDataVersion,
            DataFixerUpdater dataFixerUpdater) {
        this.blockGetter = Objects.requireNonNull(blockGetter, "blockGetter");
        if (currentDataVersion < 0) {
            throw new IllegalArgumentException("currentDataVersion must not be negative");
        }
        this.currentDataVersion = currentDataVersion;
        this.dataFixerUpdater = Objects.requireNonNull(dataFixerUpdater, "dataFixerUpdater");
    }

    public ValidatedStructureTemplate load(StructureSource source) throws StructureLoadException {
        Objects.requireNonNull(source, "source");
        byte[] compressedBytes = readCompressedBytes(source.path());
        String sha256 = sha256(compressedBytes);
        CompoundTag root = decodeCompressedNbt(source.path(), compressedBytes);
        int sourceDataVersion = readDataVersion(source.path(), root);
        CompoundTag updatedRoot = applyDataFixer(source.path(), root, sourceDataVersion);

        StructureNbtValidation validation = new StructureNbtValidator(
                blockGetter,
                source.path())
                .validate(updatedRoot);
        StructureNbtNormalizer.Result normalized = StructureNbtNormalizer.normalize(
                updatedRoot,
                validation,
                sha256);
        CompoundTag normalizedRoot = normalized.normalizedNbt();
        StructureTemplate template = loadTemplate(
                source.path(),
                normalizedRoot,
                validation.size());
        return new ValidatedStructureTemplate(
                source,
                template,
                normalizedRoot,
                validation.size(),
                sha256,
                normalized.placementSha256(),
                normalized.unavailableBlockReport(),
                sourceDataVersion,
                currentDataVersion);
    }

    public StructureDefinition loadDefinition(StructureSource source)
            throws StructureLoadException {
        StructureDefinition definition = StructureDefinition.from(
                load(source),
                blockGetter);
        UnavailableBlockReport unavailable = definition.unavailableBlockReport();
        if (!unavailable.isEmpty()) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Structure '{}' replaced {} position(s) with air because these block IDs are unavailable: {}. "
                            + "Any attached block-entity data was discarded. "
                            + "sourceSha256={}, placementSha256={}",
                    definition.source().id(),
                    unavailable.replacedPositionCount(),
                    unavailable.blockIds(),
                    definition.sha256(),
                    definition.placementSha256());
        }
        return definition;
    }

    private byte[] readCompressedBytes(Path path) throws StructureLoadException {
        long reportedSize;
        try {
            reportedSize = Files.size(path);
        } catch (IOException exception) {
            throw new StructureLoadException(
                    StructureLoadException.Category.IO,
                    path,
                    "Could not read structure file size: " + path,
                    exception);
        }
        if (reportedSize > MAX_COMPRESSED_BYTES) {
            throw invalidContent(
                    path,
                    "Compressed structure file is " + reportedSize
                            + " bytes, exceeding the limit of " + MAX_COMPRESSED_BYTES);
        }

        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(MAX_COMPRESSED_BYTES + 1L));
            if (bytes.length > MAX_COMPRESSED_BYTES) {
                throw invalidContent(
                        path,
                        "Compressed structure file exceeds the limit of " + MAX_COMPRESSED_BYTES
                                + " bytes while being read");
            }
            return bytes;
        } catch (StructureLoadException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StructureLoadException(
                    StructureLoadException.Category.IO,
                    path,
                    "Could not read structure file: " + path,
                    exception);
        }
    }

    private CompoundTag decodeCompressedNbt(Path path, byte[] compressedBytes)
            throws StructureLoadException {
        try {
            return NbtIo.readCompressed(
                    new ByteArrayInputStream(compressedBytes),
                    NbtAccounter.create(MAX_NBT_ALLOCATION_BYTES));
        } catch (IOException | RuntimeException exception) {
            throw new StructureLoadException(
                    StructureLoadException.Category.FORMAT,
                    path,
                    "Structure file is not valid size-limited compressed NBT: " + path,
                    exception);
        }
    }

    private int readDataVersion(Path path, CompoundTag root) throws StructureLoadException {
        if (root.contains("DataVersion") && !root.contains("DataVersion", Tag.TAG_ANY_NUMERIC)) {
            throw invalidContent(path, "root.DataVersion must be numeric when present");
        }
        int sourceVersion = NbtUtils.getDataVersion(root, LEGACY_DATA_VERSION_FALLBACK);
        if (sourceVersion < 0) {
            throw invalidContent(path, "root.DataVersion must not be negative");
        }
        if (sourceVersion > currentDataVersion) {
            throw invalidContent(
                    path,
                    "Structure DataVersion " + sourceVersion
                            + " is newer than the supported version " + currentDataVersion);
        }
        return sourceVersion;
    }

    private CompoundTag applyDataFixer(Path path, CompoundTag root, int sourceDataVersion)
            throws StructureLoadException {
        if (sourceDataVersion == currentDataVersion) {
            return root;
        }

        try {
            CompoundTag updated = dataFixerUpdater.update(
                    root,
                    sourceDataVersion,
                    currentDataVersion);
            if (updated == null) {
                throw new IllegalStateException("DataFixer returned null");
            }
            updated.putInt("DataVersion", currentDataVersion);
            return updated;
        } catch (RuntimeException exception) {
            throw new StructureLoadException(
                    StructureLoadException.Category.DATA_FIXER,
                    path,
                    "Could not update structure from DataVersion " + sourceDataVersion
                            + " to " + currentDataVersion + ": " + path,
                    exception);
        }
    }

    private StructureTemplate loadTemplate(Path path, CompoundTag root, Vec3i validatedSize)
            throws StructureLoadException {
        try {
            StructureTemplate template = new StructureTemplate();
            template.load(blockGetter, root);
            if (!template.getSize().equals(validatedSize)) {
                throw new IllegalStateException(
                        "Loaded template size does not match the validated size");
            }
            return template;
        } catch (RuntimeException exception) {
            throw new StructureLoadException(
                    StructureLoadException.Category.INVALID_CONTENT,
                    path,
                    "Validated structure could not be loaded as a StructureTemplate: " + path,
                    exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    private static StructureLoadException invalidContent(Path path, String message) {
        return new StructureLoadException(
                StructureLoadException.Category.INVALID_CONTENT,
                path,
                message + ": " + path);
    }

    @FunctionalInterface
    interface DataFixerUpdater {
        CompoundTag update(CompoundTag tag, int sourceVersion, int targetVersion);
    }
}
