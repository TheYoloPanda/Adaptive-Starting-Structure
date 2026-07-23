package com.typ.adaptivestartingstructure.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

final class PendingPlacementFailures<S> {
    private final Map<S, PlacementFailureNotice> notices =
            new WeakHashMap<>();

    synchronized boolean queue(
            S server,
            PlacementFailureNotice notice) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(notice, "notice");
        if (notices.containsKey(server)) {
            return false;
        }
        notices.put(server, notice);
        return true;
    }

    synchronized Optional<PlacementFailureNotice> find(S server) {
        return Optional.ofNullable(
                notices.get(Objects.requireNonNull(server, "server")));
    }

    synchronized void clear(S server) {
        notices.remove(Objects.requireNonNull(server, "server"));
    }

    synchronized boolean hasPending(S server) {
        return notices.containsKey(Objects.requireNonNull(server, "server"));
    }
}
