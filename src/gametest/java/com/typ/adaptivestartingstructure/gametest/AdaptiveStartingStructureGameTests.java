package com.typ.adaptivestartingstructure.gametest;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AdaptiveStartingStructure.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdaptiveStartingStructureGameTests {
    private static final BlockPos EXPECTED_SPAWN =
            new BlockPos(8, -59, 8);

    private AdaptiveStartingStructureGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void completesInitialPlacementBeforeTestsRun(
            GameTestHelper helper) {
        ServerLevel overworld =
                helper.getLevel().getServer().overworld();
        StartingStructureSavedData data =
                StartingStructureStorage.load(overworld)
                        .orElseThrow(() -> failure(
                                "The first-start lifecycle did not persist SavedData"));

        require(
                data.state()
                        == StartingStructureSavedData.State.COMPLETE,
                "Expected COMPLETE before GameTests, found "
                        + data.state());
        require(
                data.plan().candidate().structureId()
                        .equals("integration"),
                "The deterministic GameTest fixture was not selected");
        require(
                data.plan().candidate().worldSpawn()
                        .equals(EXPECTED_SPAWN),
                "The planned spawn changed: "
                        + data.plan().candidate().worldSpawn());
        require(
                overworld.getSharedSpawnPos()
                        .equals(EXPECTED_SPAWN),
                "The live world spawn differs from the persisted plan");
        require(
                overworld.getGameRules()
                                .getInt(GameRules.RULE_SPAWN_RADIUS)
                        == 10,
                "The vanilla spawnRadius gamerule was modified");

        BlockPos placementOrigin =
                data.plan().candidate().placementOrigin();
        require(
                overworld.getBlockState(placementOrigin)
                        .is(Blocks.GOLD_BLOCK),
                "The template floor was not placed at its persisted origin");
        require(
                overworld.getBlockState(
                                placementOrigin.offset(24, 0, 24))
                        .is(Blocks.GOLD_BLOCK),
                "The opposite template corner was not placed");
        require(
                overworld.getBlockState(
                                placementOrigin.offset(0, 1, 0))
                        .isAir(),
                "The unavailable template block was not replaced with air");
        require(
                helper.getLevel().getServer().getPlayerCount() == 0,
                "Placement verification did not finish before players");
        require(
                !containsChestNearSpawn(overworld),
                "A bonus chest was generated after the spawn event was canceled");

        helper.succeed();
    }

    private static boolean containsChestNearSpawn(
            ServerLevel level) {
        int minimumY = level.getMinBuildHeight();
        int maximumY = Math.min(
                level.getMaxBuildHeight() - 1,
                EXPECTED_SPAWN.getY() + 8);
        for (int x = EXPECTED_SPAWN.getX() - 16;
                x <= EXPECTED_SPAWN.getX() + 16;
                x++) {
            for (int z = EXPECTED_SPAWN.getZ() - 16;
                    z <= EXPECTED_SPAWN.getZ() + 16;
                    z++) {
                for (int y = minimumY; y <= maximumY; y++) {
                    if (level.getBlockState(
                                    new BlockPos(x, y, z))
                            .is(Blocks.CHEST)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void require(
            boolean condition,
            String message) {
        if (!condition) {
            throw failure(message);
        }
    }

    private static GameTestAssertException failure(
            String message) {
        return new GameTestAssertException(message);
    }
}
