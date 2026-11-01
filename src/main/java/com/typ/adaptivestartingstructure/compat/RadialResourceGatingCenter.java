package com.typ.adaptivestartingstructure.compat;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.Objects;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;

/**
 * Where Radial Resource Gating centers its overworld ore gate, read from its
 * loaded COMMON config through FML so that this mod needs neither its classes
 * nor a dependency entry on it.
 */
public record RadialResourceGatingCenter(
        Status status,
        int centerX,
        int centerZ,
        int range,
        String unreadableReason) {
    static final String MOD_ID = "radialresourcegating";
    static final String ENABLED_PATH = "overworld.enabled";
    static final String CENTER_MODE_PATH = "overworld.centerMode";
    static final String CENTER_X_PATH = "overworld.centerX";
    static final String CENTER_Z_PATH = "overworld.centerZ";
    static final String RANGE_PATH = "overworld.range";
    static final String FIXED_CENTER_MODE = "fixed";

    public RadialResourceGatingCenter {
        status = Objects.requireNonNull(status, "status");
        if ((status == Status.UNREADABLE) != (unreadableReason != null)) {
            throw new IllegalArgumentException(
                    "Only an unreadable config has a reason");
        }
    }

    public static RadialResourceGatingCenter notFixed() {
        return new RadialResourceGatingCenter(Status.NOT_FIXED, 0, 0, 0, null);
    }

    public static RadialResourceGatingCenter fixed(
            int centerX,
            int centerZ,
            int range) {
        return new RadialResourceGatingCenter(
                Status.FIXED, centerX, centerZ, range, null);
    }

    public static RadialResourceGatingCenter unreadable(String reason) {
        return new RadialResourceGatingCenter(
                Status.UNREADABLE,
                0,
                0,
                0,
                Objects.requireNonNull(reason, "reason"));
    }

    public static RadialResourceGatingCenter lookUp() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return notFixed();
        }
        return ModConfigs.getModConfigs(MOD_ID).stream()
                .filter(config -> config.getType() == ModConfig.Type.COMMON)
                .map(ModConfig::getLoadedConfig)
                .filter(Objects::nonNull)
                .findFirst()
                .map(loaded -> parse(loaded.config()))
                .orElseGet(() -> unreadable(
                        MOD_ID + " has no loaded COMMON config"));
    }

    static RadialResourceGatingCenter parse(UnmodifiableConfig config) {
        Object enabled = config.get(ENABLED_PATH);
        if (!(enabled instanceof Boolean gateEnabled)) {
            return unreadable(describe(ENABLED_PATH, enabled, "a boolean"));
        }
        if (!gateEnabled) {
            return notFixed();
        }
        Object centerMode = config.get(CENTER_MODE_PATH);
        if (!(centerMode instanceof String mode)) {
            return unreadable(describe(CENTER_MODE_PATH, centerMode, "a string"));
        }
        // Radial Resource Gating treats any value other than "fixed" as its
        // world_spawn default, so only "fixed" pins the gate away from the spawn.
        if (!FIXED_CENTER_MODE.equalsIgnoreCase(mode)) {
            return notFixed();
        }
        // Integers defined in range can come back as Integer or Long,
        // depending on how the file was written.
        Object centerX = config.get(CENTER_X_PATH);
        Object centerZ = config.get(CENTER_Z_PATH);
        Object range = config.get(RANGE_PATH);
        if (!(centerX instanceof Number x)) {
            return unreadable(describe(CENTER_X_PATH, centerX, "a number"));
        }
        if (!(centerZ instanceof Number z)) {
            return unreadable(describe(CENTER_Z_PATH, centerZ, "a number"));
        }
        if (!(range instanceof Number r)) {
            return unreadable(describe(RANGE_PATH, range, "a number"));
        }
        return fixed(x.intValue(), z.intValue(), r.intValue());
    }

    private static String describe(String path, Object value, String expected) {
        if (value == null) {
            return MOD_ID + " config has no " + path;
        }
        return MOD_ID + " config " + path + " is "
                + value.getClass().getSimpleName() + ", expected " + expected;
    }

    public enum Status {
        NOT_FIXED,
        FIXED,
        UNREADABLE
    }
}
