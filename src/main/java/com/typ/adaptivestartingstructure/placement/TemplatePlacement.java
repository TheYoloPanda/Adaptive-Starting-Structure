package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

public final class TemplatePlacement {
    private TemplatePlacement() {
    }

    public static TemplatePlacementResult place(
            PreparedPlacement prepared,
            TerrainBlendingResult terrain,
            ConfigSnapshot config) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(config, "config");
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
                .equals(prepared.treeObservationBounds())) {
            throw new PlacementPreparationException(
                    "Terrain context does not match prepared placement");
        }

        TemplatePlacementPlan plan =
                TemplatePlacementPlanner.plan(
                        level::getBlockState,
                        prepared.savedData().plan(),
                        prepared.definition(),
                        prepared.structure(),
                        prepared.bounds(),
                        config.adaptTemplateToSite()
                                ? new TemplateSiteAdaptation.Site(
                                        TemplateSiteAdaptation.materials(
                                                terrain.leveling().snapshot(),
                                                prepared.bounds()),
                                        SnowCoverPlanner.climateOf(level))
                                : TemplateSiteAdaptation.Site.AS_AUTHORED);
        int applied =
                TemplatePlacementApplier.apply(level, plan);
        return new TemplatePlacementResult(
                terrain,
                plan,
                applied);
    }
}
