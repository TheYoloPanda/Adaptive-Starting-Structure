# Adaptive Starting Structure

Adaptive Starting Structure is a NeoForge 1.21.1 mod that selects an external
Minecraft structure template when a new world is created, finds a suitable
site near the initial spawn, adapts the terrain, and places the structure
before the player joins.

Structure templates are loaded from:

```text
config/adaptive_starting_structure/structures/
```

Only direct `.nbt` files with lowercase names matching `[a-z0-9_.-]+` are
considered.

## ⚠️ Required DATA markers

Every structure template must contain exactly two
`minecraft:structure_block` blocks in `DATA` mode:

| Custom Data value | Meaning |
| --- | --- |
| `adaptive_starting_structure:spawn` | Marks the world-spawn center and its feet-level reference position. Vanilla may still place players nearby according to `spawnRadius`. |
| `adaptive_starting_structure:ground_level` | Marks one block above the intended terrain plane. The terrain level is `marker Y - 1`. |

These are authoring markers only. The mod reads their positions directly from
the template NBT and replaces both marker blocks with air during placement. It
does not invoke vanilla's hardcoded DATA-marker behavior.

The X/Z coordinates of `ground_level` do not define a second terrain shape;
they only need to keep that marker inside the template.

### How to enable the hidden DATA mode

In Minecraft Java Edition 1.21.1, the normal mode selector only cycles through
`SAVE`, `LOAD`, and `CORNER`. `DATA` is deliberately hidden.

1. Enter Creative mode with commands enabled.
2. Obtain a Structure Block:

   ```mcfunction
   /give @s minecraft:structure_block
   ```

3. Place it at the position that should become a marker and open its interface.
4. Set the visible mode to `CORNER`.
5. Hold `Alt` and, without releasing it, click the `CORNER` mode button once.
   It should change to `DATA`.
6. Enter one of the exact marker values above in the `Custom Data` field.
7. Select `Done`. Closing the screen with `Esc` cancels the change.
8. Repeat the process for the other marker.
9. Make sure both blocks are inside the region included in the exported NBT.

The template is rejected if either marker is missing, duplicated, outside the
template, not in `DATA` mode, or uses unknown Custom Data.

### Blocks from unavailable mods

If a template references a block whose registry ID is not available in the
current runtime registry, the mod replaces every template position that uses
that palette state with explicit air and continues loading the structure. This
removes any existing terrain at the affected position. Any Block Entity NBT
attached to that position is discarded.

The server log reports the unavailable block IDs and the number of replaced
positions. With alternative palettes, a state index unavailable in any palette
becomes air in every palette so the structure footprint remains consistent.
Malformed IDs, invalid properties on available blocks, corrupt NBT, and invalid
or missing DATA markers still reject the template.

### Template entities

Template entities remain disabled by default. To restore supported standalone
entities saved in the `.nbt`, enable:

```toml
placeTemplateEntities = true
```

Entities are prepared before terrain or template placement and published only
after the final block, light, heightmap, fluid, and spawn checks. Fractional
positions and authored NBT are preserved, root UUIDs are regenerated, and mobs
are not rerolled through natural-spawn finalization. Paintings, item frames,
and other block-attached entities use their transformed template anchor and
must remain supported by the final blocks.

Missing, malformed, unavailable, feature-disabled, non-serializable, or
unloadable entity entries are skipped with a warning that identifies the
template and source index. Player entities and passenger trees are rejected.
Enabled placement also rejects templates exceeding 128 root entities, 4 MiB of
aggregate uncompressed entity NBT, or NBT nesting depth 64. Entity-addition
failure after block placement is fail-closed and prevents the world from being
marked complete.

### Underground spawn markers and safety validation

The `spawn` marker may be below `ground_level`. This is valid: `ground_level`
aligns the structure with the terrain, while `spawn` independently identifies
the intended spawn position inside the structure.

By default, the mod validates only the exact `spawn` marker column. It checks
for player-sized free space, a safe floor, dangerous blocks, fluids, and
excessive falling distance. It does not require the entire vanilla
`spawnRadius` square to be safe, so an intentionally underground spawn does not
prevent the structure from being selected.

Set the following common config option to `true` only if every possible vanilla
spawn column in the full `spawnRadius` area must pass the same checks:

```toml
requireSafeSpawnArea = true
```

This option never changes the vanilla `spawnRadius` gamerule, and it does not
decide where anyone arrives: that is settled by `placePlayerAtSpawnMarker`
below.

The two work together. The area is only checked when vanilla is free to pick
the arrival column, so with `placePlayerAtSpawnMarker` left on this option does
nothing: the marker's own column is the only one anyone stands on, and proving
the other 440 would cost one noise column each, for every candidate site. Turn
`placePlayerAtSpawnMarker` off to make it bite.

### Arriving on the spawn marker

Setting the world spawn to the marker is not enough to arrive there. Vanilla
picks a column within `spawnRadius` of the world spawn and puts the player on
that column's surface, which for a structure with a roof is the roof. Setting
the gamerule to `0` does not help: the single column it then considers is
still resolved to its surface.

The mod therefore moves arriving players onto the marker itself:

```toml
placePlayerAtSpawnMarker = true
```

This applies to a player with no bed or charged respawn anchor who arrives
inside the area the vanilla search could have chosen from, and only until that
player has been placed once. It covers both the first login and a respawn that
falls back to the world spawn, including one whose bed was destroyed. Set it to
`false` in a pack that manages spawning itself.

### Customizing the biome lists

`additionalPreferredBiomes` and `additionalExcludedBiomes` add to the built-in
lists; `removedPreferredBiomes` and `removedExcludedBiomes` take entries out of
them, which is the only way to reach an ID the mod hardcodes:

```toml
removedExcludedBiomes = ["minecraft:meadow"]
removedPreferredBiomes = ["minecraft:badlands"]
```

`excludedBiomeTags` is the whole tag list rather than an addition to a hidden
one, so a pack edits it directly.

Biome and tag IDs the server's registry does not have are ignored with a
warning naming them, so a pack that lists another mod's biome and later drops
that mod keeps its starting structure. Naming an ID the defaults already list
is redundant rather than an error.

### Terrain blending and trees

Terrain is blended only inside the configured bounded placement area. Tree
analysis uses a fixed 12-block observation margin beyond that area without
extending the terrain blend. A tree is removed as a complete connected tree
when its trunk or canopy intersects the structure footprint, an actually
modified blend column, or an explicit template cell. Trees that merely stand
inside an unchanged part of the blend or observation area are preserved.

If a complete interfering tree cannot be resolved inside the observation
margin, the physical site is rejected before placement. All planned rotations
at the same site center are discarded together before the next site is tried.
This avoids sliced canopies, floating trunks, and repeated preparation of the
same unsuitable location. Observation chunks are prepared up front; tree
traversal never loads chunks dynamically.

Generated terrain whose exact footprint or blend would exceed the configured
cut/fill limits uses the same site-level retry before any world writes.

The same preflight rejects a site when the complete template volume would
intersect a generated vanilla, datapack, or modded structure registered with
Minecraft. It also rejects terrain, vegetation, or whole-tree writes that
would enter one of that structure's actual piece bounds. No extra proximity
buffer is added. Arbitrary player builds and world-generation features that
are not registered structures cannot be detected by this check.

Some structures declare bounds far larger than what they place: Applied
Energistics 2 declares each meteorite as a flat area of 144 × 144 blocks around
a meteorite a few blocks wide, which would reject sites dozens of blocks away
from it. A structure listed here never counts as a collision:

```toml
ignoredStructureCollisions = ["ae2:meteorite"]
```

The ground under the site is still judged like any other terrain, so a crater
under the footprint is treated as the hole it is.

If every planned candidate is rejected, singleplayer shows a blocking choice:
`Return to World List` preserves the world in its pending state, while
`Continue Anyway` switches permanently to Minecraft's normal spawn without
the starting structure (and restores the vanilla Bonus Chest choice when
enabled). A dedicated server stops cleanly after logging the full error.
Unexpected or potentially partial placement failures remain fail-closed.

### Command alternative

If the `Alt` shortcut is intercepted, the same marker can be created with
commands. Replace `X Y Z` with the intended marker coordinates:

```mcfunction
/setblock X Y Z minecraft:structure_block[mode=data]
/data merge block X Y Z {mode:"DATA",metadata:"adaptive_starting_structure:spawn"}
```

Use `adaptive_starting_structure:ground_level` for the second marker.

### Vanilla export-size warning

The vanilla Structure Block interface is limited to 48 blocks per axis. Larger
structures must be exported with a tool that preserves both block states and
Block Entity NBT. A clipped export or a conversion that removes Structure
Blocks will also remove these required markers.

### How a site is chosen

The search walks outward from the spawn the world would have used, on a square
lattice, and considers the area inside `preferredSearchRadius` before anything
beyond it. It stops as soon as it has enough usable sites, so a world with good
ground near the vanilla spawn never pays for the full `maximumSearchRadius`.

`preferredSearchRadius` is therefore a preference, not just a sampling density:
a site inside it wins over a flatter one outside it. `maximumSearchRadius` is
how far the search is willing to go when the near area yields nothing, not how
far it always looks.

Within one band, sites that may overlap a generated structure such as a village
rank below sites that do not. This is a placement check on the structure grid
that generates no chunks, and it is deliberately conservative: it does not
check whether the structure's own biome conditions would let it generate there,
so it lowers a site's rank but never rejects it.

Each place is put forward in its best allowed rotation. Its other rotations
are tried only once the band has offered every place, and only if that place
has not produced a usable site, so the alternatives kept for placement are
always different places: when a site fails its final check, every rotation of
it is discarded together. Rotations that leave the structure's footprint
extent unchanged are judged together, and count once against
`maximumCoarseCandidates`.

The search itself does not read the world seed. Positions come from the
lattice, and the seed decides the terrain those positions are judged on.

### Changing the configuration after a world is created

A world is planned when it is created and built the first time it is loaded,
and those can be two different sessions: create the world, return to the world
list, change the configuration, then open it.

The values that decided where the structure goes — `blendWidth`,
`maximumCutDepth`, `maximumFillDepth`, `maximumElevationRange`,
`maximumPerimeterError`, `maximumWaterFraction` and `allowedRotations` — are
stored with the plan and reused when the structure is built, so a change made
in between does not invalidate a finished plan. The server log reports when
this happens. Change them before creating a world for them to take effect.
Settings that play no part in choosing a site, such as
`placeTemplateEntities` and `ignoredStructureCollisions`, keep following the
live configuration.

### Recovering a world that refuses to start

If the game dies while the structure is being built, the saved state stays at
`PLACING` or `FAILED` and every later start refuses to run, because the world
may be half-modified. To load such a world anyway:

```toml
blockedStateRecovery = "skip"
```

The starting structure is then given up on and the world loads as it is, which
may leave partial terrain or structure changes from the interrupted attempt.
The state is kept on disk, so setting the key back to `"block"` (the default)
restores the refusal.
