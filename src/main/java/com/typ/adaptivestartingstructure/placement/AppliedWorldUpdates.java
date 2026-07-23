package com.typ.adaptivestartingstructure.placement;

record AppliedWorldUpdates(
        int shapeCorrectionWrites,
        int lightChecks,
        int neighborUpdates,
        int comparatorUpdates,
        int fluidTicks) {

    AppliedWorldUpdates {
        if (shapeCorrectionWrites < 0
                || lightChecks < 0
                || neighborUpdates < 0
                || comparatorUpdates < 0
                || fluidTicks < 0) {
            throw new IllegalArgumentException(
                    "Applied world-update counts must not be negative");
        }
    }
}
