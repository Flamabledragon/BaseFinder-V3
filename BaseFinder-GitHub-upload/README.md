# BaseFinder

Fabric client mod for Minecraft 1.21.11 (Java 21).

## Build
Run `gradle build`. The output JAR is in `build/libs/`.

## Controls
- Right Shift opens the in-game BaseFinder interface.
- Tabs: Modules, ESP, Coordinates, Settings.
- Coordinates tab lists candidate cluster coordinates and lets you copy them to the clipboard.

## Scope and limitations
BaseFinder scans block entities only in chunks already loaded and sent to the client. It cannot inspect unloaded chunks or bypass server-side protections.

The ESP tab in this update is a preference/settings UI only. The actual world-outline renderer is not implemented in this ZIP yet; do not treat those switches as working visual ESP. Coordinate results are cluster center estimates, not guaranteed exact base entrances or individual block positions.

All results are local. The mod does not send chat messages or broadcast coordinates.
