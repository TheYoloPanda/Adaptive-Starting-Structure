package com.typ.adaptivestartingstructure.placement;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import net.minecraft.server.level.ServerLevel;

/**
 * Forgets the Overworld chunks generated under a world spawn that placement
 * is leaving, so that Minecraft generates them again, under the spawn that
 * replaces it, when something next needs them.
 *
 * <p>They unload the way the game unloads any chunk, which releases their
 * lighting, block entities, scheduled ticks and points of interest, but each
 * is marked saved first so that none of them is written. The entities
 * generated in them are removed, and their entries are cleared from the
 * region files in case an earlier start saved them.
 */
public final class TrialTerrainDiscard {
    static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final long TICK_SLICE_NANOS =
            TimeUnit.MILLISECONDS.toNanos(50);
    /*
     * A synchronous chunk lookup leaves a ticket that outlives the first
     * purge after it, so what it loaded unloads only after the second.
     */
    static final int TICKET_PURGES = 2;

    private TrialTerrainDiscard() {
    }

    /**
     * Discards the {@code recorded} chunks still in memory. {@code
     * releaseTickets} must remove the tickets the caller holds on them; a
     * chunk another ticket keeps loaded is left as it is, and counted.
     */
    public static TrialTerrainDiscardResult discard(
            ServerLevel level,
            LongCollection recorded,
            LongSet saved,
            Runnable releaseTickets) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            throw new TrialTerrainDiscardException(
                    "Trial terrain must be discarded on the server thread");
        }
        return discard(
                new ServerTrialChunks(level, releaseTickets),
                recorded,
                saved,
                System::nanoTime);
    }

    static TrialTerrainDiscardResult discard(
            Chunks chunks,
            LongCollection recorded,
            LongSet saved,
            LongSupplier nanoTime) {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(nanoTime, "nanoTime");
        long started = nanoTime.getAsLong();
        long deadline = started + TIMEOUT_NANOS;
        /* A chunk the chunk map took on since its last update is visible only after the next one. */
        chunks.runPendingWork(deadline);
        LongList loaded = matching(recorded, chunks::inMemory);
        if (loaded.isEmpty()) {
            return TrialTerrainDiscardResult.nothingLoaded(
                    nanoTime.getAsLong() - started);
        }
        markSavedWhileInMemory(chunks, loaded);
        /*
         * Generation already running finishes with every ticket in place: a
         * chunk released first could still turn complete afterwards and add
         * the animals it spawns once the entities below were collected.
         */
        while (anyMatch(loaded, chunks::generating)) {
            requireTime(
                    nanoTime,
                    deadline,
                    count(loaded, chunks::generating),
                    loaded.size(),
                    "generation already running in them");
            chunks.runPendingWork(deadline);
        }
        long settled = nanoTime.getAsLong();
        LongSet full = new LongOpenHashSet(
                matching(loaded, chunks::full));
        List<TrialEntity> entities =
                chunks.entitiesIn(new LongOpenHashSet(loaded));
        chunks.releaseTickets();
        LongPredicate unloading = chunk ->
                chunks.inMemory(chunk) && !chunks.held(chunk);
        int ticks = 0;
        boolean drained;
        do {
            requireTime(
                    nanoTime,
                    deadline,
                    count(loaded, unloading),
                    loaded.size(),
                    "unloading");
            markSavedWhileInMemory(chunks, loaded);
            drained = chunks.tick(Math.min(
                    deadline,
                    nanoTime.getAsLong() + TICK_SLICE_NANOS));
            ticks++;
            chunks.runPendingWork(deadline);
        } while (ticks < TICKET_PURGES
                || !drained
                || anyMatch(loaded, unloading));
        LongList unloaded =
                matching(loaded, chunk -> !chunks.inMemory(chunk));
        int removedEntities = removeEntitiesIn(
                entities,
                new LongOpenHashSet(unloaded));
        erase(chunks, unloaded, nanoTime, deadline);
        return new TrialTerrainDiscardResult(
                unloaded.size(),
                count(unloaded, full::contains),
                count(unloaded, saved::contains),
                removedEntities,
                loaded.size() - unloaded.size(),
                nanoTime.getAsLong() - started,
                settled - started,
                ticks);
    }

    private static void markSavedWhileInMemory(
            Chunks chunks,
            LongList loaded) {
        for (int index = 0; index < loaded.size(); index++) {
            long chunk = loaded.getLong(index);
            if (chunks.inMemory(chunk)) {
                chunks.markSaved(chunk);
            }
        }
    }

    private static int removeEntitiesIn(
            List<TrialEntity> entities,
            LongSet unloaded) {
        int removed = 0;
        for (TrialEntity entity : entities) {
            if (unloaded.contains(entity.chunk())) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private static void erase(
            Chunks chunks,
            LongList unloaded,
            LongSupplier nanoTime,
            long deadline) {
        List<CompletableFuture<Void>> erasures =
                new ArrayList<>(unloaded.size());
        for (int index = 0; index < unloaded.size(); index++) {
            erasures.add(chunks.erase(unloaded.getLong(index)));
        }
        try {
            CompletableFuture.allOf(
                            erasures.toArray(CompletableFuture[]::new))
                    .get(
                            Math.max(0L, deadline - nanoTime.getAsLong()),
                            TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TrialTerrainDiscardException(
                    "Interrupted while clearing discarded chunks from the region files",
                    interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new TrialTerrainDiscardException(
                    "Could not clear " + unloaded.size()
                            + " discarded chunks from the region files",
                    failure);
        }
    }

    private static void requireTime(
            LongSupplier nanoTime,
            long deadline,
            int waiting,
            int loaded,
            String what) {
        if (nanoTime.getAsLong() > deadline) {
            throw new TrialTerrainDiscardException(
                    "Could not discard the terrain generated under the world spawn being left within "
                            + TimeUnit.NANOSECONDS.toSeconds(TIMEOUT_NANOS)
                            + " s: " + waiting + " of its " + loaded
                            + " chunks were still waiting on " + what);
        }
    }

    private static LongList matching(
            LongCollection chunks,
            LongPredicate test) {
        LongList matching = new LongArrayList();
        LongIterator iterator = chunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (test.test(chunk)) {
                matching.add(chunk);
            }
        }
        return matching;
    }

    private static boolean anyMatch(LongList chunks, LongPredicate test) {
        for (int index = 0; index < chunks.size(); index++) {
            if (test.test(chunks.getLong(index))) {
                return true;
            }
        }
        return false;
    }

    private static int count(LongList chunks, LongPredicate test) {
        int count = 0;
        for (int index = 0; index < chunks.size(); index++) {
            if (test.test(chunks.getLong(index))) {
                count++;
            }
        }
        return count;
    }

    /** The chunk system a discard drives; chunks are packed chunk positions. */
    interface Chunks {
        boolean inMemory(long chunk);

        /** Whether a generation task still claims the chunk. */
        boolean generating(long chunk);

        /** Whether a ticket keeps the chunk loaded. */
        boolean held(long chunk);

        boolean full(long chunk);

        void markSaved(long chunk);

        List<TrialEntity> entitiesIn(LongSet chunks);

        void releaseTickets();

        /** Runs what worker threads handed back to the server thread, or waits briefly for it. */
        void runPendingWork(long deadlineNanos);

        /**
         * Runs one chunk tick within the slice; true when it ended before the
         * slice did, which means it emptied its unload queue.
         */
        boolean tick(long sliceEndNanos);

        CompletableFuture<Void> erase(long chunk);
    }

    interface TrialEntity {
        long chunk();

        void remove();
    }
}
