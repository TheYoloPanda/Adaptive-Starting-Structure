package com.typ.adaptivestartingstructure.placement;

/*
 * Not an IllegalStateException, which placement takes for its terrain and
 * template plans contradicting each other: that costs a site, while a discard
 * that fails must stop the start.
 */
final class TrialTerrainDiscardException extends RuntimeException {
    TrialTerrainDiscardException(String message) {
        super(message);
    }

    TrialTerrainDiscardException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
