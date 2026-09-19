package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;
import java.util.Optional;

public record TemplateEntitySkip(
        int sourceIndex,
        Optional<String> authoredId,
        String reason) {

    public TemplateEntitySkip {
        if (sourceIndex < 0) {
            throw new IllegalArgumentException(
                    "Entity source index must not be negative");
        }
        authoredId = Objects.requireNonNull(
                authoredId,
                "authoredId");
        reason = Objects.requireNonNull(
                reason,
                "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Entity skip reason must not be blank");
        }
    }
}
