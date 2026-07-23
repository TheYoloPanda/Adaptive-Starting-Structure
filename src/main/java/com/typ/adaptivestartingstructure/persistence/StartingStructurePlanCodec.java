package com.typ.adaptivestartingstructure.persistence;

import com.typ.adaptivestartingstructure.planner.BiomeClassifier;
import com.typ.adaptivestartingstructure.planner.CoarseRejectionReason;
import com.typ.adaptivestartingstructure.planner.FineCandidateMetrics;
import com.typ.adaptivestartingstructure.planner.FineCandidatePlan;
import com.typ.adaptivestartingstructure.planner.FineRejectionReason;
import com.typ.adaptivestartingstructure.planner.SiteCandidate;
import com.typ.adaptivestartingstructure.planner.SitePlanningDiagnostics;
import com.typ.adaptivestartingstructure.planner.SpawnRejectionReason;
import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;

final class StartingStructurePlanCodec {
    private static final String SELECTION_SEED = "SelectionSeed";
    private static final String SELECTION_SALT = "SelectionSalt";
    private static final String SELECTION_ALGORITHM_VERSION = "SelectionAlgorithmVersion";
    private static final String CANDIDATE = "Candidate";
    private static final String ALTERNATIVE_CANDIDATES = "AlternativeCandidates";
    private static final String DIAGNOSTICS = "Diagnostics";

    private StartingStructurePlanCodec() {
    }

    static CompoundTag encode(StartingStructurePlan plan) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(SELECTION_SEED, plan.selectionSeed());
        tag.putLong(SELECTION_SALT, plan.selectionSalt());
        tag.putInt(
                SELECTION_ALGORITHM_VERSION,
                plan.selectionAlgorithmVersion());
        tag.put(CANDIDATE, encodeCandidate(plan.candidate()));
        ListTag alternatives = new ListTag();
        plan.alternativeCandidates().stream()
                .map(StartingStructurePlanCodec::encodeCandidate)
                .forEach(alternatives::add);
        tag.put(ALTERNATIVE_CANDIDATES, alternatives);
        tag.put(DIAGNOSTICS, encodeDiagnostics(plan.diagnostics()));
        return tag;
    }

    static StartingStructurePlan decode(CompoundTag tag) {
        try {
            return new StartingStructurePlan(
                    requireLong(tag, SELECTION_SEED, "Plan"),
                    requireLong(tag, SELECTION_SALT, "Plan"),
                    requireInt(tag, SELECTION_ALGORITHM_VERSION, "Plan"),
                    decodeCandidate(requireCompound(tag, CANDIDATE, "Plan")),
                    decodeAlternativeCandidates(tag),
                    decodeDiagnostics(requireCompound(tag, DIAGNOSTICS, "Plan")));
        } catch (StartingStructureDataException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new StartingStructureDataException(
                    "Invalid persisted starting-structure plan: "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static List<SiteCandidate> decodeAlternativeCandidates(
            CompoundTag tag) {
        if (!tag.contains(ALTERNATIVE_CANDIDATES)) {
            return List.of();
        }
        requireType(
                tag,
                ALTERNATIVE_CANDIDATES,
                Tag.TAG_LIST,
                "Plan");
        ListTag encoded = tag.getList(
                ALTERNATIVE_CANDIDATES,
                Tag.TAG_COMPOUND);
        List<SiteCandidate> candidates =
                new ArrayList<>(encoded.size());
        for (int index = 0; index < encoded.size(); index++) {
            candidates.add(decodeCandidate(
                    encoded.getCompound(index)));
        }
        return List.copyOf(candidates);
    }

    private static CompoundTag encodeCandidate(SiteCandidate candidate) {
        CompoundTag tag = new CompoundTag();
        tag.putString("StructureId", candidate.structureId());
        tag.putString("StructureSha256", candidate.structureSha256());
        tag.putInt("CenterX", candidate.centerX());
        tag.putInt("CenterZ", candidate.centerZ());
        tag.putString("Rotation", candidate.rotation().name());
        tag.put("TerrainPlan", encodeTerrainPlan(candidate.terrainPlan()));
        tag.put("WorldSpawn", encodePosition(candidate.worldSpawn()));
        tag.put("Metrics", encodeFineMetrics(candidate.metrics()));
        tag.putDouble("FitCost", candidate.fitCost());
        tag.putLong("DistanceSquared", candidate.distanceSquared());
        tag.putInt("ConfiguredSpawnRadius", candidate.configuredSpawnRadius());
        tag.putInt("EffectiveSpawnRadius", candidate.effectiveSpawnRadius());
        tag.putInt("ValidatedSpawnColumns", candidate.validatedSpawnColumns());
        return tag;
    }

    private static SiteCandidate decodeCandidate(CompoundTag tag) {
        return new SiteCandidate(
                requireString(tag, "StructureId", "Plan.Candidate"),
                requireString(tag, "StructureSha256", "Plan.Candidate"),
                requireInt(tag, "CenterX", "Plan.Candidate"),
                requireInt(tag, "CenterZ", "Plan.Candidate"),
                requireEnum(
                        Rotation.class,
                        requireString(tag, "Rotation", "Plan.Candidate"),
                        "Plan.Candidate.Rotation"),
                decodeTerrainPlan(requireCompound(
                        tag,
                        "TerrainPlan",
                        "Plan.Candidate")),
                decodePosition(requireCompound(
                        tag,
                        "WorldSpawn",
                        "Plan.Candidate"),
                        "Plan.Candidate.WorldSpawn"),
                decodeFineMetrics(requireCompound(
                        tag,
                        "Metrics",
                        "Plan.Candidate")),
                requireFiniteDouble(tag, "FitCost", "Plan.Candidate"),
                requireLong(tag, "DistanceSquared", "Plan.Candidate"),
                requireInt(tag, "ConfiguredSpawnRadius", "Plan.Candidate"),
                requireInt(tag, "EffectiveSpawnRadius", "Plan.Candidate"),
                requireInt(tag, "ValidatedSpawnColumns", "Plan.Candidate"));
    }

    private static CompoundTag encodeTerrainPlan(FineCandidatePlan plan) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("GroundSurfaceY", plan.groundSurfaceY());
        tag.put("PlacementOrigin", encodePosition(plan.placementOrigin()));
        tag.put("StructureBounds", encodeBounds(plan.structureBounds()));
        tag.putInt("MinimumBlendX", plan.minimumBlendX());
        tag.putInt("MaximumBlendX", plan.maximumBlendX());
        tag.putInt("MinimumBlendZ", plan.minimumBlendZ());
        tag.putInt("MaximumBlendZ", plan.maximumBlendZ());
        return tag;
    }

    private static FineCandidatePlan decodeTerrainPlan(CompoundTag tag) {
        String path = "Plan.Candidate.TerrainPlan";
        return new FineCandidatePlan(
                requireInt(tag, "GroundSurfaceY", path),
                decodePosition(
                        requireCompound(tag, "PlacementOrigin", path),
                        path + ".PlacementOrigin"),
                decodeBounds(
                        requireCompound(tag, "StructureBounds", path),
                        path + ".StructureBounds"),
                requireInt(tag, "MinimumBlendX", path),
                requireInt(tag, "MaximumBlendX", path),
                requireInt(tag, "MinimumBlendZ", path),
                requireInt(tag, "MaximumBlendZ", path));
    }

    private static CompoundTag encodeFineMetrics(FineCandidateMetrics metrics) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("FootprintSampleCount", metrics.footprintSampleCount());
        tag.putInt("PerimeterSampleCount", metrics.perimeterSampleCount());
        tag.putInt("BlendSampleCount", metrics.blendSampleCount());
        tag.putInt("TotalSampleCount", metrics.totalSampleCount());
        tag.putInt("MinimumGroundY", metrics.minimumGroundY());
        tag.putInt("MaximumGroundY", metrics.maximumGroundY());
        tag.putDouble("AverageError", metrics.averageError());
        tag.putInt("MaximumPerimeterError", metrics.maximumPerimeterError());
        tag.putInt("MaximumCutDepth", metrics.maximumCutDepth());
        tag.putInt("MaximumFillDepth", metrics.maximumFillDepth());
        tag.putDouble("AverageCut", metrics.averageCut());
        tag.putDouble("AverageFill", metrics.averageFill());
        tag.putInt("WaterColumns", metrics.waterColumns());
        tag.putString(
                "BiomeClassification",
                metrics.biomeClassification().name());
        return tag;
    }

    private static FineCandidateMetrics decodeFineMetrics(CompoundTag tag) {
        String path = "Plan.Candidate.Metrics";
        return new FineCandidateMetrics(
                requireInt(tag, "FootprintSampleCount", path),
                requireInt(tag, "PerimeterSampleCount", path),
                requireInt(tag, "BlendSampleCount", path),
                requireInt(tag, "TotalSampleCount", path),
                requireInt(tag, "MinimumGroundY", path),
                requireInt(tag, "MaximumGroundY", path),
                requireFiniteDouble(tag, "AverageError", path),
                requireInt(tag, "MaximumPerimeterError", path),
                requireInt(tag, "MaximumCutDepth", path),
                requireInt(tag, "MaximumFillDepth", path),
                requireFiniteDouble(tag, "AverageCut", path),
                requireFiniteDouble(tag, "AverageFill", path),
                requireInt(tag, "WaterColumns", path),
                requireEnum(
                        BiomeClassifier.Classification.class,
                        requireString(tag, "BiomeClassification", path),
                        path + ".BiomeClassification"));
    }

    private static CompoundTag encodeDiagnostics(
            SitePlanningDiagnostics diagnostics) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(
                "CoarseEvaluationCount",
                diagnostics.coarseEvaluationCount());
        tag.putInt(
                "CoarseCandidateCount",
                diagnostics.coarseCandidateCount());
        tag.putInt(
                "CoarseRejectedCandidateCount",
                diagnostics.coarseRejectedCandidateCount());
        tag.putInt("FineEvaluationCount", diagnostics.fineEvaluationCount());
        tag.putInt("FineAcceptedCount", diagnostics.fineAcceptedCount());
        tag.putInt(
                "SpawnValidationCount",
                diagnostics.spawnValidationCount());
        tag.putInt(
                "SpawnRejectedCandidateCount",
                diagnostics.spawnRejectedCandidateCount());
        tag.putInt(
                "GeneratorQueriesUsed",
                diagnostics.generatorQueriesUsed());
        tag.put(
                "CoarseRejectionCounts",
                encodeEnumCounts(diagnostics.coarseRejectionCounts()));
        tag.put(
                "FineRejectionCounts",
                encodeEnumCounts(diagnostics.fineRejectionCounts()));
        tag.put(
                "SpawnRejectionCounts",
                encodeEnumCounts(diagnostics.spawnRejectionCounts()));
        return tag;
    }

    private static SitePlanningDiagnostics decodeDiagnostics(CompoundTag tag) {
        String path = "Plan.Diagnostics";
        return new SitePlanningDiagnostics(
                requireInt(tag, "CoarseEvaluationCount", path),
                requireInt(tag, "CoarseCandidateCount", path),
                requireInt(tag, "CoarseRejectedCandidateCount", path),
                requireInt(tag, "FineEvaluationCount", path),
                requireInt(tag, "FineAcceptedCount", path),
                requireInt(tag, "SpawnValidationCount", path),
                requireInt(tag, "SpawnRejectedCandidateCount", path),
                requireInt(tag, "GeneratorQueriesUsed", path),
                decodeEnumCounts(
                        requireCompound(tag, "CoarseRejectionCounts", path),
                        CoarseRejectionReason.class,
                        path + ".CoarseRejectionCounts"),
                decodeEnumCounts(
                        requireCompound(tag, "FineRejectionCounts", path),
                        FineRejectionReason.class,
                        path + ".FineRejectionCounts"),
                decodeEnumCounts(
                        requireCompound(tag, "SpawnRejectionCounts", path),
                        SpawnRejectionReason.class,
                        path + ".SpawnRejectionCounts"));
    }

    private static CompoundTag encodePosition(BlockPos position) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", position.getX());
        tag.putInt("Y", position.getY());
        tag.putInt("Z", position.getZ());
        return tag;
    }

    private static BlockPos decodePosition(CompoundTag tag, String path) {
        return new BlockPos(
                requireInt(tag, "X", path),
                requireInt(tag, "Y", path),
                requireInt(tag, "Z", path));
    }

    private static CompoundTag encodeBounds(StructureBounds bounds) {
        CompoundTag tag = new CompoundTag();
        tag.put("Minimum", encodePosition(bounds.minimum()));
        tag.put("Maximum", encodePosition(bounds.maximum()));
        return tag;
    }

    private static StructureBounds decodeBounds(CompoundTag tag, String path) {
        return new StructureBounds(
                decodePosition(
                        requireCompound(tag, "Minimum", path),
                        path + ".Minimum"),
                decodePosition(
                        requireCompound(tag, "Maximum", path),
                        path + ".Maximum"));
    }

    private static <E extends Enum<E>> CompoundTag encodeEnumCounts(
            Map<E, Integer> counts) {
        CompoundTag tag = new CompoundTag();
        counts.forEach((reason, count) -> tag.putInt(reason.name(), count));
        return tag;
    }

    private static <E extends Enum<E>> Map<E, Integer> decodeEnumCounts(
            CompoundTag tag,
            Class<E> enumType,
            String path) {
        EnumMap<E, Integer> counts = new EnumMap<>(enumType);
        for (String key : tag.getAllKeys()) {
            E reason = requireEnum(enumType, key, path);
            int count = requireInt(tag, key, path);
            if (count <= 0) {
                throw invalid(path + "." + key, "must be positive");
            }
            counts.put(reason, count);
        }
        return counts;
    }

    private static CompoundTag requireCompound(
            CompoundTag tag,
            String key,
            String path) {
        requireType(tag, key, Tag.TAG_COMPOUND, path);
        return tag.getCompound(key);
    }

    private static String requireString(
            CompoundTag tag,
            String key,
            String path) {
        requireType(tag, key, Tag.TAG_STRING, path);
        String value = tag.getString(key);
        if (value.isBlank()) {
            throw invalid(path + "." + key, "must not be blank");
        }
        return value;
    }

    private static int requireInt(
            CompoundTag tag,
            String key,
            String path) {
        requireType(tag, key, Tag.TAG_INT, path);
        return tag.getInt(key);
    }

    private static long requireLong(
            CompoundTag tag,
            String key,
            String path) {
        requireType(tag, key, Tag.TAG_LONG, path);
        return tag.getLong(key);
    }

    private static double requireFiniteDouble(
            CompoundTag tag,
            String key,
            String path) {
        requireType(tag, key, Tag.TAG_DOUBLE, path);
        double value = tag.getDouble(key);
        if (!Double.isFinite(value)) {
            throw invalid(path + "." + key, "must be finite");
        }
        return value;
    }

    private static void requireType(
            CompoundTag tag,
            String key,
            int type,
            String path) {
        if (!tag.contains(key)) {
            throw invalid(path + "." + key, "is missing");
        }
        if (!tag.contains(key, type)) {
            throw invalid(path + "." + key, "has the wrong NBT type");
        }
    }

    private static <E extends Enum<E>> E requireEnum(
            Class<E> enumType,
            String value,
            String path) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(path, "contains unknown value '" + value + "'");
        }
    }

    private static StartingStructureDataException invalid(
            String path,
            String problem) {
        return new StartingStructureDataException(
                "Invalid persisted field " + path + ": " + problem);
    }
}
