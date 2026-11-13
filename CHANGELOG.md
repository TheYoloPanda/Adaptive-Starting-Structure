# Changelog

## 0.2.0

First public release.

- Picks a structure template from `config/adaptive_starting_structure/structures/` when a new world is created; with several templates, the world seed decides which one.
- Searches for a suitable site near the original spawn, checking terrain, biomes, water, allowed rotations and already generated structures.
- Levels and blends the terrain, removes trees in the way and places the structure before the player joins, then moves the world spawn to the structure's spawn marker.
- When no safe site remains, singleplayer offers to delete the world and create a new one or to continue without the structure; a dedicated server continues without it and tells each player in chat.
