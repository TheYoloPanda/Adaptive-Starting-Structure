package com.typ.adaptivestartingstructure.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;

public final class StartingStructureStorage {
    private StartingStructureStorage() {
    }

    public static void persistInitial(
            ServerLevel level,
            StartingStructureSavedData data) throws IOException {
        requireOverworld(level);
        Objects.requireNonNull(data, "data");

        DimensionDataStorage storage = level.getDataStorage();
        StartingStructureSavedData existing = storage.get(
                StartingStructureSavedData.FACTORY,
                StartingStructureSavedData.DATA_NAME);
        Path dataFile = dataFile(level);
        if (existing != null || Files.exists(dataFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Refusing to overwrite existing starting-structure data: "
                            + dataFile);
        }

        writeInitialFile(dataFile, data, level.registryAccess());
        data.setDirty(false);
        storage.set(StartingStructureSavedData.DATA_NAME, data);
    }

    public static Optional<StartingStructureSavedData> load(
            ServerLevel level) {
        requireOverworld(level);
        StartingStructureSavedData data =
                level.getDataStorage().get(
                        StartingStructureSavedData.FACTORY,
                        StartingStructureSavedData.DATA_NAME);
        Path dataFile = dataFile(level);
        if (data == null
                && Files.exists(
                        dataFile,
                        LinkOption.NOFOLLOW_LINKS)) {
            throw new StartingStructureDataException(
                    "Starting-structure data exists but could not be loaded: "
                            + dataFile);
        }
        return Optional.ofNullable(data);
    }

    public static void persistCurrent(
            ServerLevel level,
            StartingStructureSavedData data) throws IOException {
        requireOverworld(level);
        Objects.requireNonNull(data, "data");
        StartingStructureSavedData cached =
                level.getDataStorage().get(
                        StartingStructureSavedData.FACTORY,
                        StartingStructureSavedData.DATA_NAME);
        if (cached != data) {
            throw new IOException(
                    "Refusing to persist a starting-structure state that is not the active SavedData instance");
        }
        Path dataFile = dataFile(level);
        if (!Files.isRegularFile(
                dataFile,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Starting-structure data file is missing or not regular: "
                            + dataFile);
        }
        writeStateFile(dataFile, data, level.registryAccess());
        data.setDirty(false);
    }

    public static void persistAwaitingDecision(
            ServerLevel level,
            StartingStructureSavedData data,
            FallbackDecision decision) throws IOException {
        requireOverworld(level);
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(decision, "decision");
        if (data.state()
                != StartingStructureSavedData.State.PLANNED) {
            throw new IOException(
                    "Expected starting-structure state PLANNED before persisting AWAITING_DECISION, found "
                            + data.state());
        }
        StartingStructureSavedData cached =
                level.getDataStorage().get(
                        StartingStructureSavedData.FACTORY,
                        StartingStructureSavedData.DATA_NAME);
        if (cached != data) {
            throw new IOException(
                    "Refusing to persist a starting-structure state that is not the active SavedData instance");
        }
        Path dataFile = dataFile(level);
        if (!Files.isRegularFile(
                dataFile,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Starting-structure data file is missing or not regular: "
                            + dataFile);
        }

        CompoundTag next = data.saveAwaitingDecision(
                new CompoundTag(),
                level.registryAccess(),
                decision);
        writeEncodedStateFile(
                dataFile,
                encodeDataTag(next));
        data.markAwaitingDecision(decision);
        data.setDirty(false);
    }

    public static void persistFallbackApplying(
            ServerLevel level,
            StartingStructureSavedData data) throws IOException {
        persistFallbackTransition(
                level,
                data,
                StartingStructureSavedData.State.AWAITING_DECISION,
                StartingStructureSavedData.State.FALLBACK_APPLYING);
        data.markFallbackApplying();
        data.setDirty(false);
    }

    public static void persistSkipped(
            ServerLevel level,
            StartingStructureSavedData data) throws IOException {
        persistFallbackTransition(
                level,
                data,
                StartingStructureSavedData.State.FALLBACK_APPLYING,
                StartingStructureSavedData.State.SKIPPED);
        data.markSkipped();
        data.setDirty(false);
    }

    static void writeInitialFile(
            Path dataFile,
            StartingStructureSavedData data,
            HolderLookup.Provider registries) throws IOException {
        Objects.requireNonNull(dataFile, "dataFile");
        Objects.requireNonNull(data, "data");
        Path normalizedFile = dataFile.toAbsolutePath().normalize();
        Path parent = normalizedFile.getParent();
        if (parent == null) {
            throw new IOException(
                    "Starting-structure data file has no parent directory: "
                            + normalizedFile);
        }
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) {
            throw new IOException(
                    "Starting-structure data directory must not be a symbolic link: "
                            + parent);
        }
        if (Files.exists(normalizedFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Refusing to overwrite existing starting-structure data: "
                            + normalizedFile);
        }

        IOUtilities.writeNbtCompressed(
                encode(data, registries),
                normalizedFile);
    }

    static void writeStateFile(
            Path dataFile,
            StartingStructureSavedData data,
            HolderLookup.Provider registries) throws IOException {
        Objects.requireNonNull(dataFile, "dataFile");
        Objects.requireNonNull(data, "data");
        Path normalizedFile =
                dataFile.toAbsolutePath().normalize();
        Path parent = normalizedFile.getParent();
        if (parent == null
                || !Files.isDirectory(
                        parent,
                        LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException(
                    "Starting-structure data directory is missing, invalid, or symbolic: "
                            + parent);
        }
        IOUtilities.writeNbtCompressed(
                encode(data, registries),
                normalizedFile);
    }

    private static CompoundTag encode(
            StartingStructureSavedData data,
            HolderLookup.Provider registries) {
        return encodeDataTag(
                data.save(new CompoundTag(), registries));
    }

    private static CompoundTag encodeDataTag(
            CompoundTag dataTag) {
        CompoundTag root = new CompoundTag();
        root.put("data", dataTag);
        NbtUtils.addCurrentDataVersion(root);
        return root;
    }

    private static void persistFallbackTransition(
            ServerLevel level,
            StartingStructureSavedData data,
            StartingStructureSavedData.State expected,
            StartingStructureSavedData.State target)
            throws IOException {
        requireOverworld(level);
        Objects.requireNonNull(data, "data");
        if (data.state() != expected) {
            throw new IOException(
                    "Expected starting-structure state "
                            + expected
                            + " before persisting "
                            + target
                            + ", found "
                            + data.state());
        }
        StartingStructureSavedData cached =
                level.getDataStorage().get(
                        StartingStructureSavedData.FACTORY,
                        StartingStructureSavedData.DATA_NAME);
        if (cached != data) {
            throw new IOException(
                    "Refusing to persist a starting-structure state that is not the active SavedData instance");
        }
        Path dataFile = dataFile(level);
        if (!Files.isRegularFile(
                dataFile,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Starting-structure data file is missing or not regular: "
                            + dataFile);
        }
        CompoundTag next = data.save(
                new CompoundTag(),
                level.registryAccess());
        next.putString("State", target.name());
        writeEncodedStateFile(
                dataFile,
                encodeDataTag(next));
    }

    private static void writeEncodedStateFile(
            Path dataFile,
            CompoundTag root) throws IOException {
        Path normalizedFile =
                dataFile.toAbsolutePath().normalize();
        Path parent = normalizedFile.getParent();
        if (parent == null
                || !Files.isDirectory(
                        parent,
                        LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException(
                    "Starting-structure data directory is missing, invalid, or symbolic: "
                            + parent);
        }
        IOUtilities.writeNbtCompressed(
                root,
                normalizedFile);
    }

    private static Path dataFile(ServerLevel level) {
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(
                        StartingStructureSavedData.DATA_NAME
                                + ".dat")
                .toAbsolutePath()
                .normalize();
    }

    private static void requireOverworld(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (level.dimension() != Level.OVERWORLD) {
            throw new IllegalArgumentException(
                    "Starting-structure data belongs to the Overworld");
        }
    }
}
