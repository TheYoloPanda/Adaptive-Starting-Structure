package com.typ.adaptivestartingstructure;

import com.mojang.logging.LogUtils;
import com.typ.adaptivestartingstructure.config.ModConfig;
import com.typ.adaptivestartingstructure.lifecycle.NoStructureNotice;
import com.typ.adaptivestartingstructure.lifecycle.PlacementLifecycle;
import com.typ.adaptivestartingstructure.lifecycle.PlacementFailureNotifier;
import com.typ.adaptivestartingstructure.lifecycle.FallbackDecisionLifecycle;
import com.typ.adaptivestartingstructure.lifecycle.PlanningFailureNotifier;
import com.typ.adaptivestartingstructure.lifecycle.SpawnMarkerPlacement;
import com.typ.adaptivestartingstructure.lifecycle.SpawnPlanningLifecycle;
import com.typ.adaptivestartingstructure.network.FallbackNetwork;
import com.typ.adaptivestartingstructure.placement.StartupChunkRecord;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(AdaptiveStartingStructure.MOD_ID)
public final class AdaptiveStartingStructure {
    public static final String MOD_ID = "adaptive_starting_structure";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AdaptiveStartingStructure(IEventBus modEventBus, ModContainer modContainer) {
        ModConfig.register(modEventBus, modContainer);
        modEventBus.addListener(FallbackNetwork::register);
        StartupChunkRecord startupChunks = new StartupChunkRecord();
        NeoForge.EVENT_BUS.addListener(
                ServerAboutToStartEvent.class,
                startupChunks::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(
                ChunkTicketLevelUpdatedEvent.class,
                startupChunks::onTicketLevelUpdated);
        NeoForge.EVENT_BUS.addListener(
                ChunkEvent.Load.class,
                startupChunks::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(
                ServerStoppedEvent.class,
                startupChunks::onServerStopped);
        NeoForge.EVENT_BUS.addListener(
                SpawnPlanningLifecycle::onCreateSpawnPosition);
        NeoForge.EVENT_BUS.addListener(
                PlacementLifecycle::onServerStarting);
        NeoForge.EVENT_BUS.addListener(
                PlacementFailureNotifier::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(
                PlacementFailureNotifier::onServerStopped);
        NeoForge.EVENT_BUS.addListener(
                FallbackDecisionLifecycle::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(
                PlanningFailureNotifier::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(
                PlanningFailureNotifier::onServerStopped);
        NeoForge.EVENT_BUS.addListener(
                SpawnMarkerPlacement::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(
                NoStructureNotice::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(
                SpawnMarkerPlacement::onRespawnPosition);
    }
}
