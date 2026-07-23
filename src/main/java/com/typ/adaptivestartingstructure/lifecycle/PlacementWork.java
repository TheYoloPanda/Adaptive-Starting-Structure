package com.typ.adaptivestartingstructure.lifecycle;

interface PlacementWork extends AutoCloseable {
    @Override
    void close() throws Exception;
}
