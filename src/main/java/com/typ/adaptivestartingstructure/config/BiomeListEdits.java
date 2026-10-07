package com.typ.adaptivestartingstructure.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * What the additional and removed biome keys asked of the built-in preferred
 * and excluded lists.
 *
 * <p>A snapshot's biome lists are the outcome, and an entry that changed
 * nothing leaves no trace in an outcome. These are kept so the entries that
 * changed nothing can be named once a registry exists to check them against.
 */
public record BiomeListEdits(ListEdit preferred, ListEdit excluded) {
    public static final BiomeListEdits NONE =
            new BiomeListEdits(ListEdit.NONE, ListEdit.NONE);

    public BiomeListEdits {
        Objects.requireNonNull(preferred, "preferred");
        Objects.requireNonNull(excluded, "excluded");
    }

    /**
     * One list: its built-in entries with the additions merged in, the
     * additions on their own, and the entries asked to be taken out.
     */
    public record ListEdit(
            Set<ResourceLocation> listed,
            Set<ResourceLocation> added,
            Set<ResourceLocation> removed) {
        static final ListEdit NONE = new ListEdit(Set.of(), Set.of(), Set.of());

        public ListEdit {
            listed = immutableCopy(listed, "listed");
            added = immutableCopy(added, "added");
            removed = immutableCopy(removed, "removed");
        }

        private static Set<ResourceLocation> immutableCopy(
                Set<ResourceLocation> ids,
                String name) {
            Objects.requireNonNull(ids, name);
            return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        }
    }
}
