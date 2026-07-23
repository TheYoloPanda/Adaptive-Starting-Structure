package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

public final class TerrainLeveling {
    private TerrainLeveling() {
    }

    public static TerrainLevelingResult level(
            PreparedPlacement prepared,
            ConfigSnapshot config) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(config, "config");
        requireServerThread(prepared);
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLACING) {
            throw new PlacementPreparationException(
                    "Terrain leveling requires PLACING saved data");
        }
        PreparedTerrainLeveling planned =
                plan(prepared, config);
        return apply(prepared, planned);
    }

    public static PreparedTerrainLeveling prepare(
            PreparedPlacement prepared,
            ConfigSnapshot config) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(config, "config");
        requireServerThread(prepared);
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLANNED) {
            throw new PlacementPreparationException(
                    "Terrain leveling preparation requires PLANNED saved data");
        }
        return plan(prepared, config);
    }

    public static TerrainLevelingResult apply(
            PreparedPlacement prepared,
            PreparedTerrainLeveling planned) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(planned, "planned");
        ServerLevel level = requireServerThread(prepared);
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLACING) {
            throw new PlacementPreparationException(
                    "Terrain leveling application requires PLACING saved data");
        }
        if (!planned.snapshot()
                .bounds()
                .equals(prepared.bounds())) {
            throw new PlacementPreparationException(
                    "Prepared terrain leveling does not match placement bounds");
        }
        int applied = TerrainPlanApplier.apply(
                level,
                planned.plan());
        return new TerrainLevelingResult(
                planned.snapshot(),
                planned.plan(),
                applied);
    }

    private static PreparedTerrainLeveling plan(
            PreparedPlacement prepared,
            ConfigSnapshot config) {
        ServerLevel level = prepared.level();
        TerrainSnapshot snapshot = TerrainSnapshotCapture.capture(
                level,
                prepared.bounds(),
                prepared.savedData()
                        .plan()
                        .candidate()
                        .groundSurfaceY());
        TerrainTransformationPlan plan =
                FootprintLevelingPlanner.plan(
                        snapshot,
                        prepared.structure(),
                        prepared.savedData()
                                .plan()
                                .candidate()
                                .placementOrigin(),
                        config);
        return new PreparedTerrainLeveling(snapshot, plan);
    }

    private static ServerLevel requireServerThread(
            PreparedPlacement prepared) {
        ServerLevel level = prepared.level();
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Terrain leveling must run on the server thread");
        }
        return level;
    }
}
