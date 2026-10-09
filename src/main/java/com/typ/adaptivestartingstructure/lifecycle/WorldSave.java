package com.typ.adaptivestartingstructure.lifecycle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Saves the world and confirms that level.dat holds the world spawn.
 *
 * <p>Minecraft cannot say whether its save reached level.dat: the write logs
 * every failure and swallows it, and {@code saveAllChunks} returns true
 * whenever a level exists. A state recorded after such a save vouches for a
 * spawn that never reached disk, and no later start sets it again. So the file
 * is read back and its spawn compared with the one in memory, the one the save
 * serialized: comparing with the spawn the caller set would take a spawn that
 * something else moved in between for a failed write, and refuse a save that
 * landed.
 */
final class WorldSave {
    private static final String LEVEL_DATA = "Data";
    private static final String SPAWN_X = "SpawnX";
    private static final String SPAWN_Y = "SpawnY";
    private static final String SPAWN_Z = "SpawnZ";

    private WorldSave() {
    }

    static void saveConfirmingSpawn(
            MinecraftServer server,
            String after) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(after, "after");
        if (!server.saveAllChunks(
                true,
                true,
                true)) {
            throw new StartingStructureStartupException(
                    "Minecraft did not save any level " + after);
        }
        requireSpawn(
                server.getWorldPath(LevelResource.LEVEL_DATA_FILE),
                server.getWorldData()
                        .overworldData()
                        .getSpawnPos(),
                after);
    }

    static void requireSpawn(
            Path levelData,
            BlockPos expected,
            String after) {
        Objects.requireNonNull(levelData, "levelData");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(after, "after");
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(
                    levelData,
                    NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException failure) {
            throw new StartingStructureStartupException(
                    "Could not read " + levelData + " " + after
                            + " to confirm that it holds the world spawn " + expected,
                    failure);
        }
        Optional<BlockPos> found = spawn(root);
        if (!found.equals(Optional.of(expected))) {
            throw new StartingStructureStartupException(
                    "Minecraft did not write the world spawn " + expected
                            + " to " + levelData + " " + after
                            + "; the file holds "
                            + found.map(BlockPos::toString)
                                    .orElse("no spawn"));
        }
    }

    private static Optional<BlockPos> spawn(CompoundTag root) {
        if (!root.contains(LEVEL_DATA, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag data = root.getCompound(LEVEL_DATA);
        if (!data.contains(SPAWN_X, Tag.TAG_INT)
                || !data.contains(SPAWN_Y, Tag.TAG_INT)
                || !data.contains(SPAWN_Z, Tag.TAG_INT)) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(
                data.getInt(SPAWN_X),
                data.getInt(SPAWN_Y),
                data.getInt(SPAWN_Z)));
    }
}
