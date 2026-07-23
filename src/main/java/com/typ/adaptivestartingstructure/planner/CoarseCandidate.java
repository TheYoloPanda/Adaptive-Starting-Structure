package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import java.util.Objects;
import net.minecraft.world.level.block.Rotation;

public record CoarseCandidate(
        int centerX,
        int centerZ,
        int ringRadius,
        SearchBand searchBand,
        RotatedStructureView structure,
        int minimumX,
        int maximumX,
        int minimumZ,
        int maximumZ,
        long distanceSquared,
        CoarseCandidateMetrics metrics) {

    public CoarseCandidate {
        if (ringRadius < 0) {
            throw new IllegalArgumentException("ringRadius must be non-negative");
        }
        searchBand = Objects.requireNonNull(searchBand, "searchBand");
        structure = Objects.requireNonNull(structure, "structure");
        metrics = Objects.requireNonNull(metrics, "metrics");
        if (minimumX > maximumX || minimumZ > maximumZ) {
            throw new IllegalArgumentException("Candidate bounds must be ordered");
        }
        if (centerX < minimumX || centerX > maximumX
                || centerZ < minimumZ || centerZ > maximumZ) {
            throw new IllegalArgumentException("Candidate center must be inside its bounds");
        }
        if ((long) maximumX - minimumX + 1L != structure.size().getX()
                || (long) maximumZ - minimumZ + 1L != structure.size().getZ()) {
            throw new IllegalArgumentException(
                    "Candidate bounds must match the rotated structure dimensions");
        }
        if (distanceSquared < 0L) {
            throw new IllegalArgumentException("distanceSquared must be non-negative");
        }
    }

    public Rotation rotation() {
        return structure.rotation();
    }

    public enum SearchBand {
        NEAR,
        FAR
    }
}
