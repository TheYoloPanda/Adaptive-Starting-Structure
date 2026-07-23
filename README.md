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

This option never changes the vanilla `spawnRadius` gamerule. Vanilla may still
move a player away from the exact marker while choosing a valid spawn point.

### Terrain blending and trees

Terrain is blended only inside the configured bounded placement area. A tree is
removed as a complete connected tree when its trunk or canopy intersects the
structure footprint, an actually modified blend column, or an explicit
template cell. Trees that merely stand inside an unchanged part of the
geometrical blend ring are preserved.

If a complete interfering tree cannot be resolved inside the prepared area,
the site is rejected before placement and the next planned candidate is tried.
This avoids leaving sliced canopies or floating trunks and does not load extra
chunks dynamically.

If every planned candidate is rejected, singleplayer shows an error screen
with a `Back to Title Screen` action instead of crashing the client. A
dedicated server stops cleanly after logging the full error. The failed world
is preserved and remains blocked from automatic placement retries.

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
