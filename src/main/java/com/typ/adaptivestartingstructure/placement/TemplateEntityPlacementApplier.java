package com.typ.adaptivestartingstructure.placement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;

final class TemplateEntityPlacementApplier {
    private TemplateEntityPlacementApplier() {
    }

    static void validateAttachments(
            PreparedTemplateEntities prepared) {
        for (int index = 0;
                index < prepared.size();
                index++) {
            Entity entity = prepared.entity(index);
            if (entity
                    instanceof BlockAttachedEntity attached
                    && !attached.survives()) {
                throw failure(
                        prepared,
                        index,
                        "failed its final attachment survival check");
            }
        }

        for (int firstIndex = 0;
                firstIndex < prepared.size();
                firstIndex++) {
            Entity first =
                    prepared.entity(firstIndex);
            if (!(first
                    instanceof BlockAttachedEntity firstAttached)) {
                continue;
            }
            for (int secondIndex =
                            firstIndex + 1;
                    secondIndex
                            < prepared.size();
                    secondIndex++) {
                Entity second =
                        prepared.entity(secondIndex);
                if (!(second
                        instanceof BlockAttachedEntity secondAttached)) {
                    continue;
                }
                if (firstAttached.getPos()
                                .equals(
                                        secondAttached.getPos())
                        || first.getBoundingBox()
                                .intersects(
                                        second.getBoundingBox())) {
                    throw failure(
                            prepared,
                            secondIndex,
                            "conflicts with prepared block-attached entity at source index "
                                    + prepared.entry(
                                                    firstIndex)
                                            .sourceIndex());
                }
            }
        }
    }

    static TemplateEntityPlacementMetrics addAll(
            ServerLevel level,
            PreparedTemplateEntities prepared) {
        if (prepared.addedEntities() != 0) {
            throw new IllegalStateException(
                    "Template entity publication may only run once");
        }
        for (int index = 0;
                index < prepared.size();
                index++) {
            Entity entity =
                    prepared.entity(index);
            TemplateEntityPlacementEntry entry =
                    prepared.entry(index);
            boolean added;
            try {
                added = level.addFreshEntity(
                        entity);
            } catch (RuntimeException failure) {
                throw failure(
                        prepared,
                        index,
                        "threw while being added; "
                                + prepared.addedEntities()
                                + " earlier entities were already added",
                        failure);
            }
            if (!added) {
                throw failure(
                        prepared,
                        index,
                        "was rejected by ServerLevel.addFreshEntity; "
                                + prepared.addedEntities()
                                + " earlier entities were already added");
            }
            prepared.transferOwnership(index);
            if (!entity.isAddedToLevel()
                    || entity.isRemoved()
                    || level.getEntity(
                                    entity.getUUID())
                            != entity) {
                throw failure(
                        prepared,
                        index,
                        "reported success but was not immediately live in the level; "
                                + prepared.addedEntities()
                                + " entities were already accepted");
            }
            if (entity.getType()
                    != entry.type()) {
                throw failure(
                        prepared,
                        index,
                        "changed registry type during publication");
            }
        }

        TemplateEntityPlacementMetrics metrics =
                prepared.metrics();
        if (metrics.addedEntities()
                != metrics.materializedEntities()) {
            throw new IllegalStateException(
                    "Not every materialized template entity was added");
        }
        return metrics;
    }

    private static PlacementPreparationException failure(
            PreparedTemplateEntities prepared,
            int index,
            String reason) {
        return failure(
                prepared,
                index,
                reason,
                null);
    }

    private static PlacementPreparationException failure(
            PreparedTemplateEntities prepared,
            int index,
            String reason,
            RuntimeException cause) {
        TemplateEntityPlacementEntry entry =
                prepared.entry(index);
        String message =
                "Template entity in structure '"
                        + prepared.plan()
                                .structureId()
                        + "' at source index "
                        + entry.sourceIndex()
                        + " (type '"
                        + entry.typeId()
                        + "', position "
                        + entry.worldPosition()
                        + ") "
                        + reason;
        return cause == null
                ? new PlacementPreparationException(
                        message)
                : new PlacementPreparationException(
                        message,
                        cause);
    }
}
