package com.typ.adaptivestartingstructure.gametest;

import com.typ.adaptivestartingstructure.AdaptiveStartingStructure;
import com.typ.adaptivestartingstructure.lifecycle.VanillaSpawnFallback;
import com.typ.adaptivestartingstructure.persistence.FallbackDecision;
import com.typ.adaptivestartingstructure.persistence.StartingStructureSavedData;
import com.typ.adaptivestartingstructure.persistence.StartingStructureStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void fallbackDecisionIsPreWriteAndTerminal(
            GameTestHelper helper) {
        ServerLevel overworld =
                helper.getLevel().getServer().overworld();
        StartingStructureSavedData completed =
                StartingStructureStorage.load(overworld)
                        .orElseThrow(() -> failure(
                                "The first-start lifecycle did not persist SavedData"));
        StartingStructureSavedData decisionData =
                StartingStructureSavedData.planned(
                        completed.plan());
        BlockPos observedPosition =
                helper.absolutePos(
                        new BlockPos(1, 1, 1));
        var before = overworld.getBlockState(
                observedPosition);
        BlockPos first =
                VanillaSpawnFallback.resolve(overworld);
        BlockPos second =
                VanillaSpawnFallback.resolve(overworld);

        require(
                first.equals(second),
                "Vanilla fallback spawn was not deterministic");
        require(
                first.getY()
                                >= overworld.getMinBuildHeight()
                        && first.getY()
                                < overworld
                                        .getMaxBuildHeight(),
                "Vanilla fallback spawn was outside build limits: "
                        + first);

        FallbackDecision fallback =
                new FallbackDecision(
                        first,
                        false,
                        "controlled GameTest safe-site exhaustion");
        decisionData.markAwaitingDecision(fallback);
        require(
                decisionData.state()
                        == StartingStructureSavedData.State
                                .AWAITING_DECISION,
                "Expected AWAITING_DECISION");
        require(
                decisionData.fallbackDecision()
                        .orElseThrow()
                        .vanillaSpawn()
                        .equals(first),
                "Fallback spawn was not retained");
        require(
                overworld.getBlockState(observedPosition)
                        .equals(before),
                "AWAITING_DECISION modified the test world");

        decisionData.markFallbackApplying();
        decisionData.markSkipped();
        require(
                decisionData.state()
                        == StartingStructureSavedData.State.SKIPPED,
                "Expected terminal SKIPPED state");
        require(
                !decisionData.fallbackDecision()
                        .orElseThrow()
                        .generateBonusChest(),
                "Disabled Bonus Chest choice was not retained");
        require(
                overworld.getBlockState(observedPosition)
                        .equals(before),
                "State-only fallback test modified the test world");

        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void templateEntitiesAreRestoredInSourceOrder(
            GameTestHelper helper) {
        ServerLevel overworld =
                helper.getLevel().getServer().overworld();
        StartingStructureSavedData data =
                StartingStructureStorage.load(overworld)
                        .orElseThrow(() -> failure(
                                "The first-start lifecycle did not persist SavedData"));
        BlockPos origin =
                data.plan()
                        .candidate()
                        .placementOrigin();
        List<Entity> entities =
                fixtureEntities(overworld);

        require(
                entities.size() == 4,
                "Expected four template entities, found "
                        + entities.size());
        Entity armorStand =
                fixtureEntity(
                        entities,
                        "source_0");
        Entity pig =
                fixtureEntity(
                        entities,
                        "source_1");
        Entity minecart =
                fixtureEntity(
                        entities,
                        "source_2");
        Entity itemFrame =
                fixtureEntity(
                        entities,
                        "source_3");

        require(
                armorStand.getType()
                        == EntityType.ARMOR_STAND,
                "source_0 did not retain its armor-stand type");
        require(
                pig.getType()
                        == EntityType.PIG,
                "source_1 did not retain its pig type");
        require(
                minecart.getType()
                        == EntityType.MINECART,
                "source_2 did not retain its minecart type");
        require(
                itemFrame.getType()
                        == EntityType.ITEM_FRAME,
                "source_3 did not retain its item-frame type");
        requirePosition(
                armorStand,
                new Vec3(
                        origin.getX() + 6.25,
                        origin.getY() + 1.0,
                        origin.getZ() + 6.75));
        requirePosition(
                pig,
                new Vec3(
                        origin.getX() + 9.5,
                        origin.getY() + 1.0,
                        origin.getZ() + 9.5));
        requirePosition(
                minecart,
                new Vec3(
                        origin.getX() + 12.5,
                        origin.getY() + 1.25,
                        origin.getZ() + 9.5));
        require(
                Math.abs(
                                armorStand.getYRot()
                                        - 35.0F)
                        < 0.001F,
                "Armor-stand authored yaw changed: "
                        + armorStand.getYRot());
        require(
                Math.abs(
                                pig.getYRot()
                                        - 90.0F)
                        < 0.001F,
                "Pig authored yaw changed: "
                        + pig.getYRot());
        require(
                armorStand.isNoGravity()
                        && pig.isNoGravity()
                        && minecart.isNoGravity(),
                "Authored NoGravity state was not retained");
        require(
                armorStand.getId()
                                < pig.getId()
                        && pig.getId()
                                < minecart.getId()
                        && minecart.getId()
                                < itemFrame.getId(),
                "Template entity source order was not retained");
        require(
                entities.stream()
                                .map(Entity::getUUID)
                                .distinct()
                                .count()
                        == entities.size(),
                "Template entities do not have distinct runtime UUIDs");

        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void blockAttachedEntitySurvivesPeriodicCheck(
            GameTestHelper helper) {
        ServerLevel overworld =
                helper.getLevel().getServer().overworld();
        StartingStructureSavedData data =
                StartingStructureStorage.load(overworld)
                        .orElseThrow(() -> failure(
                                "The first-start lifecycle did not persist SavedData"));
        BlockPos origin =
                data.plan()
                        .candidate()
                        .placementOrigin();
        BlockPos expectedAnchor =
                origin.offset(
                        15,
                        1,
                        15);

        helper.runAfterDelay(105L, () -> {
            Entity entity =
                    fixtureEntity(
                            fixtureEntities(overworld),
                            "source_3");
            require(
                    entity instanceof ItemFrame,
                    "source_3 is not an item frame after 105 ticks");
            ItemFrame itemFrame =
                    (ItemFrame) entity;
            require(
                    !itemFrame.isRemoved(),
                    "Item frame was removed by its periodic attachment check");
            require(
                    itemFrame.getPos()
                            .equals(expectedAnchor),
                    "Item-frame anchor changed: "
                            + itemFrame.getPos());
            require(
                    itemFrame.getDirection()
                            == Direction.SOUTH,
                    "Item-frame facing changed: "
                            + itemFrame.getDirection());
            require(
                    itemFrame.survives(),
                    "Item frame no longer survives against the final blocks");
            require(
                    overworld.getBlockState(
                                    origin.offset(
                                            15,
                                            1,
                                            14))
                            .is(Blocks.GOLD_BLOCK),
                    "Item-frame support block is missing");
            helper.succeed();
        });
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

    private static List<Entity> fixtureEntities(
            ServerLevel level) {
        List<Entity> entities =
                new ArrayList<>();
        for (Entity entity
                : level.getAllEntities()) {
            if (entity.getTags()
                    .contains(
                            "adaptive_entity_fixture")) {
                entities.add(entity);
            }
        }
        entities.sort(
                Comparator.comparingInt(
                        Entity::getId));
        return List.copyOf(entities);
    }

    private static Entity fixtureEntity(
            List<Entity> entities,
            String sourceTag) {
        return entities.stream()
                .filter(entity ->
                        entity.getTags()
                                .contains(
                                        sourceTag))
                .findFirst()
                .orElseThrow(() -> failure(
                        "Missing template entity tagged "
                                + sourceTag));
    }

    private static void requirePosition(
            Entity entity,
            Vec3 expected) {
        require(
                entity.position()
                                .distanceToSqr(
                                        expected)
                        < 1.0E-12,
                "Entity "
                        + entity.getType()
                        + " position changed: expected "
                        + expected
                        + ", found "
                        + entity.position());
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
