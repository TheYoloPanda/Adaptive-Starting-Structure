package com.typ.adaptivestartingstructure.planner;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public final class TerrainColumn {
    private final int minY;
    private final BlockState[] states;

    public TerrainColumn(int minY, BlockState[] states) {
        this.minY = minY;
        this.states = states.clone();
        if (this.states.length == 0) {
            throw new IllegalArgumentException("Terrain column must contain at least one state");
        }
        if (Arrays.stream(this.states).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Terrain column must not contain null states");
        }
    }

    static TerrainColumn copyOf(NoiseColumn column, int minY, int maxYExclusive) {
        if (maxYExclusive <= minY) {
            throw new IllegalArgumentException("Terrain column height range must be positive");
        }
        BlockState[] states = new BlockState[maxYExclusive - minY];
        for (int y = minY; y < maxYExclusive; y++) {
            states[y - minY] = column.getBlock(y);
        }
        return new TerrainColumn(minY, states);
    }

    public int minY() {
        return minY;
    }

    public int maxYExclusive() {
        return minY + states.length;
    }

    public int height() {
        return states.length;
    }

    public boolean containsY(int y) {
        return y >= minY && y < maxYExclusive();
    }

    public BlockState blockState(int y) {
        if (!containsY(y)) {
            throw new IndexOutOfBoundsException(
                    "Y " + y + " is outside terrain column ["
                            + minY + ", " + maxYExclusive() + ")");
        }
        return states[y - minY];
    }

    /**
     * The height the generator's own query for {@code heightmap} returns for
     * this column: one above the highest block the heightmap counts, or the
     * bottom of the column when it counts none. It has to match that query
     * exactly, since planning mixes heights read either way.
     */
    public int surfaceHeight(Heightmap.Types heightmap) {
        Predicate<BlockState> counted = heightmap.isOpaque();
        for (int index = states.length - 1; index >= 0; index--) {
            if (counted.test(states[index])) {
                return minY + index + 1;
            }
        }
        return minY;
    }
}
