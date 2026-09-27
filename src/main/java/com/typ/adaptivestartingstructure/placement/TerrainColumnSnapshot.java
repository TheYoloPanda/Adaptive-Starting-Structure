package com.typ.adaptivestartingstructure.placement;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class TerrainColumnSnapshot {
    private final int x;
    private final int z;
    private final int groundY;
    private final int surfaceY;
    private final int minimumCapturedY;
    private final List<BlockState> states;
    private final BlockState surfaceMaterial;
    private final BlockState fillerMaterial;
    private final BlockState deepMaterial;
    private final List<BlockPos> fluidPositions;
    private final List<BlockPos> vegetationPositions;

    TerrainColumnSnapshot(
            int x,
            int z,
            int groundY,
            int surfaceY,
            int minimumCapturedY,
            List<BlockState> states,
            BlockState surfaceMaterial,
            BlockState fillerMaterial,
            BlockState deepMaterial,
            List<BlockPos> fluidPositions,
            List<BlockPos> vegetationPositions) {
        this.x = x;
        this.z = z;
        this.groundY = groundY;
        this.surfaceY = surfaceY;
        this.minimumCapturedY = minimumCapturedY;
        this.states = List.copyOf(states);
        this.surfaceMaterial =
                Objects.requireNonNull(surfaceMaterial, "surfaceMaterial");
        this.fillerMaterial =
                Objects.requireNonNull(fillerMaterial, "fillerMaterial");
        this.deepMaterial =
                Objects.requireNonNull(deepMaterial, "deepMaterial");
        this.fluidPositions =
                immutablePositions("fluidPositions", fluidPositions);
        this.vegetationPositions =
                immutablePositions(
                        "vegetationPositions",
                        vegetationPositions);

        if (this.states.isEmpty()
                || this.states.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Captured terrain states must not be empty or contain null");
        }
        if (groundY < minimumCapturedY
                || groundY >= maximumCapturedYExclusive()
                || surfaceY < groundY
                || surfaceY >= maximumCapturedYExclusive()) {
            throw new IllegalArgumentException(
                    "Captured terrain heights are outside the state range");
        }
        if (!stateAt(groundY).equals(surfaceMaterial)) {
            throw new IllegalArgumentException(
                    "Surface material must match the captured ground state");
        }
        validatePositions("fluidPositions", this.fluidPositions, false);
        validatePositions(
                "vegetationPositions",
                this.vegetationPositions,
                true);
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public int groundY() {
        return groundY;
    }

    public int surfaceY() {
        return surfaceY;
    }

    public int minimumCapturedY() {
        return minimumCapturedY;
    }

    public int maximumCapturedYExclusive() {
        return Math.addExact(minimumCapturedY, states.size());
    }

    public int capturedBlockCount() {
        return states.size();
    }

    public List<BlockState> states() {
        return states;
    }

    public BlockState stateAt(int y) {
        if (y < minimumCapturedY
                || y >= maximumCapturedYExclusive()) {
            throw new IndexOutOfBoundsException(
                    "Y " + y + " is outside captured column ["
                            + minimumCapturedY + ", "
                            + maximumCapturedYExclusive() + ")");
        }
        return states.get(y - minimumCapturedY);
    }

    public BlockState surfaceMaterial() {
        return surfaceMaterial;
    }

    /**
     * The block resting on the ground. The capture stops at the column's
     * surface, and everything from the surface up is air.
     */
    public BlockState stateAboveGround() {
        int y = groundY + 1;
        return y < maximumCapturedYExclusive()
                ? stateAt(y)
                : Blocks.AIR.defaultBlockState();
    }

    public BlockState fillerMaterial() {
        return fillerMaterial;
    }

    public BlockState deepMaterial() {
        return deepMaterial;
    }

    public List<BlockPos> fluidPositions() {
        return fluidPositions;
    }

    public List<BlockPos> vegetationPositions() {
        return vegetationPositions;
    }

    private static List<BlockPos> immutablePositions(
            String name,
            List<BlockPos> positions) {
        Objects.requireNonNull(positions, name);
        return positions.stream()
                .map(position -> Objects.requireNonNull(position, name)
                        .immutable())
                .toList();
    }

    private void validatePositions(
            String name,
            List<BlockPos> positions,
            boolean requireAboveGround) {
        Set<BlockPos> distinct = new HashSet<>();
        for (BlockPos position : positions) {
            if (position.getX() != x
                    || position.getZ() != z
                    || position.getY() < minimumCapturedY
                    || position.getY()
                            >= maximumCapturedYExclusive()
                    || requireAboveGround
                            && position.getY() <= groundY
                    || !distinct.add(position)) {
                throw new IllegalArgumentException(
                        name + " contains an invalid captured position");
            }
        }
    }
}
