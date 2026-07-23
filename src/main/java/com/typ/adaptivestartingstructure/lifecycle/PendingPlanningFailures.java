package com.typ.adaptivestartingstructure.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

final class PendingPlanningFailures<S> {
    private final Map<S, PlanningFailureNotice> notices =
            new WeakHashMap<>();

    synchronized boolean queue(
            S server,
            PlanningFailureNotice notice) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(notice, "notice");
        if (notices.containsKey(server)) {
            return false;
        }
        notices.put(server, notice);
        return true;
    }

    synchronized boolean deliver(
            S server,
            Consumer<Component> recipient) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(recipient, "recipient");

        PlanningFailureNotice notice = notices.get(server);
        if (notice == null) {
            return false;
        }

        recipient.accept(notice.message());
        return notices.remove(server, notice);
    }

    synchronized void clear(S server) {
        notices.remove(Objects.requireNonNull(server, "server"));
    }

    synchronized boolean hasPending(S server) {
        return notices.containsKey(Objects.requireNonNull(server, "server"));
    }
}
