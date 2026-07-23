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
        int spawnColumnsChecked) {

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
                || spawnColumnsChecked <= 0) {
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
