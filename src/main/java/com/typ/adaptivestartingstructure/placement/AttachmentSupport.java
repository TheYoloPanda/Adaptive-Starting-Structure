package com.typ.adaptivestartingstructure.placement;

import java.util.Iterator;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Cursor3D;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Whether a block-attached entity stays attached to blocks that are not in a
 * level yet: a site's planned blocks, or a template on its own. The rules are
 * the {@code survives()} of NeoForge 21.1's item frames, paintings and leash
 * knots, block by block, because those read only their own level.
 *
 * <p>The copy is never stricter than the game. A type whose rule is not
 * copied here, such as another mod's attachment, is not judged; a block whose
 * final state is not known passes; collisions with entities and the world
 * border are left out. The check on the real level after the writes keeps the
 * last word.
 */
final class AttachmentSupport {
    private static final double SUPPORT_OFFSET = -0.5D;
    private static final double SUPPORT_DEFLATION = 1.0E-7;
    private static final double COLLISION_EPSILON = 1.0E-7;
    /* Cursor3D's kinds of cell: on a face, on an edge, on a corner of the box widened by one. */
    private static final int FACE_CELL = 1;
    private static final int EDGE_CELL = 2;
    private static final int CORNER_CELL = 3;

    private AttachmentSupport() {
    }

    /**
     * Refuses the site when a prepared entity would fall off the blocks the
     * site will hold once its terrain and template are written.
     */
    static void requireAttached(
            PreparedTemplateEntities entities,
            BlockGetter blocks) {
        for (int index = 0; index < entities.size(); index++) {
            TemplateEntityPlacementEntry entry = entities.entry(index);
            Optional<String> failure = failure(
                    entities.entity(index),
                    entry.placementNbt(),
                    blocks,
                    position -> true);
            if (failure.isPresent()) {
                throw new UnsuitableGeneratedSiteException(
                        "Template entity at source index "
                                + entry.sourceIndex()
                                + " (type '"
                                + entry.typeId()
                                + "', anchor "
                                + entry.anchor()
                                + ") would not stay attached once the terrain and template are written: "
                                + failure.get());
            }
        }
    }

    /**
     * Why the entity would not stay attached to these blocks, judging only
     * the positions {@code known} accepts; empty when it would stay, or when
     * its type is not one whose rule is copied here.
     */
    static Optional<String> failure(
            Entity entity,
            CompoundTag placementNbt,
            BlockGetter blocks,
            Predicate<BlockPos> known) {
        EntityType<?> type = entity.getType();
        if (entity instanceof ItemFrame frame
                && (type == EntityType.ITEM_FRAME
                        || type == EntityType.GLOW_ITEM_FRAME)) {
            return frameFailure(
                    frame,
                    placementNbt.getBoolean("Fixed"),
                    blocks,
                    known);
        }
        if (entity instanceof Painting painting
                && type == EntityType.PAINTING) {
            return hangingFailure(
                    painting.getBoundingBox(),
                    CollisionContext.of(painting),
                    painting.getDirection(),
                    blocks,
                    known);
        }
        if (entity instanceof LeashFenceKnotEntity knot
                && type == EntityType.LEASH_KNOT) {
            BlockPos fence = knot.getPos();
            if (!known.test(fence)
                    || blocks.getBlockState(fence).is(BlockTags.FENCES)) {
                return Optional.empty();
            }
            return Optional.of("the block it is tied to at " + fence
                    + " is " + blockId(blocks.getBlockState(fence))
                    + ", not a fence");
        }
        return Optional.empty();
    }

    /* survives() itself reads the deprecated isSolid(). */
    @SuppressWarnings("deprecation")
    private static Optional<String> frameFailure(
            ItemFrame frame,
            boolean fixed,
            BlockGetter blocks,
            Predicate<BlockPos> known) {
        if (fixed) {
            return Optional.empty();
        }
        Optional<String> collision = collision(
                frame.getBoundingBox(),
                CollisionContext.of(frame),
                blocks,
                known);
        if (collision.isPresent()) {
            return collision;
        }
        Direction facing = frame.getDirection();
        BlockPos support = frame.getPos().relative(facing.getOpposite());
        if (!known.test(support)) {
            return Optional.empty();
        }
        BlockState state = blocks.getBlockState(support);
        if (state.isSolid()
                || facing.getAxis().isHorizontal()
                        && DiodeBlock.isDiode(state)) {
            return Optional.empty();
        }
        return Optional.of(unsupported(support, state));
    }

    /* HangingEntity.survives, for the hanging entity with this box and facing. */
    @SuppressWarnings("deprecation")
    static Optional<String> hangingFailure(
            AABB box,
            CollisionContext context,
            Direction facing,
            BlockGetter blocks,
            Predicate<BlockPos> known) {
        Optional<String> collision = collision(box, context, blocks, known);
        if (collision.isPresent()) {
            return collision;
        }
        AABB supportBox = box
                .move(
                        facing.getStepX() * SUPPORT_OFFSET,
                        facing.getStepY() * SUPPORT_OFFSET,
                        facing.getStepZ() * SUPPORT_OFFSET)
                .deflate(SUPPORT_DEFLATION);
        Iterator<BlockPos> positions =
                BlockPos.betweenClosedStream(supportBox).iterator();
        while (positions.hasNext()) {
            BlockPos support = positions.next();
            if (!known.test(support)
                    || canSupportCenter(blocks, support, facing)) {
                continue;
            }
            BlockState state = blocks.getBlockState(support);
            if (!state.isSolid() && !DiodeBlock.isDiode(state)) {
                return Optional.of(unsupported(support.immutable(), state));
            }
        }
        return Optional.empty();
    }

    /* Block.canSupportCenter, which takes a LevelReader only to read blocks. */
    private static boolean canSupportCenter(
            BlockGetter blocks,
            BlockPos position,
            Direction direction) {
        BlockState state = blocks.getBlockState(position);
        return !(direction == Direction.DOWN
                        && state.is(BlockTags.UNSTABLE_BOTTOM_CENTER))
                && state.isFaceSturdy(
                        blocks,
                        position,
                        direction,
                        SupportType.CENTER);
    }

    /* The block part of CollisionGetter.noCollision(entity), as BlockCollisions walks it. */
    private static Optional<String> collision(
            AABB box,
            CollisionContext context,
            BlockGetter blocks,
            Predicate<BlockPos> known) {
        VoxelShape entityShape = Shapes.create(box);
        Cursor3D cursor = new Cursor3D(
                Mth.floor(box.minX - COLLISION_EPSILON) - 1,
                Mth.floor(box.minY - COLLISION_EPSILON) - 1,
                Mth.floor(box.minZ - COLLISION_EPSILON) - 1,
                Mth.floor(box.maxX + COLLISION_EPSILON) + 1,
                Mth.floor(box.maxY + COLLISION_EPSILON) + 1,
                Mth.floor(box.maxZ + COLLISION_EPSILON) + 1);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        while (cursor.advance()) {
            int cell = cursor.getNextType();
            if (cell == CORNER_CELL) {
                continue;
            }
            int x = cursor.nextX();
            int y = cursor.nextY();
            int z = cursor.nextZ();
            position.set(x, y, z);
            if (!known.test(position)) {
                continue;
            }
            BlockState state = blocks.getBlockState(position);
            if (cell == FACE_CELL && !state.hasLargeCollisionShape()
                    || cell == EDGE_CELL && !state.is(Blocks.MOVING_PISTON)) {
                continue;
            }
            VoxelShape shape =
                    state.getCollisionShape(blocks, position, context);
            boolean collides = shape == Shapes.block()
                    ? box.intersects(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D)
                    : !shape.isEmpty()
                            && Shapes.joinIsNotEmpty(
                                    shape.move(x, y, z),
                                    entityShape,
                                    BooleanOp.AND);
            if (collides) {
                return Optional.of(blockId(state) + " at "
                        + position.immutable() + " intersects it");
            }
        }
        return Optional.empty();
    }

    private static String unsupported(BlockPos support, BlockState state) {
        return "its support at " + support + " is " + blockId(state)
                + ", which does not hold it";
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
