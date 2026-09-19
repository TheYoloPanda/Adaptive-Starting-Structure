package com.typ.adaptivestartingstructure.placement;

public record TemplateEntityPlacementMetrics(
        int sourceEntities,
        int plannedEntities,
        int skippedByDisabledOption,
        int skippedUnsupportedEntities,
        int materializedEntities,
        int addedEntities) {

    public TemplateEntityPlacementMetrics {
        if (sourceEntities < 0
                || plannedEntities < 0
                || skippedByDisabledOption < 0
                || skippedUnsupportedEntities < 0
                || materializedEntities < 0
                || addedEntities < 0
                || sourceEntities
                        != Math.addExact(
                                plannedEntities,
                                Math.addExact(
                                        skippedByDisabledOption,
                                        skippedUnsupportedEntities))
                || materializedEntities
                        != plannedEntities
                || addedEntities
                        > materializedEntities
                || skippedByDisabledOption > 0
                        && (plannedEntities != 0
                                || skippedUnsupportedEntities
                                        != 0
                                || skippedByDisabledOption
                                        != sourceEntities)) {
            throw new IllegalArgumentException(
                    "Template-entity placement metrics are inconsistent");
        }
    }
}
