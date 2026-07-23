package com.typ.adaptivestartingstructure.planner;

public final class GeneratorQueryBudgetExceededException extends RuntimeException {
    private final int maximumQueries;
    private final int queriesUsed;

    GeneratorQueryBudgetExceededException(
            int maximumQueries,
            int queriesUsed,
            String attemptedQuery) {
        super("Generator query budget of " + maximumQueries
                + " exhausted after " + queriesUsed
                + " queries; cannot execute " + attemptedQuery);
        this.maximumQueries = maximumQueries;
        this.queriesUsed = queriesUsed;
    }

    public int maximumQueries() {
        return maximumQueries;
    }

    public int queriesUsed() {
        return queriesUsed;
    }
}
