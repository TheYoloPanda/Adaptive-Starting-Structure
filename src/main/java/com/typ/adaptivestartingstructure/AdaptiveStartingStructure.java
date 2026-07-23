package com.typ.adaptivestartingstructure;

import com.mojang.logging.LogUtils;
import com.typ.adaptivestartingstructure.config.ModConfig;
import com.typ.adaptivestartingstructure.lifecycle.PlacementLifecycle;
import com.typ.adaptivestartingstructure.lifecycle.PlacementFailureNotifier;
import com.typ.adaptivestartingstructure.lifecycle.PlanningFailureNotifier;
import com.typ.adaptivestartingstructure.lifecycle.SpawnPlanningLifecycle;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(AdaptiveStartingStructure.MOD_ID)
public final class AdaptiveStartingStructure {
    public static final String MOD_ID = "adaptive_starting_structure";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AdaptiveStartingStructure(IEventBus modEventBus, ModContainer modContainer) {
        ModConfig.register(modEventBus, modContainer);
        NeoForge.EVENT_BUS.addListener(
                SpawnPlanningLifecycle::onCreateSpawnPosition);
        NeoForge.EVENT_BUS.addListener(
                PlacementLifecycle::onServerStarting);
        NeoForge.EVENT_BUS.addListener(
                PlacementFailureNotifier::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(
                PlacementFailureNotifier::onServerStopped);
        NeoForge.EVENT_BUS.addListener(
                PlanningFailureNotifier::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(
                PlanningFailureNotifier::onServerStopped);
    }
}
