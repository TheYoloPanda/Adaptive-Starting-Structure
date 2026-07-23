package com.typ.adaptivestartingstructure.structure;

import java.util.Objects;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class ValidatedStructureTemplate {
    private final StructureSource source;
    private final StructureTemplate template;
    private final CompoundTag verifiedNbt;
    private final Vec3i size;
    private final String sha256;
    private final String placementSha256;
    private final UnavailableBlockReport unavailableBlockReport;
    private final int sourceDataVersion;
    private final int loadedDataVersion;

    ValidatedStructureTemplate(
            StructureSource source,
            StructureTemplate template,
            CompoundTag verifiedNbt,
            Vec3i size,
            String sha256,
            String placementSha256,
            UnavailableBlockReport unavailableBlockReport,
            int sourceDataVersion,
            int loadedDataVersion) {
        this.source = Objects.requireNonNull(source, "source");
        this.template = Objects.requireNonNull(template, "template");
        this.verifiedNbt = Objects.requireNonNull(verifiedNbt, "verifiedNbt").copy();
        this.size = Objects.requireNonNull(size, "size");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.placementSha256 = Objects.requireNonNull(
                placementSha256,
                "placementSha256");
        this.unavailableBlockReport = Objects.requireNonNull(
                unavailableBlockReport,
                "unavailableBlockReport");
        this.sourceDataVersion = sourceDataVersion;
        this.loadedDataVersion = loadedDataVersion;
    }

    public StructureSource source() {
        return source;
    }

    public Vec3i size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    public String placementSha256() {
        return placementSha256;
    }

    public UnavailableBlockReport unavailableBlockReport() {
        return unavailableBlockReport;
    }

    public int sourceDataVersion() {
        return sourceDataVersion;
    }

    public int loadedDataVersion() {
        return loadedDataVersion;
    }

    StructureTemplate template() {
        return template;
    }

    CompoundTag verifiedNbt() {
        return verifiedNbt.copy();
    }
}
