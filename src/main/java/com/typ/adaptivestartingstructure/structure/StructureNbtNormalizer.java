package com.typ.adaptivestartingstructure.structure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

final class StructureNbtNormalizer {
    private static final String PLACEMENT_HASH_DOMAIN =
            "adaptive_starting_structure:placement:v2\n";
    private static final CompoundTag AIR_PALETTE_STATE =
            NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState());
    private static final Comparator<AirReplacement> AIR_REPLACEMENT_ORDER =
            Comparator.comparingInt((AirReplacement block) -> block.x)
                    .thenComparingInt(block -> block.y)
                    .thenComparingInt(block -> block.z)
                    .thenComparingInt(block -> block.stateIndex);

    private StructureNbtNormalizer() {
    }

    static Result normalize(
            CompoundTag validatedRoot,
            StructureNbtValidation validation,
            String sourceSha256) {
        CompoundTag normalized = validatedRoot.copy();
        replaceUnavailablePaletteStatesWithAir(normalized, validation);

        ListTag sourceBlocks = normalized.getList("blocks", CompoundTag.TAG_COMPOUND);
        ListTag retainedBlocks = new ListTag();
        List<AirReplacement> airReplacements = new ArrayList<>();
        Set<ResourceLocation> unavailableBlockIds = new LinkedHashSet<>();
        for (int index = 0; index < sourceBlocks.size(); index++) {
            CompoundTag block = sourceBlocks.getCompound(index);
            CompoundTag retainedBlock = block.copy();
            int stateIndex = block.getInt("state");
            if (validation.isUnavailable(stateIndex)) {
                retainedBlock.remove("nbt");
                ListTag position = block.getList("pos", CompoundTag.TAG_INT);
                airReplacements.add(new AirReplacement(
                        position.getInt(0),
                        position.getInt(1),
                        position.getInt(2),
                        stateIndex));
                unavailableBlockIds.addAll(
                        validation.unavailableBlocksByStateIndex().get(stateIndex));
            }
            retainedBlocks.add(retainedBlock);
        }
        normalized.put("blocks", retainedBlocks);
        airReplacements.sort(AIR_REPLACEMENT_ORDER);

        UnavailableBlockReport report = airReplacements.isEmpty()
                ? UnavailableBlockReport.EMPTY
                : new UnavailableBlockReport(
                        List.copyOf(unavailableBlockIds),
                        airReplacements.size());
        String placementSha256 = airReplacements.isEmpty()
                ? sourceSha256
                : placementSha256(sourceSha256, airReplacements);
        return new Result(normalized, placementSha256, report);
    }

    private static void replaceUnavailablePaletteStatesWithAir(
            CompoundTag root,
            StructureNbtValidation validation) {
        if (root.contains("palettes", CompoundTag.TAG_LIST)) {
            ListTag palettes = root.getList("palettes", CompoundTag.TAG_LIST);
            for (int paletteIndex = 0; paletteIndex < palettes.size(); paletteIndex++) {
                replaceUnavailablePaletteStatesWithAir(
                        palettes.getList(paletteIndex),
                        validation);
            }
            return;
        }
        replaceUnavailablePaletteStatesWithAir(
                root.getList("palette", CompoundTag.TAG_COMPOUND),
                validation);
    }

    private static void replaceUnavailablePaletteStatesWithAir(
            ListTag palette,
            StructureNbtValidation validation) {
        for (int stateIndex : validation.unavailableBlocksByStateIndex().keySet()) {
            palette.set(stateIndex, AIR_PALETTE_STATE.copy());
        }
    }

    private static String placementSha256(
            String sourceSha256,
            List<AirReplacement> airReplacements) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(PLACEMENT_HASH_DOMAIN.getBytes(StandardCharsets.UTF_8));
            digest.update(("source=" + sourceSha256 + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            for (AirReplacement block : airReplacements) {
                digest.update(("air=" + block.x + "," + block.y + ","
                                + block.z + "," + block.stateIndex + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Required SHA-256 algorithm is unavailable",
                    exception);
        }
    }

    record Result(
            CompoundTag normalizedNbt,
            String placementSha256,
            UnavailableBlockReport unavailableBlockReport) {
        Result {
            normalizedNbt = normalizedNbt.copy();
        }

        @Override
        public CompoundTag normalizedNbt() {
            return normalizedNbt.copy();
        }
    }

    private record AirReplacement(int x, int y, int z, int stateIndex) {
    }
}
