package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public final class GeneratedStructureCollisionValidator {
    private GeneratedStructureCollisionValidator() {
    }

    /**
     * Rejects the site when a generated structure is in its way. A structure in
     * {@code ignoredStructures} never rejects it; the collisions let through
     * for that reason are returned, one per structure, for the log.
     */
    public static List<String> validate(
            PreparedPlacement prepared,
            PreparedTerrainLeveling leveling,
            PreparedTerrainBlending blending,
            Set<ResourceLocation> ignoredStructures) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(leveling, "leveling");
        Objects.requireNonNull(blending, "blending");
        ServerLevel level = prepared.level();
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Generated-structure collision validation must run on the server thread");
        }
        if (blending.leveling() != leveling) {
            throw new PlacementPreparationException(
                    "Generated-structure collision validation received mismatched terrain plans");
        }

        Map<ChunkPos, List<BlockPos>> writesByChunk =
                plannedWritePositions(leveling, blending);
        return validate(
                new ServerWorldView(level),
                prepared.bounds().structureBounds(),
                writesByChunk,
                Set.copyOf(prepared.chunks()),
                ignoredStructures);
    }

    static List<String> validate(
            WorldView world,
            StructureBounds templateBounds,
            Map<ChunkPos, List<BlockPos>> writesByChunk,
            Set<ChunkPos> preparedChunks,
            Set<ResourceLocation> ignoredStructures) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(templateBounds, "templateBounds");
        Objects.requireNonNull(writesByChunk, "writesByChunk");
        Objects.requireNonNull(preparedChunks, "preparedChunks");
        Objects.requireNonNull(ignoredStructures, "ignoredStructures");

        CollisionBox templateBox = CollisionBox.from(templateBounds);
        LinkedHashSet<ChunkPos> relevantChunks =
                templateChunks(templateBounds);
        Map<ChunkPos, List<BlockPos>> immutableWrites =
                immutableWrites(writesByChunk);
        relevantChunks.addAll(immutableWrites.keySet());
        if (!preparedChunks.containsAll(relevantChunks)) {
            throw new PlacementPreparationException(
                    "Generated-structure collision validation requires an unprepared chunk");
        }

        Set<StructureKey> templateChecks = new HashSet<>();
        Map<StructureKey, String> ignoredCollisions = new LinkedHashMap<>();
        for (ChunkPos chunk : relevantChunks) {
            Set<StructureKey> checkedInChunk = new HashSet<>();
            List<BlockPos> chunkWrites =
                    immutableWrites.getOrDefault(chunk, List.of());
            for (GeneratedStructure structure
                    : world.startsForChunk(chunk)) {
                StructureKey key = new StructureKey(
                        structure.id(),
                        structure.startChunk());
                if (!checkedInChunk.add(key)) {
                    continue;
                }
                String detail = collisionDetail(
                        structure,
                        templateChecks.add(key),
                        templateBox,
                        chunkWrites);
                if (detail == null) {
                    continue;
                }
                String collision = describe(structure, detail);
                if (!ignoredStructures.contains(structure.id())) {
                    throw new UnsuitableGeneratedSiteException(collision);
                }
                ignoredCollisions.putIfAbsent(key, collision);
            }
        }
        return List.copyOf(ignoredCollisions.values());
    }

    private static String collisionDetail(
            GeneratedStructure structure,
            boolean checkTemplateBounds,
            CollisionBox templateBox,
            List<BlockPos> chunkWrites) {
        if (checkTemplateBounds
                && structure.startBounds().intersects(templateBox)) {
            return "intersects the starting-template bounds";
        }
        for (BlockPos write : chunkWrites) {
            if (insideAnyPiece(write, structure.pieceBounds())) {
                return "contains a planned terrain write at "
                        + coordinate(write);
            }
        }
        return null;
    }

    private static Map<ChunkPos, List<BlockPos>> plannedWritePositions(
            PreparedTerrainLeveling leveling,
            PreparedTerrainBlending blending) {
        Map<ChunkPos, List<BlockPos>> writes = new LinkedHashMap<>();
        addWritePositions(
                writes,
                leveling.plan().writesByChunk());
        addWritePositions(
                writes,
                blending.plan().writesByChunk());
        return writes;
    }

    private static void addWritePositions(
            Map<ChunkPos, List<BlockPos>> target,
            Map<ChunkPos, List<TerrainWrite>> source) {
        for (Map.Entry<ChunkPos, List<TerrainWrite>> entry
                : source.entrySet()) {
            List<BlockPos> positions = target.computeIfAbsent(
                    entry.getKey(),
                    ignored -> new ArrayList<>());
            for (TerrainWrite write : entry.getValue()) {
                positions.add(write.position());
            }
        }
    }

    private static Map<ChunkPos, List<BlockPos>> immutableWrites(
            Map<ChunkPos, List<BlockPos>> writesByChunk) {
        Map<ChunkPos, List<BlockPos>> copy = new LinkedHashMap<>();
        for (Map.Entry<ChunkPos, List<BlockPos>> entry
                : writesByChunk.entrySet()) {
            ChunkPos chunk = Objects.requireNonNull(
                    entry.getKey(),
                    "write chunk");
            List<BlockPos> positions = new ArrayList<>();
            for (BlockPos position : entry.getValue()) {
                BlockPos immutable = Objects.requireNonNull(
                                position,
                                "write position")
                        .immutable();
                if (!new ChunkPos(immutable).equals(chunk)) {
                    throw new PlacementPreparationException(
                            "Generated-structure collision validation received a write in the wrong chunk");
                }
                positions.add(immutable);
            }
            if (!positions.isEmpty()) {
                copy.put(chunk, List.copyOf(positions));
            }
        }
        return Map.copyOf(copy);
    }

    private static LinkedHashSet<ChunkPos> templateChunks(
            StructureBounds bounds) {
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        int minimumChunkX = SectionPos.blockToSectionCoord(
                bounds.minimum().getX());
        int maximumChunkX = SectionPos.blockToSectionCoord(
                bounds.maximum().getX());
        int minimumChunkZ = SectionPos.blockToSectionCoord(
                bounds.minimum().getZ());
        int maximumChunkZ = SectionPos.blockToSectionCoord(
                bounds.maximum().getZ());
        for (int chunkX = minimumChunkX; ; chunkX++) {
            for (int chunkZ = minimumChunkZ; ; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
                if (chunkZ == maximumChunkZ) {
                    break;
                }
            }
            if (chunkX == maximumChunkX) {
                break;
            }
        }
        return chunks;
    }

    private static boolean insideAnyPiece(
            BlockPos position,
            List<CollisionBox> pieces) {
        for (CollisionBox piece : pieces) {
            if (piece.contains(position)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(
            GeneratedStructure structure,
            String detail) {
        return "Generated structure "
                + structure.id()
                + " at start chunk ["
                + structure.startChunk().x
                + ", "
                + structure.startChunk().z
                + "] "
                + detail;
    }

    private static String coordinate(BlockPos position) {
        return "["
                + position.getX()
                + ", "
                + position.getY()
                + ", "
                + position.getZ()
                + "]";
    }

    interface WorldView {
        List<GeneratedStructure> startsForChunk(ChunkPos chunk);
    }

    record GeneratedStructure(
            ResourceLocation id,
            ChunkPos startChunk,
            CollisionBox startBounds,
            List<CollisionBox> pieceBounds) {
        GeneratedStructure {
            id = Objects.requireNonNull(id, "id");
            startChunk = Objects.requireNonNull(
                    startChunk,
                    "startChunk");
            startBounds = Objects.requireNonNull(
                    startBounds,
                    "startBounds");
            pieceBounds = List.copyOf(pieceBounds);
            if (pieceBounds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Generated structures must contain at least one piece");
            }
        }
    }

    record CollisionBox(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        CollisionBox {
            if (minimumX > maximumX
                    || minimumY > maximumY
                    || minimumZ > maximumZ) {
                throw new IllegalArgumentException(
                        "Collision bounds must be ordered");
            }
        }

        static CollisionBox from(StructureBounds bounds) {
            return new CollisionBox(
                    bounds.minimum().getX(),
                    bounds.minimum().getY(),
                    bounds.minimum().getZ(),
                    bounds.maximum().getX(),
                    bounds.maximum().getY(),
                    bounds.maximum().getZ());
        }

        static CollisionBox from(BoundingBox bounds) {
            return new CollisionBox(
                    bounds.minX(),
                    bounds.minY(),
                    bounds.minZ(),
                    bounds.maxX(),
                    bounds.maxY(),
                    bounds.maxZ());
        }

        boolean intersects(CollisionBox other) {
            return maximumX >= other.minimumX
                    && minimumX <= other.maximumX
                    && maximumY >= other.minimumY
                    && minimumY <= other.maximumY
                    && maximumZ >= other.minimumZ
                    && minimumZ <= other.maximumZ;
        }

        boolean contains(BlockPos position) {
            return position.getX() >= minimumX
                    && position.getX() <= maximumX
                    && position.getY() >= minimumY
                    && position.getY() <= maximumY
                    && position.getZ() >= minimumZ
                    && position.getZ() <= maximumZ;
        }
    }

    private record StructureKey(
            ResourceLocation id,
            ChunkPos startChunk) {
    }

    private static final class ServerWorldView
            implements WorldView {
        private final ServerLevel level;
        private final Map<StructureKey, GeneratedStructure> cache =
                new LinkedHashMap<>();

        private ServerWorldView(ServerLevel level) {
            this.level = Objects.requireNonNull(level, "level");
        }

        @Override
        public List<GeneratedStructure> startsForChunk(
                ChunkPos chunk) {
            var registry = level.registryAccess()
                    .registryOrThrow(Registries.STRUCTURE);
            List<GeneratedStructure> structures =
                    new ArrayList<>();
            for (StructureStart start : level.structureManager()
                    .startsForStructure(chunk, ignored -> true)) {
                Structure structure = start.getStructure();
                ResourceLocation id = registry.getKey(structure);
                if (id == null) {
                    throw new PlacementPreparationException(
                            "Generated structure start uses an unregistered structure type");
                }
                StructureKey key = new StructureKey(
                        id,
                        start.getChunkPos());
                structures.add(cache.computeIfAbsent(
                        key,
                        ignored -> new GeneratedStructure(
                                id,
                                start.getChunkPos(),
                                CollisionBox.from(
                                        start.getBoundingBox()),
                                start.getPieces().stream()
                                        .map(piece -> CollisionBox.from(
                                                piece.getBoundingBox()))
                                        .toList())));
            }
            return List.copyOf(structures);
        }
    }
}
