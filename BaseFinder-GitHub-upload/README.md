# BaseFinder (Fabric 1.21.11)

Client-side Fabric mod for inspecting storage/spawner block entities in chunks already loaded by the client.

## Controls
- Right Shift: open the BaseFinder screen.
- Storage Finder / Spawner Finder: enable or disable the corresponding scan categories.
- Radius and score controls: adjust the scan range and cluster threshold.
- Scan Now: rescan currently loaded client chunks.
- Save Local File: write results under `.minecraft/config/basefinder/`.
- Clear Results: clear the current in-memory scan results.

## Privacy / chat behavior
- BaseFinder does not send detection announcements or save confirmations to Minecraft chat.
- Results are saved locally only when you press **Save Local File** (the scanner also keeps its existing local autosave when new candidate groups are first found).
- Scanning is limited to chunk data available to the client. It does not reveal blocks in unloaded chunks or obtain hidden server-side data.

## ESP status
The purple control screen and silent/local-only behavior are included in this revision. A world ESP outline renderer is **not yet implemented**; the GUI does not pretend that ESP is active. That needs a separate rendering pass and in-game verification against the exact Fabric 1.21.11 API.

## Build
Run `gradle build --no-daemon` from this directory with Java 21. The mod JAR is produced in `build/libs/`.
