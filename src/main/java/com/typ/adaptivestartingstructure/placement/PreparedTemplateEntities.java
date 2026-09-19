package com.typ.adaptivestartingstructure.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

public final class PreparedTemplateEntities
        implements AutoCloseable {
    private final TemplateEntityPlacementPlan plan;
    private final List<OwnedEntity> entities;
    private int addedEntities;
    private boolean closed;

    PreparedTemplateEntities(
            TemplateEntityPlacementPlan plan,
            List<? extends Entity> materializedEntities) {
        this.plan = Objects.requireNonNull(
                plan,
                "plan");
        List<Entity> copy = List.copyOf(
                materializedEntities);
        if (copy.size() != plan.plannedEntities()) {
            throw new IllegalArgumentException(
                    "Every planned template entity must be materialized");
        }
        List<OwnedEntity> owned =
                new ArrayList<>(copy.size());
        for (int index = 0;
                index < copy.size();
                index++) {
            Entity entity = Objects.requireNonNull(
                    copy.get(index),
                    "entity");
            TemplateEntityPlacementEntry entry =
                    plan.entries().get(index);
            if (entity.getType() != entry.type()
                    || entity.isAddedToLevel()) {
                throw new IllegalArgumentException(
                        "Prepared template entity does not match its plan");
            }
            owned.add(new OwnedEntity(
                    entry,
                    entity));
        }
        this.entities = List.copyOf(owned);
    }

    public TemplateEntityPlacementPlan plan() {
        ensureOpen();
        return plan;
    }

    public int materializedEntities() {
        ensureOpen();
        return entities.size();
    }

    public int addedEntities() {
        ensureOpen();
        return addedEntities;
    }

    TemplateEntityPlacementEntry entry(int index) {
        ensureOpen();
        return entities.get(index).entry;
    }

    Entity entity(int index) {
        ensureOpen();
        return entities.get(index).entity;
    }

    int size() {
        ensureOpen();
        return entities.size();
    }

    void transferOwnership(int index) {
        ensureOpen();
        OwnedEntity owned = entities.get(index);
        if (owned.transferred) {
            throw new IllegalStateException(
                    "Template entity ownership was already transferred");
        }
        owned.transferred = true;
        addedEntities++;
    }

    public TemplateEntityPlacementMetrics metrics() {
        ensureOpen();
        return new TemplateEntityPlacementMetrics(
                plan.sourceEntities(),
                plan.plannedEntities(),
                plan.skippedByDisabledOption(),
                plan.skippedUnsupportedEntities(),
                entities.size(),
                addedEntities);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException firstFailure = null;
        for (OwnedEntity owned : entities) {
            if (owned.transferred
                    || owned.entity.isAddedToLevel()
                    || owned.entity.isRemoved()) {
                continue;
            }
            try {
                owned.entity.discard();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Prepared template entities have already been closed");
        }
    }

    private static final class OwnedEntity {
        private final TemplateEntityPlacementEntry entry;
        private final Entity entity;
        private boolean transferred;

        private OwnedEntity(
                TemplateEntityPlacementEntry entry,
                Entity entity) {
            this.entry = entry;
            this.entity = entity;
        }
    }
}
