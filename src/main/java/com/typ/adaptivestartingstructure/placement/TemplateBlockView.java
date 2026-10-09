package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureElement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * A template's own blocks in one palette, at its own coordinates, before it
 * lands anywhere. Only the cells it writes are known: explicit air and the
 * markers it clears read as air, every other position reads as air too but
 * belongs to whatever site the template lands on.
 */
final class TemplateBlockView implements BlockGetter {
    /* Wide enough for any template and the cell around it; nothing here reads the level's limits. */
    private static final int MINIMUM_Y = -2048;
    private static final int HEIGHT = 4096;

    private final RotatedStructureView structure;
    private final int palette;

    TemplateBlockView(RotatedStructureView structure, int palette) {
        this.structure = structure;
        this.palette = palette;
    }

    boolean knows(BlockPos position) {
        return structure.cellKindAt(position).isPresent();
    }

    @Override
    public BlockState getBlockState(BlockPos position) {
        return structure.elementAt(position)
                .filter(element -> element.kind() == StructureElement.Kind.BLOCK)
                .map(element -> element.stateForPalette(palette))
                .orElse(Blocks.AIR.defaultBlockState());
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos position) {
        return null;
    }

    @Override
    public FluidState getFluidState(BlockPos position) {
        return getBlockState(position).getFluidState();
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public int getMinBuildHeight() {
        return MINIMUM_Y;
    }
}
