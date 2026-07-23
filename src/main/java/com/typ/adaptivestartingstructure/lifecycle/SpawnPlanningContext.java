package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import net.minecraft.core.BlockPos;

interface SpawnPlanningContext {
    boolean isOverworld();

    boolean isFirstInitialization();

    boolean isServerThread();

    long nanoTime();

    ConfigSnapshot loadConfig();

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
}
