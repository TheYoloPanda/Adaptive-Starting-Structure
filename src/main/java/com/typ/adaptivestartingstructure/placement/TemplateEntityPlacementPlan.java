package com.typ.adaptivestartingstructure.placement;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TemplateEntityPlacementPlan {
    private final String structureId;
    private final int sourceEntities;
    private final int skippedByDisabledOption;
    private final List<TemplateEntityPlacementEntry> entries;
    private final List<TemplateEntitySkip> skippedEntities;

    TemplateEntityPlacementPlan(
            String structureId,
            int sourceEntities,
            int skippedByDisabledOption,
            List<TemplateEntityPlacementEntry> entries,
            List<TemplateEntitySkip> skippedEntities) {
        this.structureId = Objects.requireNonNull(
                structureId,
                "structureId");
        if (structureId.isBlank()
                || sourceEntities < 0
                || skippedByDisabledOption < 0) {
            throw new IllegalArgumentException(
                    "Entity placement plan identity or counts are invalid");
        }
        this.sourceEntities = sourceEntities;
        this.skippedByDisabledOption =
                skippedByDisabledOption;
        this.entries = List.copyOf(entries);
        this.skippedEntities = List.copyOf(
                skippedEntities);
        if (sourceEntities
                != Math.addExact(
                        this.entries.size(),
                        Math.addExact(
                                skippedByDisabledOption,
                                this.skippedEntities.size()))) {
            throw new IllegalArgumentException(
                    "Entity placement plan counts do not cover every source entry");
        }
        if (skippedByDisabledOption > 0
                && (!this.entries.isEmpty()
                        || !this.skippedEntities.isEmpty()
                        || skippedByDisabledOption
                                != sourceEntities)) {
            throw new IllegalArgumentException(
                    "Disabled entity placement must skip the complete source list");
        }
        Set<Integer> sourceIndexes = new HashSet<>();
        for (TemplateEntityPlacementEntry entry
                : this.entries) {
            if (!sourceIndexes.add(entry.sourceIndex())
                    || entry.sourceIndex()
                            >= sourceEntities) {
                throw new IllegalArgumentException(
                        "Entity plan contains an invalid or duplicate source index");
            }
        }
        for (TemplateEntitySkip skip
                : this.skippedEntities) {
            if (!sourceIndexes.add(skip.sourceIndex())
                    || skip.sourceIndex()
                            >= sourceEntities) {
                throw new IllegalArgumentException(
                        "Entity skip contains an invalid or duplicate source index");
            }
        }
    }

    public String structureId() {
        return structureId;
    }

    public int sourceEntities() {
        return sourceEntities;
    }

    public int plannedEntities() {
        return entries.size();
    }

    public int skippedByDisabledOption() {
        return skippedByDisabledOption;
    }

    public int skippedUnsupportedEntities() {
        return skippedEntities.size();
    }

    public List<TemplateEntityPlacementEntry> entries() {
        return entries;
    }

    public List<TemplateEntitySkip> skippedEntities() {
        return skippedEntities;
    }
}
