package com.typ.adaptivestartingstructure.planner;

public final class GeneratorQueryBudgetExceededException extends RuntimeException {
    private final int maximumQueries;
    private final int phaseLimit;
    private final int queriesUsed;

    GeneratorQueryBudgetExceededException(
            int maximumQueries,
            int phaseLimit,
            int queriesUsed,
            String attemptedQuery) {
        super(message(maximumQueries, phaseLimit, queriesUsed, attemptedQuery));
        this.maximumQueries = maximumQueries;
        this.phaseLimit = phaseLimit;
        this.queriesUsed = queriesUsed;
    }

    public int maximumQueries() {
        return maximumQueries;
    }

    /**
     * The ceiling that actually stopped the query, which is the configured
     * budget unless a planning phase reserved part of it for later phases.
     */
    public int phaseLimit() {
        return phaseLimit;
    }

    public int queriesUsed() {
        return queriesUsed;
    }

    private static String message(
            int maximumQueries,
            int phaseLimit,
            int queriesUsed,
            String attemptedQuery) {
        if (phaseLimit >= maximumQueries) {
            return "Generator query budget of " + maximumQueries
                    + " exhausted after " + queriesUsed
                    + " queries; cannot execute " + attemptedQuery;
        }
        return "Generator query phase limit of " + phaseLimit
                + " out of a budget of " + maximumQueries
                + " exhausted after " + queriesUsed
                + " queries; cannot execute " + attemptedQuery;
    }
}
