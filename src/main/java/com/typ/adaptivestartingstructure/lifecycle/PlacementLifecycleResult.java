package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.placement.WorldFinalizationMetrics;
import java.util.Objects;

record PlacementLifecycleResult(
        Status status,
        WorldFinalizationMetrics metrics) {

    PlacementLifecycleResult {
        status = Objects.requireNonNull(status, "status");
        if ((status == Status.COMPLETED)
                != (metrics != null)) {
            throw new IllegalArgumentException(
                    "Only a completed placement has final metrics");
        }
    }

    static PlacementLifecycleResult noData() {
        return new PlacementLifecycleResult(
                Status.NO_DATA,
                null);
    }

    static PlacementLifecycleResult alreadyComplete() {
        return new PlacementLifecycleResult(
                Status.ALREADY_COMPLETE,
                null);
    }

    static PlacementLifecycleResult completed(
            WorldFinalizationMetrics metrics) {
        return new PlacementLifecycleResult(
                Status.COMPLETED,
                Objects.requireNonNull(metrics, "metrics"));
    }

    enum Status {
        NO_DATA,
        ALREADY_COMPLETE,
        COMPLETED
    }
}
