package com.typ.adaptivestartingstructure.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What a server should do with a world whose starting-structure state blocks
 * startup.
 *
 * <p>A JVM that dies mid-placement leaves the state at {@code PLACING} or
 * {@code FAILED}, and every later start refuses to run. That refusal is
 * deliberate, because the world may be half-modified, but without a way out
 * the only remedy is editing the saved data by hand. This is the admin's way
 * out, and it stays opt-in for the same reason the refusal exists.
 */
public enum BlockedStateRecovery {
    /** Refuse to start, the default. */
    BLOCK,
    /** Give up on the starting structure and let the world load as it is. */
    SKIP;

    public static final List<String> CONFIG_VALUES =
            List.of(BLOCK.configValue(), SKIP.configValue());

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BlockedStateRecovery fromConfigValue(String value) {
        Objects.requireNonNull(value, "value");
        for (BlockedStateRecovery recovery : values()) {
            if (recovery.configValue().equalsIgnoreCase(value.trim())) {
                return recovery;
            }
        }
        throw new IllegalArgumentException(
                "Invalid adaptive starting-structure configuration value for "
                        + "blockedStateRecovery: '" + value
                        + "' (expected one of " + CONFIG_VALUES + ")");
    }
}
