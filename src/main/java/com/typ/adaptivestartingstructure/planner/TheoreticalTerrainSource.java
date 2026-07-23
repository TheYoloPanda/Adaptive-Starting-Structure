package com.typ.adaptivestartingstructure.planner;

@FunctionalInterface
public interface TheoreticalTerrainSource {
    TerrainColumn column(int x, int z);
}
