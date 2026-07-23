package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class PreparedPlacement implements AutoCloseable {
    private final ServerLevel level;
    private final StartingStructureSavedData savedData;
    private final StructureDefinition definition;
    private final RotatedStructureView structure;
    private final PlacementBounds bounds;
    private final List<ChunkPos> chunks;
    private final GeneratedSiteValidation validation;
    private final ChunkTicketLease lease;
    private boolean closed;

    PreparedPlacement(
            ServerLevel level,
            StartingStructureSavedData savedData,
            StructureDefinition definition,
            RotatedStructureView structure,
            PlacementBounds bounds,
            List<ChunkPos> chunks,
            GeneratedSiteValidation validation,
            ChunkTicketLease lease) {
        this.level = Objects.requireNonNull(level, "level");
        this.savedData = Objects.requireNonNull(savedData, "savedData");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.structure = Objects.requireNonNull(structure, "structure");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.chunks = List.copyOf(chunks);
        this.validation = Objects.requireNonNull(validation, "validation");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (this.chunks.isEmpty()
                || !this.chunks.equals(lease.chunks())) {
            throw new IllegalArgumentException(
                    "Prepared placement chunks must match the active lease");
        }
    }

    public ServerLevel level() {
        ensureOpen();
        return level;
    }

    public StartingStructureSavedData savedData() {
        ensureOpen();
        return savedData;
    }

    public StructureDefinition definition() {
        ensureOpen();
        return definition;
    }

    public RotatedStructureView structure() {
        ensureOpen();
        return structure;
    }

    public PlacementBounds bounds() {
        ensureOpen();
        return bounds;
    }

    public List<ChunkPos> chunks() {
        ensureOpen();
        return chunks;
    }

    public GeneratedSiteValidation validation() {
        ensureOpen();
        return validation;
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
        lease.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Prepared placement has already been closed");
        }
    }
}
