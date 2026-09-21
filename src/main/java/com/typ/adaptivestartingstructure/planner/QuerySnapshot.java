package com.typ.adaptivestartingstructure.planner;

import java.util.Locale;
import java.util.Objects;

/**
 * What a planning run has asked the world, by kind of question.
 *
 * <p>Planning is bounded by a query budget and its cost is dominated by which
 * kind of query it spends that budget on: a noise column is far dearer than a
 * heightmap sample. Without this split a slow world creation reports one total
 * and leaves nothing to reason from, and a change that claims to make the
 * search cheaper cannot be shown to have done so.
 */
public record QuerySnapshot(
        Count height,
        Count column,
        Count biome,
        Count structure) {

    public static final QuerySnapshot EMPTY = new QuerySnapshot(
            Count.EMPTY,
            Count.EMPTY,
            Count.EMPTY,
            Count.EMPTY);

    public QuerySnapshot {
        Objects.requireNonNull(height, "height");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(structure, "structure");
    }

    /** What happened between an earlier snapshot and this one. */
    public QuerySnapshot since(QuerySnapshot earlier) {
        Objects.requireNonNull(earlier, "earlier");
        return new QuerySnapshot(
                height.since(earlier.height),
                column.since(earlier.column),
                biome.since(earlier.biome),
                structure.since(earlier.structure));
    }

    public int totalCalls() {
        return height.calls()
                + column.calls()
                + biome.calls()
                + structure.calls();
    }

    public long totalNanos() {
        return height.nanos()
                + column.nanos()
                + biome.nanos()
                + structure.nanos();
    }

    public String describe() {
        return "height " + height.describe()
                + ", column " + column.describe()
                + ", biome " + biome.describe()
                + ", structure " + structure.describe();
    }

    public record Count(int calls, int cacheHits, long nanos) {
        public static final Count EMPTY = new Count(0, 0, 0L);

        public Count {
            if (calls < 0 || cacheHits < 0 || nanos < 0L) {
                throw new IllegalArgumentException(
                        "Query counts must not be negative");
            }
        }

        public Count since(Count earlier) {
            Objects.requireNonNull(earlier, "earlier");
            return new Count(
                    calls - earlier.calls,
                    cacheHits - earlier.cacheHits,
                    nanos - earlier.nanos);
        }

        /** Share of asks that a cached answer already covered. */
        public double cacheHitRate() {
            int asks = calls + cacheHits;
            return asks == 0 ? 0.0D : (double) cacheHits / asks;
        }

        public String describe() {
            if (calls == 0 && cacheHits == 0) {
                return "0";
            }
            return calls
                    + " (" + Math.round(cacheHitRate() * 100.0D) + "% cached, "
                    + String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0D)
                    + " ms)";
        }
    }
}
