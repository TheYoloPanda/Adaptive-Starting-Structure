package com.typ.adaptivestartingstructure.planner;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Where a planning run spent its time and its query budget.
 *
 * <p>The plan sets targets for planning: under five seconds, a warning past
 * ten. Nothing measured against them, so a slow world creation reported one
 * total and a change that claimed to make the search cheaper could not be
 * shown to have done so. This records the split the targets are actually
 * about: which phase, and which kind of query.
 *
 * <p>Phases repeat, once per search band, and their figures accumulate under
 * the same name.
 */
final class PlanningTelemetry {
    private final PlannerQueryContext queries;
    private final Map<String, Phase> phases = new LinkedHashMap<>();
    private QuerySnapshot mark = QuerySnapshot.EMPTY;
    private long markNanos;

    /*
     * Reads the real clock rather than one the caller supplies. A phase
     * duration is an observation, and a fake clock would only let a test
     * assert a duration it invented itself.
     */
    PlanningTelemetry(PlannerQueryContext queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    void begin() {
        mark = queries.snapshot();
        markNanos = System.nanoTime();
    }

    void end(String phase) {
        Objects.requireNonNull(phase, "phase");
        QuerySnapshot spent = queries.snapshot().since(mark);
        long elapsed = Math.max(0L, System.nanoTime() - markNanos);
        phases.computeIfAbsent(phase, Phase::new).add(spent, elapsed);
    }

    /**
     * One line naming the total, the split by phase and the split by query
     * kind, in that order: the total says whether to care, the phases say
     * where to look, the kinds say what to do about it.
     */
    String describe(int acceptedSites, long totalNanos) {
        StringBuilder line = new StringBuilder()
                .append("Starting-structure planning: ")
                .append(millis(totalNanos))
                .append(" ms, ")
                .append(queries.queriesUsed())
                .append(" of ")
                .append(queries.maximumQueries())
                .append(" queries, ")
                .append(acceptedSites)
                .append(" site(s) validated, search v")
                .append(SitePlanner.SEARCH_ALGORITHM_VERSION);
        if (!phases.isEmpty()) {
            line.append("; by phase: ");
            boolean first = true;
            for (Phase phase : phases.values()) {
                if (!first) {
                    line.append(", ");
                }
                first = false;
                line.append(phase.describe());
            }
        }
        return line
                .append("; by query: ")
                .append(queries.snapshot().describe())
                .toString();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0D);
    }

    private static final class Phase {
        private final String name;
        private int calls;
        private long nanos;

        private Phase(String name) {
            this.name = name;
        }

        private void add(QuerySnapshot spent, long elapsed) {
            calls += spent.totalCalls();
            nanos += elapsed;
        }

        private String describe() {
            return name + " " + millis(nanos) + " ms / " + calls + " queries";
        }
    }
}
