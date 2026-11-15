package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.Objects;

/** A horizontal box, inclusive on every side, where no site may be planned. */
public record ExcludedArea(
        int minimumX,
        int maximumX,
        int minimumZ,
        int maximumZ) {

    public ExcludedArea {
        if (minimumX > maximumX || minimumZ > maximumZ) {
            throw new IllegalArgumentException(
                    "Excluded area minimum must not exceed maximum");
        }
    }

    public static ExcludedArea covering(StructureBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return new ExcludedArea(
                bounds.minimum().getX(),
                bounds.maximum().getX(),
                bounds.minimum().getZ(),
                bounds.maximum().getZ());
    }

    /**
     * Whether the given box and this area overlap once each is widened by
     * {@code margin} on every side.
     */
    boolean overlaps(
            long otherMinimumX,
            long otherMaximumX,
            long otherMinimumZ,
            long otherMaximumZ,
            int margin) {
        long gap = 2L * margin;
        return otherMinimumX <= maximumX + gap
                && minimumX <= otherMaximumX + gap
                && otherMinimumZ <= maximumZ + gap
                && minimumZ <= otherMaximumZ + gap;
    }
}
