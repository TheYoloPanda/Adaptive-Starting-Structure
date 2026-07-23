package com.typ.adaptivestartingstructure.structure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public final class StructureElement {
    private final BlockPos position;
    private final List<BlockState> paletteStates;
    private final Kind kind;
    private final CompoundTag blockEntityNbt;

    StructureElement(
            BlockPos position,
            List<BlockState> paletteStates,
            Kind kind,
            CompoundTag blockEntityNbt) {
        this.position = Objects.requireNonNull(position, "position").immutable();
        this.paletteStates = List.copyOf(paletteStates);
        if (this.paletteStates.isEmpty()) {
            throw new IllegalArgumentException("paletteStates must not be empty");
        }
        this.kind = Objects.requireNonNull(kind, "kind");
        this.blockEntityNbt = blockEntityNbt == null ? null : blockEntityNbt.copy();
    }

    public BlockPos position() {
        return position;
    }

    public List<BlockState> paletteStates() {
        return paletteStates;
    }

    public BlockState stateForPalette(int paletteIndex) {
        return paletteStates.get(paletteIndex);
    }

    public Kind kind() {
        return kind;
    }

    public Optional<CompoundTag> blockEntityNbt() {
        return blockEntityNbt == null
                ? Optional.empty()
                : Optional.of(blockEntityNbt.copy());
    }

    CompoundTag copyBlockEntityNbtOrNull() {
        return blockEntityNbt == null ? null : blockEntityNbt.copy();
    }

    public enum Kind {
        BLOCK,
        EXPLICIT_AIR
    }
}
