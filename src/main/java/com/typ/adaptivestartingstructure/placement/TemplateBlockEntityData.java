package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public final class TemplateBlockEntityData {
    private final BlockPos position;
    private final BlockState blockState;
    private final ResourceLocation typeId;
    private final CompoundTag nbt;
    private final Long lootSeed;

    TemplateBlockEntityData(
            BlockPos position,
            BlockState blockState,
            ResourceLocation typeId,
            CompoundTag nbt,
            Long lootSeed) {
        this.position =
                Objects.requireNonNull(position, "position").immutable();
        this.blockState =
                Objects.requireNonNull(blockState, "blockState");
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.nbt = Objects.requireNonNull(nbt, "nbt").copy();
        this.lootSeed = lootSeed;
        if (lootSeed != null
                && (lootSeed == 0L
                        || !this.nbt.contains(
                                "LootTable",
                                CompoundTag.TAG_STRING)
                        || this.nbt.getLong("LootTableSeed")
                                != lootSeed)) {
            throw new IllegalArgumentException(
                    "Deterministic loot seed does not match BlockEntity NBT");
        }
    }

    public BlockPos position() {
        return position;
    }

    public BlockState blockState() {
        return blockState;
    }

    public ResourceLocation typeId() {
        return typeId;
    }

    public CompoundTag nbt() {
        return nbt.copy();
    }

    public Long lootSeed() {
        return lootSeed;
    }
}
