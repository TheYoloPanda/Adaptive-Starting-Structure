package com.typ.adaptivestartingstructure.placement;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

public record TemplateEntityPlacementEntry(
        int sourceIndex,
        ResourceLocation typeId,
        EntityType<?> type,
        Vec3 worldPosition,
        BlockPos anchor,
        CompoundTag placementNbt) {

    public TemplateEntityPlacementEntry {
        if (sourceIndex < 0) {
            throw new IllegalArgumentException(
                    "Entity source index must not be negative");
        }
        typeId = Objects.requireNonNull(typeId, "typeId");
        type = Objects.requireNonNull(type, "type");
        worldPosition = Objects.requireNonNull(
                worldPosition,
                "worldPosition");
        if (!Double.isFinite(worldPosition.x)
                || !Double.isFinite(worldPosition.y)
                || !Double.isFinite(worldPosition.z)) {
            throw new IllegalArgumentException(
                    "Entity world position must be finite");
        }
        anchor = Objects.requireNonNull(
                        anchor,
                        "anchor")
                .immutable();
        placementNbt = Objects.requireNonNull(
                        placementNbt,
                        "placementNbt")
                .copy();
    }

    @Override
    public CompoundTag placementNbt() {
        return placementNbt.copy();
    }
}
