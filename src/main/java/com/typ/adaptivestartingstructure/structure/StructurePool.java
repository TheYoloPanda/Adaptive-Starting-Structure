package com.typ.adaptivestartingstructure.structure;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.neoforged.fml.loading.FMLPaths;

public final class StructurePool {
    public static final int SELECTION_ALGORITHM_VERSION = 1;
    public static final long SELECTION_SALT_V1 = 0x4153_5452_504F_4F4CL;

    private static final String STRUCTURES_DIRECTORY = "structures";
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+");
    private static final long SPLIT_MIX_GAMMA = 0x9E37_79B9_7F4A_7C15L;

    private StructurePool() {
    }

    public static List<StructureSource> discover() throws IOException {
        return discover(FMLPaths.CONFIGDIR.get());
    }

    public static List<StructureSource> discover(Path configDirectory) throws IOException {
        Objects.requireNonNull(configDirectory, "configDirectory");
        try {
            Path directory = structuresDirectory(configDirectory);
            List<Path> candidates = listCandidates(directory);
            if (candidates.isEmpty()) {
                throw StructurePoolException.emptyPool(directory);
            }

            candidates.sort(Comparator
                    .comparing((Path path) -> fileName(path).toLowerCase(Locale.ROOT))
                    .thenComparing(StructurePool::fileName));

            Map<String, Path> pathsByNormalizedId = new LinkedHashMap<>();
            for (Path candidate : candidates) {
                String rawId = idFromFileName(candidate);
                String normalizedId = rawId.toLowerCase(Locale.ROOT);
                Path duplicate = pathsByNormalizedId.putIfAbsent(normalizedId, candidate);
                if (duplicate != null) {
                    throw StructurePoolException.duplicateId(
                            rawId,
                            duplicate,
                            candidate);
                }
            }

            List<StructureSource> sources = new ArrayList<>(pathsByNormalizedId.size());
            for (Map.Entry<String, Path> entry : pathsByNormalizedId.entrySet()) {
                String rawId = idFromFileName(entry.getValue());
                if (!VALID_ID.matcher(rawId).matches()) {
                    throw StructurePoolException.invalidFilename(
                            entry.getValue(),
                            "the ID must match [a-z0-9_.-]+");
                }
                sources.add(new StructureSource(entry.getKey(), entry.getValue()));
            }
            return List.copyOf(sources);
        } catch (StructurePoolException failure) {
            throw failure;
        } catch (IOException failure) {
            throw StructurePoolException.filesystem(
                    "discover the structure pool",
                    configDirectory.toAbsolutePath().normalize(),
                    failure);
        }
    }

    public static StructureSource resolveSelected(String id) throws IOException {
        return resolveSelected(FMLPaths.CONFIGDIR.get(), id);
    }

    public static StructureSource resolveSelected(
            Path configDirectory,
            String id) throws IOException {
        Objects.requireNonNull(id, "id");
        if (!VALID_ID.matcher(id).matches()) {
            throw StructurePoolException.invalidSelectedId(id);
        }

        Objects.requireNonNull(configDirectory, "configDirectory");
        try {
            Path directory = structuresDirectory(configDirectory);
            Path selected = directory.resolve(id + ".nbt").normalize();
            if (!Objects.equals(selected.getParent(), directory)
                    || Files.isSymbolicLink(selected)
                    || !Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(selected)) {
                throw StructurePoolException.selectedFileUnavailable(
                        id,
                        selected);
            }
            Path realSelected = selected.toRealPath();
            if (!Objects.equals(realSelected.getParent(), directory)) {
                throw StructurePoolException.unsafePath(
                        selected,
                        "Selected structure file resolves outside the pool directory");
            }
            return new StructureSource(id, realSelected);
        } catch (StructurePoolException failure) {
            throw failure;
        } catch (IOException failure) {
            throw StructurePoolException.filesystem(
                    "resolve the selected structure file",
                    configDirectory.toAbsolutePath().normalize(),
                    failure);
        }
    }

    public static Path structuresDirectory(Path configDirectory) throws IOException {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Path requestedConfigDirectory =
                configDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(requestedConfigDirectory);
            Path realConfigDirectory =
                    requestedConfigDirectory.toRealPath();
            if (!Files.isDirectory(
                    realConfigDirectory,
                    LinkOption.NOFOLLOW_LINKS)) {
                throw StructurePoolException.unsafePath(
                        requestedConfigDirectory,
                        "Config path is not a directory");
            }

            Path modDirectory = ensureDirectChildDirectory(
                    realConfigDirectory,
                    AdaptiveStartingStructure.MOD_ID);
            return ensureDirectChildDirectory(
                    modDirectory,
                    STRUCTURES_DIRECTORY);
        } catch (StructurePoolException failure) {
            throw failure;
        } catch (IOException failure) {
            throw StructurePoolException.filesystem(
                    "prepare the structure pool directory",
                    requestedConfigDirectory,
                    failure);
        }
    }

    public static <T> T selectValidated(long worldSeed, List<T> validatedPool) {
        Objects.requireNonNull(validatedPool, "validatedPool");
        List<T> poolSnapshot = List.copyOf(validatedPool);
        return poolSnapshot.get(selectionIndex(worldSeed, poolSnapshot.size()));
    }

    public static StructureDefinition loadAndSelect(
            long worldSeed,
            List<StructureSource> sources,
            ExternalStructureLoader loader) throws StructureLoadException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(loader, "loader");
        List<StructureSource> sourceSnapshot = List.copyOf(sources);
        if (sourceSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Structure source pool must not be empty");
        }

        List<StructureDefinition> definitions = new ArrayList<>(sourceSnapshot.size());
        for (StructureSource source : sourceSnapshot) {
            definitions.add(loader.loadDefinition(source));
        }

        AdaptiveStartingStructure.LOGGER.info(
                "Validated structure pool ({}): {}",
                definitions.size(),
                definitions.stream().map(StructureDefinition::summary).toList());
        StructureDefinition selected = selectValidated(worldSeed, definitions);
        AdaptiveStartingStructure.LOGGER.info(
                "Selected starting structure: {}, sourceSha256={}, placementSha256={}",
                selected.summary(),
                selected.sha256(),
                selected.placementSha256());
        return selected;
    }

    public static int selectionIndex(long worldSeed, int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("Validated structure pool must not be empty");
        }

        long state = worldSeed ^ SELECTION_SALT_V1;
        while (true) {
            state += SPLIT_MIX_GAMMA;
            long candidate = mix64(state) >>> 1;
            long remainder = candidate % poolSize;
            if (candidate + (poolSize - 1L) - remainder >= 0L) {
                return (int) remainder;
            }
        }
    }

    private static List<Path> listCandidates(Path directory) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw StructurePoolException.unsafePath(
                            entry,
                            "Symbolic links are not allowed in the structure pool");
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String fileName = fileName(entry);
                if (!fileName.endsWith(".nbt")) {
                    if (fileName.toLowerCase(Locale.ROOT).endsWith(".nbt")) {
                        throw StructurePoolException.invalidFilename(
                                entry,
                                "the extension must be exactly .nbt");
                    }
                    continue;
                }
                if (!Files.isReadable(entry)) {
                    throw StructurePoolException.unsafePath(
                            entry,
                            "Structure file is not readable");
                }

                Path normalizedEntry = entry.toAbsolutePath().normalize();
                if (!Objects.equals(normalizedEntry.getParent(), directory)) {
                    throw StructurePoolException.unsafePath(
                            entry,
                            "Structure path escapes the pool directory");
                }
                Path realEntry = entry.toRealPath();
                if (!Objects.equals(realEntry.getParent(), directory)) {
                    throw StructurePoolException.unsafePath(
                            entry,
                            "Structure path resolves outside the pool directory");
                }
                candidates.add(realEntry);
            }
        }

        if (Files.isSymbolicLink(directory) || !directory.equals(directory.toRealPath())) {
            throw StructurePoolException.unsafePath(
                    directory,
                    "Structure pool directory changed or became a symbolic link");
        }
        return candidates;
    }

    private static Path ensureDirectChildDirectory(Path parent, String childName) throws IOException {
        Path child = parent.resolve(childName).normalize();
        if (!Objects.equals(child.getParent(), parent)) {
            throw StructurePoolException.unsafePath(
                    child,
                    "Resolved path escapes its parent directory");
        }

        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(child);
            } catch (FileAlreadyExistsException ignored) {
                // Another thread or process created it; validate the resulting entry below.
            }
        }
        if (Files.isSymbolicLink(child)) {
            throw StructurePoolException.unsafePath(
                    child,
                    "Symbolic links are not allowed for the structure pool path");
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            throw StructurePoolException.unsafePath(
                    child,
                    "Expected a directory at structure pool path");
        }

        Path realChild = child.toRealPath();
        if (!Objects.equals(realChild.getParent(), parent)) {
            throw StructurePoolException.unsafePath(
                    child,
                    "Structure pool path resolves outside its parent");
        }
        return realChild;
    }

    private static String fileName(Path path) {
        return path.getFileName().toString();
    }

    private static String idFromFileName(Path path) {
        String fileName = fileName(path);
        return fileName.substring(0, fileName.length() - ".nbt".length());
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return value ^ (value >>> 31);
    }
}
