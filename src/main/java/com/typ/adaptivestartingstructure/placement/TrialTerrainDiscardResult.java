package com.typ.adaptivestartingstructure.placement;

/**
 * {@code savedChunks} counts the discarded chunks an earlier start had saved
 * complete, and {@code keptChunks} the chunks left loaded because another
 * ticket holds them.
 */
public record TrialTerrainDiscardResult(
        int discardedChunks,
        int fullChunks,
        int savedChunks,
        int removedEntities,
        int keptChunks,
        long elapsedNanos,
        long settleNanos,
        int ticks) {
    static TrialTerrainDiscardResult nothingLoaded(long elapsedNanos) {
        return new TrialTerrainDiscardResult(
                0,
                0,
                0,
                0,
                0,
                elapsedNanos,
                0L,
                0);
    }
}
