package com.typ.adaptivestartingstructure.client;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.world.level.storage.LevelStorageException;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Deletes the world whose starting structure could not be placed, and
 * nothing else.
 *
 * <p>An earlier version took the folder name from the end of the server's
 * root path, which is {@code .}, and so pointed at the whole saves folder. The
 * level id is therefore never cut out of a path here: it is the name of the
 * world-list entry whose folder is the running server's folder, and it is
 * looked up in the world list again right before deleting.
 */
final class FailedWorldDeletion {
    private FailedWorldDeletion() {
    }

    static Optional<String> runningWorldId(
            Path runningWorld,
            LevelStorageSource worlds) {
        try {
            return levelIdOf(runningWorld, worlds.findLevelCandidates());
        } catch (LevelStorageException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Could not read the world list to identify the running world",
                    failure);
            return Optional.empty();
        }
    }

    static Optional<String> levelIdOf(
            Path runningWorld,
            Iterable<LevelStorageSource.LevelDirectory> worldList) {
        Path running = runningWorld.toAbsolutePath().normalize();
        for (LevelStorageSource.LevelDirectory entry : worldList) {
            if (entry.path().toAbsolutePath().normalize().equals(running)) {
                String levelId = entry.directoryName();
                return isSafeLevelId(levelId)
                        ? Optional.of(levelId)
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    static boolean isSafeLevelId(String levelId) {
        return levelId != null
                && !levelId.isEmpty()
                && !levelId.equals(".")
                && !levelId.equals("..")
                && levelId.indexOf('/') < 0
                && levelId.indexOf('\\') < 0;
    }

    /**
     * Deletes the world through the same call the world list's Delete button
     * makes. Returns false, with the world left in place, when the id is
     * refused, the world is no longer listed, or deleting fails.
     */
    static boolean deleteListedWorld(
            LevelStorageSource worlds,
            String levelId) {
        if (!isSafeLevelId(levelId)) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Refused to delete a world with the level id '{}'",
                    levelId);
            return false;
        }
        try {
            if (!isListed(worlds, levelId)) {
                AdaptiveStartingStructure.LOGGER.error(
                        "Refused to delete world '{}' because the world list no longer contains it",
                        levelId);
                return false;
            }
            try (LevelStorageSource.LevelStorageAccess access =
                    worlds.createAccess(levelId)) {
                access.deleteLevel();
            }
            AdaptiveStartingStructure.LOGGER.info(
                    "Deleted world '{}', whose starting structure could not be placed",
                    levelId);
            return true;
        } catch (IOException | LevelStorageException failure) {
            AdaptiveStartingStructure.LOGGER.error(
                    "Could not delete world '{}'",
                    levelId,
                    failure);
            return false;
        }
    }

    private static boolean isListed(
            LevelStorageSource worlds,
            String levelId) {
        for (LevelStorageSource.LevelDirectory entry
                : worlds.findLevelCandidates()) {
            if (entry.directoryName().equals(levelId)) {
                return true;
            }
        }
        return false;
    }
}
