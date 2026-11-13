# BaseFinder

Client-side Fabric mod for Minecraft 1.21.11 (Java 21).

## Controls
- **Right Shift**: open/close the BaseFinder ClickGUI.
- **Modules / ESP**: toggle chest, other-storage, and spawner outlines independently.
- **Coords**: view candidate clusters found by scanning chunks and copy coordinates.
- **Settings**: adjust scan radius and minimum cluster score.

## ESP behavior
Outlines are rendered locally around matching block entities in chunks already loaded by your Minecraft client. Chest outlines are gold, other storage (barrels, shulker boxes, ender chests) is purple, and spawners are red. This does not load chunks or reveal unloaded/hidden areas.

The scanner accumulates candidate clusters as you explore. Results are estimates based on visible block entities, not a guarantee that a location is a player base. Automatic chat alerts are off by default.

## Build
Run `gradle build`. The mod jar is created in `build/libs/`.
