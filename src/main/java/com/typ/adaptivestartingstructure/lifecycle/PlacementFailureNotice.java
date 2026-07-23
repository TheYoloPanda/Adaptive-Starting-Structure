package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.placement.UnsuitableGeneratedSiteException;
import java.util.Objects;
import net.minecraft.network.chat.Component;

record PlacementFailureNotice(Component reason) {
    static final String MESSAGE_KEY =
            "disconnect.adaptive_starting_structure.placement_failure";
    static final String NO_SAFE_SITE_KEY =
            "disconnect.adaptive_starting_structure.reason.no_safe_site";
    static final String PREVIOUS_FAILURE_KEY =
            "disconnect.adaptive_starting_structure.reason.previous_failure";
    static final String POSSIBLE_PARTIAL_PLACEMENT_KEY =
            "disconnect.adaptive_starting_structure.reason.possible_partial_placement";
    static final String UNEXPECTED_KEY =
            "disconnect.adaptive_starting_structure.reason.unexpected";

    PlacementFailureNotice {
        reason = Objects.requireNonNull(reason, "reason").copy();
    }

    static PlacementFailureNotice from(Exception failure) {
        Objects.requireNonNull(failure, "failure");
        String reasonKey = reasonKey(failure);
        return new PlacementFailureNotice(
                Component.translatable(reasonKey));
    }

    Component message() {
        return Component.translatable(
                MESSAGE_KEY,
                reason.copy());
    }

    static String reasonKey(Exception failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof UnsuitableGeneratedSiteException) {
            return NO_SAFE_SITE_KEY;
        }
        if (failure instanceof StartingStructureStartupException) {
            String message = failure.getMessage();
            if (message != null
                    && message.contains("state is PLACING")) {
                return POSSIBLE_PARTIAL_PLACEMENT_KEY;
            }
            if (message != null
                    && message.contains("state is FAILED")) {
                return PREVIOUS_FAILURE_KEY;
            }
        }
        return UNEXPECTED_KEY;
    }
}
