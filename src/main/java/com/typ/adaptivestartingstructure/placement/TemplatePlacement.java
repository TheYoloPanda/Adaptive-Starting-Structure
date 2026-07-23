package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

public final class TemplatePlacement {
    private TemplatePlacement() {
    }

    public static TemplatePlacementResult place(
            PreparedPlacement prepared,
            TerrainBlendingResult terrain) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(terrain, "terrain");
        ServerLevel level = prepared.level();
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Template placement must run on the server thread");
        }
        if (prepared.savedData().state()
                != StartingStructureSavedData.State.PLACING) {
            throw new PlacementPreparationException(
                    "Template placement requires PLACING saved data");
        }
        if (!terrain.leveling()
                .snapshot()
                .bounds()
                .equals(prepared.bounds())) {
            throw new PlacementPreparationException(
                    "Terrain context does not match prepared placement");
        }

        TemplatePlacementPlan plan =
                TemplatePlacementPlanner.plan(
                        level::getBlockState,
                        prepared.savedData().plan(),
                        prepared.definition(),
                        prepared.structure(),
                        prepared.bounds());
        int applied =
                TemplatePlacementApplier.apply(level, plan);
        return new TemplatePlacementResult(
                terrain,
                plan,
                applied);
    }
}
