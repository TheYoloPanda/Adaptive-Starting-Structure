package com.typ.adaptivestartingstructure.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class TemplatePlacementApplier {
    private static final int PLACEMENT_FLAGS =
            Block.UPDATE_CLIENTS
                    | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS;

    private TemplatePlacementApplier() {
    }

    static int apply(
            ServerLevel level,
            TemplatePlacementPlan plan) {
        return apply(new ServerWorldAccess(level), plan);
    }

    static int apply(
            WorldAccess world,
            TemplatePlacementPlan plan) {
        for (TemplateBlockWrite write : plan.writes()) {
            if (!world.blockState(write.position())
                    .equals(write.originalState())) {
                throw new PlacementPreparationException(
                        "World changed before template placement at "
                                + write.position());
            }
        }

        int applied = 0;
        for (TemplateBlockWrite write : plan.writes()) {
            /*
             * Minecraft refuses a write that changes nothing. A block an
             * earlier write of this pass took away, such as a vine whose
             * support went first, already holds its planned state; only a
             * refusal that leaves anything else fails the placement.
             */
            if (!world.setBlock(
                            write.position(),
                            write.targetState())
                    && !world.blockState(write.position())
                            .equals(write.targetState())) {
                throw new PlacementPreparationException(
                        "World rejected template write at "
                                + write.position()
                                + ": planned " + write.originalState()
                                + " -> " + write.targetState()
                                + ", found " + world.blockState(write.position()));
            }
            applied++;
        }
        for (TemplateBlockEntityData data
                : plan.blockEntities()) {
            if (!world.blockState(data.position())
                    .equals(data.blockState())) {
                throw new PlacementPreparationException(
                        "BlockEntity state changed during template placement at "
                                + data.position());
            }
            world.loadBlockEntity(
                    data.position(),
                    data.typeId(),
                    data.nbt());
        }
        return applied;
    }

    interface WorldAccess
            extends TemplatePlacementPlanner.BlockStateReader {
        boolean setBlock(
                BlockPos position,
                BlockState targetState);

        void loadBlockEntity(
                BlockPos position,
                ResourceLocation typeId,
                CompoundTag nbt);
    }

    private record ServerWorldAccess(ServerLevel level)
            implements WorldAccess {
        @Override
        public BlockState blockState(BlockPos position) {
            return level.getBlockState(position);
        }

        @Override
        public boolean setBlock(
                BlockPos position,
                BlockState targetState) {
            return level.setBlock(
                    position,
                    targetState,
                    PLACEMENT_FLAGS);
        }

        @Override
        public void loadBlockEntity(
                BlockPos position,
                ResourceLocation typeId,
                CompoundTag nbt) {
            BlockEntity blockEntity =
                    level.getBlockEntity(position);
            ResourceLocation actualType = blockEntity == null
                    ? null
                    : BuiltInRegistries.BLOCK_ENTITY_TYPE
                            .getKey(blockEntity.getType());
            if (blockEntity == null
                    || !typeId.equals(actualType)) {
                throw new PlacementPreparationException(
                        "Expected BlockEntity " + typeId
                                + " was not created at " + position);
            }
            blockEntity.loadWithComponents(
                    nbt,
                    level.registryAccess());
            level.blockEntityChanged(position);
        }
    }
}
