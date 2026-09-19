package com.typ.adaptivestartingstructure.planner;

import com.typ.adaptivestartingstructure.structure.StructureBounds;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;

public final class RealSpawnValidator {
    private RealSpawnValidator() {
    }

    public static SpawnValidationResult validate(
            ServerLevel level,
            BlockPos spawnFeet,
            StructureBounds safeBounds) {
        return validate(level, spawnFeet, safeBounds, true);
    }

    public static SpawnValidationResult validate(
            ServerLevel level,
            BlockPos spawnFeet,
            StructureBounds safeBounds,
            boolean requireSafeSpawnArea) {
        Objects.requireNonNull(level, "level");
        return validate(
                requireSafeSpawnArea
                        ? level.getGameRules().getInt(GameRules.RULE_SPAWN_RADIUS)
                        : 0,
                spawnFeet,
                safeBounds,
                level);
    }

    public static SpawnValidationResult validate(
            ServerLevel level,
            BlockPos spawnFeet,
            StructureBounds safeBounds,
            int configuredRadius) {
        Objects.requireNonNull(level, "level");
        return validate(
                configuredRadius,
                spawnFeet,
                safeBounds,
                level);
    }

    static SpawnValidationResult validate(
            GameRules gameRules,
            BlockPos spawnFeet,
            StructureBounds safeBounds,
            BlockGetter world) {
        Objects.requireNonNull(gameRules, "gameRules");
        return validate(
                gameRules.getInt(GameRules.RULE_SPAWN_RADIUS),
                spawnFeet,
                safeBounds,
                world);
    }

    static SpawnValidationResult validate(
            int configuredRadius,
            BlockPos spawnFeet,
            StructureBounds safeBounds,
            BlockGetter world) {
        Objects.requireNonNull(world, "world");
        return SpawnAreaValidator.validate(
                configuredRadius,
                spawnFeet,
                safeBounds,
                () -> List.of(world));
    }
}
