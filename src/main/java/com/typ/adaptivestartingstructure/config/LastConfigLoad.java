package com.typ.adaptivestartingstructure.config;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The configuration as its last load or reload read it.
 *
 * <p>NeoForge reloads the file on its watcher thread while the server reads
 * the configuration on its own, so reading the values one by one at the moment
 * of use can mix two versions of the file in one snapshot.
 */
final class LastConfigLoad {
    private volatile Outcome last;

    ConfigSnapshot record(Supplier<ConfigSnapshot> read) {
        Objects.requireNonNull(read, "read");
        try {
            ConfigSnapshot snapshot = read.get();
            last = new Outcome(snapshot, null);
            return snapshot;
        } catch (IllegalArgumentException invalid) {
            last = new Outcome(null, invalid);
            throw invalid;
        } catch (RuntimeException unexpected) {
            last = null;
            throw unexpected;
        }
    }

    ConfigSnapshot snapshot(Supplier<ConfigSnapshot> beforeAnyLoad) {
        Objects.requireNonNull(beforeAnyLoad, "beforeAnyLoad");
        Outcome outcome = last;
        if (outcome == null) {
            return beforeAnyLoad.get();
        }
        if (outcome.failure() != null) {
            throw new IllegalArgumentException(
                    outcome.failure().getMessage(),
                    outcome.failure());
        }
        return outcome.snapshot();
    }

    private record Outcome(
            ConfigSnapshot snapshot,
            IllegalArgumentException failure) {
    }
}
