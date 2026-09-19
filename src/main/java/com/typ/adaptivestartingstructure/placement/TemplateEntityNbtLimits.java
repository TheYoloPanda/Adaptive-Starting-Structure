package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.StructureEntityData;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

final class TemplateEntityNbtLimits {
    static final int MAX_ROOT_ENTITIES = 128;
    static final long MAX_AGGREGATE_BYTES =
            4L * 1024L * 1024L;
    static final int MAX_NESTING_DEPTH = 64;

    private TemplateEntityNbtLimits() {
    }

    static void validate(
            List<StructureEntityData> entities) {
        if (entities.size() > MAX_ROOT_ENTITIES) {
            throw new PlacementPreparationException(
                    "Template contains "
                            + entities.size()
                            + " root entities; the operational maximum is "
                            + MAX_ROOT_ENTITIES);
        }

        CountingOutputStream counter =
                new CountingOutputStream(
                        MAX_AGGREGATE_BYTES);
        try (DataOutputStream output =
                new DataOutputStream(counter)) {
            for (StructureEntityData entity
                    : entities) {
                validateDepth(entity);
                try {
                    NbtIo.write(
                            entity.copyNbt(),
                            output);
                } catch (LimitExceededException failure) {
                    throw new PlacementPreparationException(
                            "Aggregate uncompressed template entity NBT exceeds "
                                    + MAX_AGGREGATE_BYTES
                                    + " bytes at source index "
                                    + entity.sourceIndex(),
                            failure);
                }
            }
        } catch (PlacementPreparationException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new PlacementPreparationException(
                    "Could not measure uncompressed template entity NBT",
                    failure);
        }
    }

    static long encodedSize(
            List<StructureEntityData> entities) {
        CountingOutputStream counter =
                new CountingOutputStream(
                        Long.MAX_VALUE);
        try (DataOutputStream output =
                new DataOutputStream(counter)) {
            for (StructureEntityData entity
                    : entities) {
                NbtIo.write(
                        entity.copyNbt(),
                        output);
            }
        } catch (IOException failure) {
            throw new PlacementPreparationException(
                    "Could not measure uncompressed template entity NBT",
                    failure);
        }
        return counter.count();
    }

    private static void validateDepth(
            StructureEntityData entity) {
        Deque<ContainerDepth> pending =
                new ArrayDeque<>();
        pending.push(new ContainerDepth(
                entity.copyNbt(),
                1));
        while (!pending.isEmpty()) {
            ContainerDepth current =
                    pending.pop();
            if (current.depth
                    > MAX_NESTING_DEPTH) {
                throw new PlacementPreparationException(
                        "Template entity NBT nesting depth exceeds "
                                + MAX_NESTING_DEPTH
                                + " at source index "
                                + entity.sourceIndex());
            }
            if (current.tag
                    instanceof CompoundTag compound) {
                for (String key
                        : compound.getAllKeys()) {
                    pushContainer(
                            pending,
                            compound.get(key),
                            current.depth + 1);
                }
            } else if (current.tag
                    instanceof ListTag list) {
                for (Tag child : list) {
                    pushContainer(
                            pending,
                            child,
                            current.depth + 1);
                }
            }
        }
    }

    private static void pushContainer(
            Deque<ContainerDepth> pending,
            Tag tag,
            int depth) {
        if (tag instanceof CompoundTag
                || tag instanceof ListTag) {
            pending.push(new ContainerDepth(
                    tag,
                    depth));
        }
    }

    private record ContainerDepth(
            Tag tag,
            int depth) {
    }

    private static final class CountingOutputStream
            extends OutputStream {
        private final long maximum;
        private long count;

        private CountingOutputStream(long maximum) {
            this.maximum = maximum;
        }

        @Override
        public void write(int value)
                throws IOException {
            increase(1);
        }

        @Override
        public void write(
                byte[] bytes,
                int offset,
                int length) throws IOException {
            if (offset < 0
                    || length < 0
                    || offset + length
                            > bytes.length) {
                throw new IndexOutOfBoundsException();
            }
            increase(length);
        }

        private void increase(int amount)
                throws LimitExceededException {
            if (amount < 0
                    || count > maximum - amount) {
                throw new LimitExceededException();
            }
            count += amount;
        }

        private long count() {
            return count;
        }
    }

    private static final class LimitExceededException
            extends IOException {
    }
}
