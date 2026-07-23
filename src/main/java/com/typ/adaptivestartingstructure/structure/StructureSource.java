package com.typ.adaptivestartingstructure.structure;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

public record StructureSource(String id, Path path) {
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+");

    public StructureSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(path, "path");
        if (!VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid structure ID: '" + id + "'");
        }
        path = path.toAbsolutePath().normalize();
    }
}
