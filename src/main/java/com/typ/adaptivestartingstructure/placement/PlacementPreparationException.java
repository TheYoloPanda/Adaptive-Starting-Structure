package com.typ.adaptivestartingstructure.placement;

public class PlacementPreparationException extends IllegalStateException {
    public PlacementPreparationException(String message) {
        super(message);
    }

    public PlacementPreparationException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
