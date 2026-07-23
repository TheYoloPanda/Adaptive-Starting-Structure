package com.typ.adaptivestartingstructure.placement;

record HeightmapVerification(
        int checkedColumns,
        int checkedValues) {

    HeightmapVerification {
        if (checkedColumns < 0
                || checkedValues != checkedColumns
                        * HeightmapConsistencyValidator
                                .TRACKED_TYPES
                                .size()) {
            throw new IllegalArgumentException(
                    "Heightmap verification counts are inconsistent");
        }
    }
}
