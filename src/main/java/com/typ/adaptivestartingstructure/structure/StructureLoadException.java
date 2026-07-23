package com.typ.adaptivestartingstructure.structure;

import java.nio.file.Path;
import java.util.Objects;

public final class StructureLoadException extends Exception {
    private final Category category;
    private final Path sourcePath;

    StructureLoadException(Category category, Path sourcePath, String message) {
        super(message);
        this.category = Objects.requireNonNull(category, "category");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
    }

    StructureLoadException(Category category, Path sourcePath, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
    }

    public Category category() {
        return category;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public enum Category {
        IO,
        FORMAT,
        DATA_FIXER,
        INVALID_CONTENT
    }
}
