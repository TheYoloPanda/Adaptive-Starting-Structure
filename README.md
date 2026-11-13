# Adaptive Starting Structure

A NeoForge 1.21.1 mod for modpacks that want every world to start in a
specific structure. When a new world is created, the mod picks one of your
`.nbt` templates, looks for a good spot near spawn, reshapes the terrain around
it and builds it before the player joins. The world spawn is moved onto the
structure.

## Adding a structure

Put your templates in:

```text
config/adaptive_starting_structure/structures/
```

Only `.nbt` files directly in that folder are used. Keep the file names
lowercase, using only letters, numbers, `_`, `-` and `.`. A bad name or an
empty folder makes every new world fall back to the vanilla spawn. With more
than one template, the world seed picks which one is used.

Blocks from mods that aren't installed are replaced with air, and the log
lists them.

### Markers

Each template needs exactly two structure blocks in `DATA` mode, with one of
these values in the Custom Data field:

| Custom Data | Marks |
| --- | --- |
| `adaptive_starting_structure:spawn` | Where players spawn (feet position) |
| `adaptive_starting_structure:ground_level` | One block above the ground the structure sits on |

Both are replaced with air when the structure is built. A template with a
missing, duplicated or unknown marker is rejected.

`DATA` mode is hidden in the structure block screen. Switch the mode to
`CORNER`, then hold `Alt` and click the mode button once. Type the value and
press `Done` (`Esc` throws the change away).

If `Alt` doesn't work for you, use commands:

```mcfunction
/setblock X Y Z minecraft:structure_block[mode=data]
/data merge block X Y Z {mode:"DATA",metadata:"adaptive_starting_structure:spawn"}
```

The vanilla structure block saves at most 48 blocks per side. For anything
bigger, use a tool that keeps block entity data, or the markers get lost.

## When no site fits

Every site is checked on the real generated terrain before anything is built,
and the terrain of a rejected site is thrown away. If none of them pass:

- in singleplayer you get a screen with **Delete World and Create New** or
  **Continue Anyway**, which keeps the vanilla spawn without the structure;
- a dedicated server carries on without the structure and tells each player
  in chat.

## Config

Everything is in `config/adaptive_starting_structure-common.toml`, and every
key has a comment there. The ones you're most likely to change:

| Key | Default | What it does |
| --- | --- | --- |
| `preferredSearchRadius` | `1024` | A site inside this radius beats any site farther out |
| `maximumSearchRadius` | `4096` | How far from the original spawn the search can go |
| `maximumCutDepth`, `maximumFillDepth` | `8` | How much ground can be dug out or filled under the structure |
| `blendWidth` | `24` | Width of the slope between the structure and the land around it |
| `ignoredStructureCollisions` | `["ae2:meteorite"]` | Structures whose oversized bounds shouldn't rule out a site |
| `adaptTemplateToSite` | `true` | Swaps the template's grass and dirt for the local ground, like sand in a desert, and adds snow in cold biomes |
| `placeTemplateEntities` | `false` | Places the entities saved in the template |
| `placePlayerAtSpawnMarker` | `true` | Puts new players right on the spawn marker instead of on the roof. Turn it off if your pack handles spawning |

The biome lists are edited with `additionalPreferredBiomes`,
`additionalExcludedBiomes`, `removedPreferredBiomes`, `removedExcludedBiomes`
and `excludedBiomeTags`.

Settings that decide where the structure goes only affect worlds created after
you change them.

## A world that won't start

If the game crashes while the structure is being built, the world might be
half modified, so the mod refuses to load it. To load it anyway, as it is and
without the structure:

```toml
blockedStateRecovery = "skip"
```

The world stays flagged, so it refuses again if you switch back to `"block"`.

## Radial Resource Gating

Set the overworld `centerMode` to `"world_spawn"`, so the ore ring follows the
spawn this mod picks. With `"fixed"` the ring stays where it is while the
spawn can end up thousands of blocks away, and the host and operators get a
warning in chat once per world. If the fixed center is what you want, turn the
warning off:

```toml
warnAboutFixedOreGateCenter = false
```
