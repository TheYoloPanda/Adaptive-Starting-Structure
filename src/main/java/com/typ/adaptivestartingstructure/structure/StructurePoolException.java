package com.typ.adaptivestartingstructure.structure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class StructurePoolException extends IOException {
    private static final int MAX_PUBLIC_LABEL_LENGTH = 128;

    private final Category category;
    private final List<String> publicArguments;

    private StructurePoolException(
            Category category,
            String internalMessage,
            Throwable cause,
            List<String> publicArguments) {
        super(internalMessage, cause);
        this.category = Objects.requireNonNull(
                category,
                "category");
        this.publicArguments = List.copyOf(publicArguments);
    }

    public Category category() {
        return category;
    }

    public List<String> publicArguments() {
        return publicArguments;
    }

    static StructurePoolException emptyPool(Path directory) {
        return create(
                Category.EMPTY_POOL,
                "Structure pool directory does not contain any direct regular .nbt files: "
                        + directory);
    }

    static StructurePoolException duplicateId(
            String rawId,
            Path first,
            Path second) {
        return create(
                Category.DUPLICATE_ID,
                "Duplicate structure ID ignoring case: '"
                        + rawId
                        + "' in "
                        + first.getFileName()
                        + " and "
                        + second.getFileName(),
                safeFileName(first),
                safeFileName(second));
    }

    static StructurePoolException invalidFilename(
            Path path,
            String reason) {
        return create(
                Category.INVALID_FILENAME,
                "Invalid structure filename '"
                        + path.getFileName()
                        + "': "
                        + reason,
                safeFileName(path));
    }

    static StructurePoolException invalidSelectedId(String id) {
        return create(
                Category.INVALID_SELECTED_ID,
                "Invalid selected structure ID: '" + id + "'");
    }

    static StructurePoolException selectedFileUnavailable(
            String id,
            Path selected) {
        return create(
                Category.SELECTED_FILE_UNAVAILABLE,
                "Selected structure file is missing or unsafe: "
                        + selected,
                safeIdentifier(id));
    }

    static StructurePoolException unsafePath(
            Path path,
            String detail) {
        return create(
                Category.UNSAFE_PATH,
                detail + ": " + path,
                safeFileName(path));
    }

    static StructurePoolException filesystem(
            String operation,
            Path path,
            IOException cause) {
        return new StructurePoolException(
                Category.FILESYSTEM,
                "Could not "
                        + operation
                        + " at "
                        + path
                        + ": "
                        + cause.getMessage(),
                Objects.requireNonNull(cause, "cause"),
                List.of());
    }

    private static StructurePoolException create(
            Category category,
            String internalMessage,
            String... publicArguments) {
        return new StructurePoolException(
                category,
                internalMessage,
                null,
                List.of(publicArguments));
    }

    private static String safeFileName(Path path) {
        Objects.requireNonNull(path, "path");
        Path fileName = path.getFileName();
        return safePublicLabel(
                fileName == null ? "" : fileName.toString());
    }

    private static String safeIdentifier(String id) {
        Objects.requireNonNull(id, "id");
        return safePublicLabel(id);
    }

    private static String safePublicLabel(String label) {
        String normalized = label.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains(":")) {
            return "invalid entry";
        }
        if (normalized.length() <= MAX_PUBLIC_LABEL_LENGTH) {
            return normalized;
        }
        return normalized.substring(
                0,
                MAX_PUBLIC_LABEL_LENGTH - 3) + "...";
    }

    public enum Category {
        EMPTY_POOL,
        DUPLICATE_ID,
        INVALID_FILENAME,
        INVALID_SELECTED_ID,
        SELECTED_FILE_UNAVAILABLE,
        UNSAFE_PATH,
        FILESYSTEM
    }
}
