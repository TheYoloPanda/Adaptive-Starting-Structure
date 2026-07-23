package com.typ.adaptivestartingstructure.placement;

import com.typ.adaptivestartingstructure.persistence.StartingStructurePlan;
import net.minecraft.core.BlockPos;

final class TemplatePlacementSeeds {
    private static final long PALETTE_DOMAIN =
            0x50A1_E77E_5EED_0001L;
    private static final long LOOT_DOMAIN =
            0x1007_5EED_5EED_0001L;
    private static final long NON_ZERO_FALLBACK =
            0x6A09_E667_F3BC_C909L;

    private TemplatePlacementSeeds() {
    }

    static int paletteIndex(
            StartingStructurePlan plan,
            int paletteCount) {
        if (paletteCount <= 0) {
            throw new IllegalArgumentException(
                    "paletteCount must be positive");
        }
        long seed = baseSeed(plan) ^ PALETTE_DOMAIN;
        return (int) Math.floorMod(
                mix64(seed),
                paletteCount);
    }

    static long lootSeed(
            StartingStructurePlan plan,
            BlockPos position,
            int paletteIndex) {
        long seed = baseSeed(plan)
                ^ LOOT_DOMAIN
                ^ position.asLong()
                ^ Integer.toUnsignedLong(paletteIndex);
        long mixed = mix64(seed);
        return mixed == 0L ? NON_ZERO_FALLBACK : mixed;
    }

    private static long baseSeed(StartingStructurePlan plan) {
        long hashPrefix = Long.parseUnsignedLong(
                plan.candidate()
                        .structureSha256()
                        .substring(0, 16),
                16);
        return plan.selectionSeed()
                ^ Long.rotateLeft(plan.selectionSalt(), 21)
                ^ Long.rotateLeft(hashPrefix, 42)
                ^ plan.candidate().placementOrigin().asLong();
    }

    private static long mix64(long value) {
        long mixed = value;
        mixed = (mixed ^ mixed >>> 30)
                * 0xBF58_476D_1CE4_E5B9L;
        mixed = (mixed ^ mixed >>> 27)
                * 0x94D0_49BB_1331_11EBL;
        return mixed ^ mixed >>> 31;
    }
}
