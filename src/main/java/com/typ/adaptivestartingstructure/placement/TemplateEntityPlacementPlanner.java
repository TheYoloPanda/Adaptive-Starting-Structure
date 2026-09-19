package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.config.ConfigSnapshot;
import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import com.typ.adaptivestartingstructure.structure.RotatedStructureView;
import com.typ.adaptivestartingstructure.structure.StructureDefinition;
import com.typ.adaptivestartingstructure.structure.StructureEntityData;
import com.typ.adaptivestartingstructure.structure.StructureTransforms;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class TemplateEntityPlacementPlanner {
    static final int MAX_DIAGNOSTIC_TEXT_LENGTH = 128;

    private TemplateEntityPlacementPlanner() {
    }

    static PreparedTemplateEntities prepare(
            ServerLevel level,
            StartingStructurePlan savedPlan,
            StructureDefinition definition,
            RotatedStructureView structure,
            PlacementBounds bounds,
            List<ChunkPos> preparedChunks,
            ConfigSnapshot config) {
        if (!level.getServer().isSameThread()) {
            throw new PlacementPreparationException(
                    "Template entity preparation must run on the server thread");
        }
        String structureId =
                definition.source().id();
        List<StructureEntityData> sourceEntities =
                definition.entities();
        if (!config.placeTemplateEntities()) {
            return new PreparedTemplateEntities(
                    new TemplateEntityPlacementPlan(
                            structureId,
                            sourceEntities.size(),
                            sourceEntities.size(),
                            List.of(),
                            List.of()),
                    List.of());
        }

        TemplateEntityNbtLimits.validate(
                sourceEntities);
        rejectPassengers(
                structureId,
                sourceEntities);

        Set<Long> preparedChunkKeys =
                new HashSet<>(preparedChunks.size());
        for (ChunkPos chunk
                : preparedChunks) {
            preparedChunkKeys.add(chunk.toLong());
        }

        List<TemplateEntityPlacementEntry> entries =
                new ArrayList<>(sourceEntities.size());
        List<TemplateEntitySkip> skipped =
                new ArrayList<>();
        List<Entity> materialized =
                new ArrayList<>(sourceEntities.size());
        try {
            for (StructureEntityData source
                    : sourceEntities) {
                Optional<String> authoredId =
                        source.authoredId();
                if (authoredId.isEmpty()) {
                    skip(
                            structureId,
                            source,
                            skipped,
                            "missing or non-string nbt.id",
                            null);
                    continue;
                }
                ResourceLocation typeId =
                        ResourceLocation.tryParse(
                                authoredId.orElseThrow());
                if (typeId == null) {
                    skip(
                            structureId,
                            source,
                            skipped,
                            "malformed nbt.id",
                            null);
                    continue;
                }
                if (typeId.equals(
                        EntityType.getKey(
                                EntityType.PLAYER))) {
                    throw fatal(
                            structureId,
                            source,
                            "player entities are forbidden");
                }

                EntityType<?> type =
                        BuiltInRegistries.ENTITY_TYPE
                                .getOptional(typeId)
                                .orElse(null);
                if (type == null) {
                    skip(
                            structureId,
                            source,
                            skipped,
                            "entity type is not registered",
                            null);
                    continue;
                }
                if (type == EntityType.PLAYER) {
                    throw fatal(
                            structureId,
                            source,
                            "player entities are forbidden");
                }
                if (!type.isEnabled(
                        level.enabledFeatures())) {
                    skip(
                            structureId,
                            source,
                            skipped,
                            "entity type is disabled by the level feature flags",
                            null);
                    continue;
                }
                if (!type.canSerialize()) {
                    skip(
                            structureId,
                            source,
                            skipped,
                            "entity type is not serializable",
                            null);
                    continue;
                }

                Vec3 worldPosition =
                        offset(
                                StructureTransforms.transform(
                                        source.position(),
                                        definition.size(),
                                        structure.rotation()),
                                savedPlan.candidate()
                                        .placementOrigin());
                BlockPos anchor =
                        offsetExact(
                                savedPlan.candidate()
                                        .placementOrigin(),
                                StructureTransforms.transform(
                                        source.blockPosition(),
                                        definition.size(),
                                        structure.rotation()));
                validatePreMaterializationGeometry(
                        level,
                        structureId,
                        source,
                        worldPosition,
                        anchor,
                        bounds);

                Entity entity;
                try {
                    entity = type.create(level);
                } catch (RuntimeException failure) {
                    skip(
                            structureId,
                            source,
                            skipped,
                            failureReason(
                                    "entity factory failed",
                                    failure),
                            failure);
                    continue;
                }
                if (entity == null) {
                    skip(
                            structureId,
                            source,
                            skipped,
                            "entity factory returned null",
                            null);
                    continue;
                }
                if (entity instanceof Player) {
                    discardForFatal(
                            entity,
                            null);
                    throw fatal(
                            structureId,
                            source,
                            "materialized entity is a Player subclass");
                }
                if (entity.getType() != type) {
                    discardForSkip(
                            entity,
                            null,
                            structureId,
                            source);
                    skip(
                            structureId,
                            source,
                            skipped,
                            "entity factory returned a different registry type",
                            null);
                    continue;
                }

                CompoundTag placementNbt =
                        source.copyNbt();
                placementNbt.put(
                        "Pos",
                        doubleList(worldPosition));
                placementNbt.remove("UUID");
                if (entity
                        instanceof BlockAttachedEntity) {
                    placementNbt.putInt(
                            "TileX",
                            anchor.getX());
                    placementNbt.putInt(
                            "TileY",
                            anchor.getY());
                    placementNbt.putInt(
                            "TileZ",
                            anchor.getZ());
                }

                try {
                    entity.load(placementNbt);
                    float yaw =
                            entity.rotate(
                                    structure.rotation());
                    yaw += entity.mirror(Mirror.NONE)
                            - entity.getYRot();
                    float pitch =
                            entity.getXRot();
                    if (entity
                            instanceof BlockAttachedEntity attached) {
                        attached.setYRot(yaw);
                        attached.setXRot(pitch);
                        attached.setPos(
                                anchor.getX(),
                                anchor.getY(),
                                anchor.getZ());
                        attached.setOldPosAndRot();
                        if (!attached.getPos()
                                .equals(anchor)) {
                            throw new IllegalStateException(
                                    "block-attached entity did not retain its transformed anchor");
                        }
                    } else {
                        entity.moveTo(
                                worldPosition.x,
                                worldPosition.y,
                                worldPosition.z,
                                yaw,
                                pitch);
                    }
                    if (entity.isRemoved()
                            || entity.isAddedToLevel()) {
                        throw new IllegalStateException(
                                "materialized entity is not detached and live");
                    }
                } catch (RuntimeException failure) {
                    discardForSkip(
                            entity,
                            failure,
                            structureId,
                            source);
                    skip(
                            structureId,
                            source,
                            skipped,
                            failureReason(
                                    "entity NBT load or placement preparation failed",
                                    failure),
                            failure);
                    continue;
                }

                materialized.add(entity);
                validateMaterializedGeometry(
                        level,
                        structureId,
                        source,
                        entity,
                        worldPosition,
                        anchor,
                        preparedChunkKeys);
                entries.add(
                        new TemplateEntityPlacementEntry(
                                source.sourceIndex(),
                                typeId,
                                type,
                                worldPosition,
                                anchor,
                                placementNbt));
            }

            return new PreparedTemplateEntities(
                    new TemplateEntityPlacementPlan(
                            structureId,
                            sourceEntities.size(),
                            0,
                            entries,
                            skipped),
                    materialized);
        } catch (RuntimeException | Error failure) {
            discardAll(
                    materialized,
                    failure);
            throw failure;
        }
    }

    private static void rejectPassengers(
            String structureId,
            List<StructureEntityData> entities) {
        for (StructureEntityData entity
                : entities) {
            Tag passengers =
                    entity.copyNbt().get(
                            "Passengers");
            if (passengers instanceof ListTag list
                    && !list.isEmpty()) {
                throw fatal(
                        structureId,
                        entity,
                        "non-empty Passengers data is forbidden");
            }
        }
    }

    private static void validatePreMaterializationGeometry(
            ServerLevel level,
            String structureId,
            StructureEntityData source,
            Vec3 position,
            BlockPos anchor,
            PlacementBounds bounds) {
        if (!finite(position)
                || position.x < -Level.MAX_LEVEL_SIZE
                || position.x >= Level.MAX_LEVEL_SIZE
                || position.z < -Level.MAX_LEVEL_SIZE
                || position.z >= Level.MAX_LEVEL_SIZE
                || !Level.isInSpawnableBounds(anchor)) {
            throw fatal(
                    structureId,
                    source,
                    "transformed entity coordinates are outside spawnable world bounds");
        }
        if (!bounds.structureBounds()
                .contains(anchor)) {
            throw fatal(
                    structureId,
                    source,
                    "transformed block anchor is outside the structure bounds");
        }
        if (position.y
                        < level.getMinBuildHeight()
                || position.y
                        >= level.getMaxBuildHeight()
                || anchor.getY()
                        < level.getMinBuildHeight()
                || anchor.getY()
                        >= level.getMaxBuildHeight()) {
            throw new UnsuitableGeneratedSiteException(
                    diagnosticPrefix(
                            structureId,
                            source)
                            + "exceeds the generated world's build height");
        }
        if (!level.getWorldBorder()
                        .isWithinBounds(position)
                || !level.getWorldBorder()
                        .isWithinBounds(anchor)) {
            throw new UnsuitableGeneratedSiteException(
                    diagnosticPrefix(
                            structureId,
                            source)
                            + "is outside the world border");
        }
    }

    private static void validateMaterializedGeometry(
            ServerLevel level,
            String structureId,
            StructureEntityData source,
            Entity entity,
            Vec3 plannedPosition,
            BlockPos plannedAnchor,
            Set<Long> preparedChunks) {
        if (entity
                instanceof BlockAttachedEntity attached) {
            if (!attached.getPos()
                    .equals(plannedAnchor)) {
                throw fatal(
                        structureId,
                        source,
                        "materialized block attachment does not match its transformed anchor");
            }
        } else if (!samePosition(
                entity.position(),
                plannedPosition)) {
            throw fatal(
                    structureId,
                    source,
                    "materialized entity does not match its transformed position");
        }

        AABB box = entity.getBoundingBox();
        if (!valid(box)) {
            throw fatal(
                    structureId,
                    source,
                    "materialized entity has an invalid AABB");
        }
        if (box.minY
                        < level.getMinBuildHeight()
                || box.maxY
                        > level.getMaxBuildHeight()) {
            throw new UnsuitableGeneratedSiteException(
                    diagnosticPrefix(
                            structureId,
                            source)
                            + "AABB exceeds the generated world's build height");
        }
        if (!level.getWorldBorder()
                .isWithinBounds(box)) {
            throw new UnsuitableGeneratedSiteException(
                    diagnosticPrefix(
                            structureId,
                            source)
                            + "AABB is outside the world border");
        }

        int minimumChunkX =
                SectionPos.blockToSectionCoord(
                        Mth.floor(box.minX));
        int maximumChunkX =
                SectionPos.blockToSectionCoord(
                        Mth.floor(
                                Math.nextDown(
                                        box.maxX)));
        int minimumChunkZ =
                SectionPos.blockToSectionCoord(
                        Mth.floor(box.minZ));
        int maximumChunkZ =
                SectionPos.blockToSectionCoord(
                        Mth.floor(
                                Math.nextDown(
                                        box.maxZ)));
        for (int chunkX = minimumChunkX;
                chunkX <= maximumChunkX;
                chunkX++) {
            for (int chunkZ = minimumChunkZ;
                    chunkZ <= maximumChunkZ;
                    chunkZ++) {
                if (!preparedChunks.contains(
                        ChunkPos.asLong(
                                chunkX,
                                chunkZ))) {
                    throw fatal(
                            structureId,
                            source,
                            "materialized entity AABB reaches an unprepared chunk ["
                                    + chunkX
                                    + ", "
                                    + chunkZ
                                    + "]");
                }
            }
        }
    }

    private static Vec3 offset(
            Vec3 relative,
            BlockPos origin) {
        Vec3 result = relative.add(
                origin.getX(),
                origin.getY(),
                origin.getZ());
        if (!finite(result)) {
            throw new PlacementPreparationException(
                    "Template entity position exceeds world coordinates");
        }
        return result;
    }

    private static BlockPos offsetExact(
            BlockPos origin,
            BlockPos relative) {
        try {
            return new BlockPos(
                    Math.addExact(
                            origin.getX(),
                            relative.getX()),
                    Math.addExact(
                            origin.getY(),
                            relative.getY()),
                    Math.addExact(
                            origin.getZ(),
                            relative.getZ()));
        } catch (ArithmeticException failure) {
            throw new PlacementPreparationException(
                    "Template entity anchor exceeds world coordinates",
                    failure);
        }
    }

    private static ListTag doubleList(
            Vec3 position) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(position.x));
        list.add(DoubleTag.valueOf(position.y));
        list.add(DoubleTag.valueOf(position.z));
        return list;
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    private static boolean valid(AABB box) {
        return Double.isFinite(box.minX)
                && Double.isFinite(box.minY)
                && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX)
                && Double.isFinite(box.maxY)
                && Double.isFinite(box.maxZ)
                && box.minX < box.maxX
                && box.minY < box.maxY
                && box.minZ < box.maxZ
                && box.minX
                        >= -Level.MAX_LEVEL_SIZE
                && box.maxX
                        <= Level.MAX_LEVEL_SIZE
                && box.minZ
                        >= -Level.MAX_LEVEL_SIZE
                && box.maxZ
                        <= Level.MAX_LEVEL_SIZE;
    }

    private static boolean samePosition(
            Vec3 first,
            Vec3 second) {
        return Double.compare(
                        first.x,
                        second.x)
                        == 0
                && Double.compare(
                                first.y,
                                second.y)
                        == 0
                && Double.compare(
                                first.z,
                                second.z)
                        == 0;
    }

    private static void skip(
            String structureId,
            StructureEntityData source,
            List<TemplateEntitySkip> skipped,
            String reason,
            RuntimeException failure) {
        TemplateEntitySkip diagnostic =
                new TemplateEntitySkip(
                        source.sourceIndex(),
                        source.authoredId(),
                        reason);
        skipped.add(diagnostic);
        String authoredId =
                diagnosticText(
                        diagnostic.authoredId());
        if (failure == null) {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Skipping template entity in structure '{}' at source index {} "
                            + "(authored id '{}'): {}",
                    structureId,
                    diagnostic.sourceIndex(),
                    authoredId,
                    reason);
        } else {
            AdaptiveStartingStructure.LOGGER.warn(
                    "Skipping template entity in structure '{}' at source index {} "
                            + "(authored id '{}'): {}",
                    structureId,
                    diagnostic.sourceIndex(),
                    authoredId,
                    reason,
                    failure);
        }
    }

    private static String failureReason(
            String prefix,
            RuntimeException failure) {
        String message =
                failure.getMessage();
        if (message == null
                || message.isBlank()) {
            return prefix
                    + " ("
                    + failure.getClass()
                            .getSimpleName()
                    + ")";
        }
        return prefix
                + " ("
                + failure.getClass()
                        .getSimpleName()
                + ": "
                + diagnosticText(
                        Optional.of(message))
                + ")";
    }

    private static PlacementPreparationException fatal(
            String structureId,
            StructureEntityData source,
            String reason) {
        return new PlacementPreparationException(
                diagnosticPrefix(
                        structureId,
                        source)
                        + reason);
    }

    private static String diagnosticPrefix(
            String structureId,
            StructureEntityData source) {
        return "Template entity in structure '"
                + structureId
                + "' at source index "
                + source.sourceIndex()
                + " (authored id '"
                + diagnosticText(
                        source.authoredId())
                + "') ";
    }

    static String diagnosticText(
            Optional<String> value) {
        if (value.isEmpty()) {
            return "<missing>";
        }
        String source = value.orElseThrow();
        StringBuilder result = new StringBuilder(
                Math.min(
                        source.length(),
                        MAX_DIAGNOSTIC_TEXT_LENGTH));
        boolean pendingSpace = false;
        boolean truncated = false;
        int offset = 0;
        while (offset < source.length()) {
            int codePoint = source.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            int required = Character.charCount(codePoint)
                    + (pendingSpace ? 1 : 0);
            if (result.length() + required
                    > MAX_DIAGNOSTIC_TEXT_LENGTH) {
                truncated = true;
                break;
            }
            if (pendingSpace) {
                result.append(' ');
                pendingSpace = false;
            }
            result.appendCodePoint(codePoint);
        }
        if (offset < source.length()) {
            truncated = true;
        }
        if (result.isEmpty()) {
            return "<blank>";
        }
        if (!truncated) {
            return result.toString();
        }

        int contentLimit =
                MAX_DIAGNOSTIC_TEXT_LENGTH - 3;
        if (result.length() > contentLimit) {
            result.setLength(contentLimit);
            if (Character.isHighSurrogate(
                    result.charAt(
                            result.length() - 1))) {
                result.setLength(
                        result.length() - 1);
            }
        }
        return result.append("...").toString();
    }

    private static void discardForSkip(
            Entity entity,
            RuntimeException originalFailure,
            String structureId,
            StructureEntityData source) {
        try {
            entity.discard();
        } catch (RuntimeException cleanupFailure) {
            if (originalFailure != null) {
                cleanupFailure.addSuppressed(
                        originalFailure);
            }
            throw new PlacementPreparationException(
                    diagnosticPrefix(
                            structureId,
                            source)
                            + "could not be discarded after failed materialization",
                    cleanupFailure);
        }
    }

    private static void discardForFatal(
            Entity entity,
            RuntimeException originalFailure) {
        try {
            entity.discard();
        } catch (RuntimeException cleanupFailure) {
            if (originalFailure != null) {
                cleanupFailure.addSuppressed(
                        originalFailure);
            }
            throw cleanupFailure;
        }
    }

    private static void discardAll(
            List<Entity> entities,
            Throwable failure) {
        for (int index = entities.size() - 1;
                index >= 0;
                index--) {
            Entity entity = entities.get(index);
            if (entity.isAddedToLevel()
                    || entity.isRemoved()) {
                continue;
            }
            try {
                entity.discard();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(
                        cleanupFailure);
            }
        }
    }
}
