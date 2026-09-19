package com.typ.adaptivestartingstructure.placement;

public record WorldFinalizationMetrics(
        int preparedChunks,
        int modifiedChunks,
        int modifiedColumns,
        int levelingBlockWrites,
        int blendingBlockWrites,
        int templateBlockWrites,
        int shapeCorrectionWrites,
        int blockEntityLoads,
        int heightmapColumnsChecked,
        int heightmapValuesChecked,
        int lightChecks,
        int neighborUpdates,
        int comparatorUpdates,
        int fluidTicks,
        int spawnColumnsChecked,
        int sourceEntities,
        int plannedEntities,
        int skippedByDisabledOption,
        int skippedUnsupportedEntities,
        int materializedEntities,
        int addedEntities) {

    public WorldFinalizationMetrics {
        if (preparedChunks <= 0
                || modifiedChunks < 0
                || modifiedChunks > preparedChunks
                || modifiedColumns < 0
                || levelingBlockWrites < 0
                || blendingBlockWrites < 0
                || templateBlockWrites < 0
                || shapeCorrectionWrites < 0
                || blockEntityLoads < 0
                || heightmapColumnsChecked != modifiedColumns
                || heightmapValuesChecked
                        != heightmapColumnsChecked
                                * HeightmapConsistencyValidator
                                        .TRACKED_TYPES
                                        .size()
                || lightChecks < 0
                || neighborUpdates < 0
                || comparatorUpdates != blockEntityLoads
                || fluidTicks < 0
                || spawnColumnsChecked <= 0
                || sourceEntities < 0
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
                        != materializedEntities
                || skippedByDisabledOption > 0
                        && (plannedEntities != 0
                                || skippedUnsupportedEntities
                                        != 0
                                || skippedByDisabledOption
                                        != sourceEntities)) {
            throw new IllegalArgumentException(
                    "World-finalization metrics are inconsistent");
        }
    }

    public int plannedBlockWrites() {
        return Math.addExact(
                levelingBlockWrites,
                Math.addExact(
                        blendingBlockWrites,
                        templateBlockWrites));
    }

    public int observedBlockWrites() {
        return Math.addExact(
                plannedBlockWrites(),
                shapeCorrectionWrites);
    }
}
