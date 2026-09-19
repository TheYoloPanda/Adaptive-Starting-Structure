package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import java.util.Optional;
import net.minecraft.core.BlockPos;

interface SpawnPlanningContext {
    boolean isOverworld();

    boolean isFirstInitialization();

    boolean isServerThread();

    long nanoTime();

    ConfigSnapshot loadConfig();

    long worldSeed();

    /**
     * The data a previous creation attempt already wrote for this world,
     * which exists when the game died between planning and the level being
     * marked initialized.
     */
    Optional<StartingStructureSavedData> loadExistingData();

    StartingStructurePlan createPlan(ConfigSnapshot config) throws Exception;

    void persist(StartingStructureSavedData data) throws Exception;

    void setSpawn(BlockPos spawn, float angle);

    void cancelEvent();

    void reportPlanningFallback(
            PlanningFailurePhase phase,
            Exception failure);

    void logCompletion(
            StartingStructurePlan plan,
            long planningElapsedNanos);

    void logResumedPlan(StartingStructurePlan plan);
}
