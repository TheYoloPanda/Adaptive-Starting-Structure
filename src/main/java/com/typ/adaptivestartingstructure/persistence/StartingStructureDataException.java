package com.typ.adaptivestartingstructure.persistence;

public final class StartingStructureDataException extends IllegalArgumentException {
    public StartingStructureDataException(String message) {
        super(message);
    }

    public StartingStructureDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
