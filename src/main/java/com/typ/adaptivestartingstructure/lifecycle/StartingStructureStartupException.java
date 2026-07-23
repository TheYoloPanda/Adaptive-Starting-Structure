package com.typ.adaptivestartingstructure.lifecycle;

public final class StartingStructureStartupException
        extends IllegalStateException {
    public StartingStructureStartupException(String message) {
        super(message);
    }

    public StartingStructureStartupException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
