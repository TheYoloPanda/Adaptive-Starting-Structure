package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.Objects;
import net.minecraft.core.BlockPos;

public record FineCandidatePlan(
        int groundSurfaceY,
        BlockPos placementOrigin,
        StructureBounds structureBounds,
        int minimumBlendX,
        int maximumBlendX,
        int minimumBlendZ,
        int maximumBlendZ) {

    public FineCandidatePlan {
        placementOrigin = Objects.requireNonNull(placementOrigin, "placementOrigin").immutable();
        structureBounds = Objects.requireNonNull(structureBounds, "structureBounds");
        if (minimumBlendX > maximumBlendX || minimumBlendZ > maximumBlendZ) {
            throw new IllegalArgumentException("Blend bounds must be ordered");
        }
    }
}
