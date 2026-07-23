package com.typ.adaptivestartingstructure.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

final class ChunkTicketLease implements AutoCloseable {
    private static final TicketType<ChunkPos> PREPARATION_TICKET =
            TicketType.create(
                    "adaptive_starting_structure:placement_preparation",
                    Comparator.comparingLong(ChunkPos::toLong));

    private final TicketController controller;
    private final List<ChunkPos> ticketedChunks;
    private boolean closed;

    private ChunkTicketLease(
            TicketController controller,
            List<ChunkPos> ticketedChunks) {
        this.controller = controller;
        this.ticketedChunks = List.copyOf(ticketedChunks);
    }

    static ChunkTicketLease acquire(
            ServerLevel level,
            List<ChunkPos> chunks) {
        Objects.requireNonNull(level, "level");
        ServerChunkCache source = level.getChunkSource();
        return acquire(chunks, new TicketController() {
            @Override
            public void add(ChunkPos chunk) {
                source.addRegionTicket(
                        PREPARATION_TICKET,
                        chunk,
                        0,
                        chunk);
            }

            @Override
            public void loadFull(ChunkPos chunk) {
                Object loaded = source.getChunk(
                        chunk.x,
                        chunk.z,
                        ChunkStatus.FULL,
                        true);
                if (!(loaded instanceof LevelChunk)) {
                    throw new PlacementPreparationException(
                            "Chunk " + chunk + " did not reach FULL status");
                }
            }

            @Override
            public void remove(ChunkPos chunk) {
                source.removeRegionTicket(
                        PREPARATION_TICKET,
                        chunk,
                        0,
                        chunk);
            }
        });
    }

    static ChunkTicketLease acquire(
            List<ChunkPos> chunks,
            TicketController controller) {
        List<ChunkPos> requested = List.copyOf(chunks);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one placement chunk is required");
        }
        if (requested.stream().distinct().count() != requested.size()) {
            throw new IllegalArgumentException(
                    "Placement chunks must not contain duplicates");
        }
        Objects.requireNonNull(controller, "controller");

        List<ChunkPos> ticketed = new ArrayList<>(requested.size());
        try {
            for (ChunkPos chunk : requested) {
                controller.add(chunk);
                ticketed.add(chunk);
            }
            for (ChunkPos chunk : requested) {
                controller.loadFull(chunk);
            }
            return new ChunkTicketLease(controller, ticketed);
        } catch (RuntimeException | Error failure) {
            release(controller, ticketed, failure);
            throw failure;
        }
    }

    List<ChunkPos> chunks() {
        return ticketedChunks;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException releaseFailure =
                release(controller, ticketedChunks, null);
        if (releaseFailure != null) {
            throw releaseFailure;
        }
    }

    private static RuntimeException release(
            TicketController controller,
            List<ChunkPos> chunks,
            Throwable originalFailure) {
        RuntimeException releaseFailure = null;
        for (int index = chunks.size() - 1; index >= 0; index--) {
            try {
                controller.remove(chunks.get(index));
            } catch (RuntimeException exception) {
                if (originalFailure != null) {
                    originalFailure.addSuppressed(exception);
                } else if (releaseFailure == null) {
                    releaseFailure = exception;
                } else {
                    releaseFailure.addSuppressed(exception);
                }
            }
        }
        return releaseFailure;
    }

    interface TicketController {
        void add(ChunkPos chunk);

        void loadFull(ChunkPos chunk);

        void remove(ChunkPos chunk);
    }
}
