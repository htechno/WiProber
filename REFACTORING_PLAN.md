# WiProber refactoring plan

This plan is intentionally incremental. Each stage must preserve a buildable application and add tests around the boundary being changed.

## Stage 0 — project lifecycle and ESX compatibility

- [x] Add a launcher project hub for creating, importing, and reopening projects.
- [x] Copy maps and imported data into app-owned workspaces and autosave survey changes.
- [x] Add bounded ESX extraction, binary track reading, real-file fixtures, and streaming JSON persistence.
- [x] Keep original-image survey coordinates aligned with downsampled PhotoView drawables.
- [x] Replace the accidental one-project/one-floor model with `Project → Floors → FloorSurvey` storage schema v2.
- [x] Migrate schema v1 workspaces without deleting their current map or survey payload before schema v2 is committed.
- [x] Import every supported floor from an ESX archive in one transaction and initially open the first floor.
- [x] Remember the active floor and allow safe switching from the survey screen after the current floor is saved.
- [x] Allow a named floor-plan image to be added to both new and existing projects.
- [x] Add a functional explicit Stop-and-Go/Continuous selector; replace its provisional layout in Stage 0.5.
- [x] Make local autosave and ESX export separate concepts; build the export from every stored floor.
- [x] Verify multi-floor import, switching, additional scans, adding a floor, process recreation, and re-import of the exported ESX.

Stage 0 storage contract:

```text
Project
├── id, title, activeFloorId
└── floors[]
    ├── id, name, map payload, map dimensions
    └── survey
        ├── metersPerUnit
        ├── Stop-and-Go points
        ├── Continuous sessions
        └── positioned notes and media
```

Local edits autosave the active floor. Switching floors first captures and persists an immutable snapshot. ESX export reads a stable snapshot of every floor and either writes the entire archive or fails; it never reports a partial archive as successful.

## Stage 0.5 — survey workspace UX

This stage replaces the provisional controls before Stage 0 receives final device acceptance.

- [x] Replace the ambiguous new-project popup with a clearly labelled creation flow:
  - title and short explanation;
  - persistent `Project name` field, initially `New project` or a deliberate user value;
  - persistent `First floor name` field, initially `Floor 1` rather than a copy of the image filename;
  - read-only selected floor-plan filename/preview;
  - validation and an explicit `Create project` action.
- [x] Replace floating, unrelated controls with one Material workspace hierarchy:
  - top app bar: Back, project title, and project overflow actions (`Export ESX`, `About`);
  - anchored non-modal floor dropdown directly below the app bar (`Floor: <name> ▾`);
  - the last dropdown item is `Add floor…`; switching does not open a dialog;
  - map canvas contains no permanent project/action icons;
  - one bottom survey dock contains scan modes, contextual scan action/status, Undo, Note, and Scale.
- [x] Make survey modes self-explanatory:
  - segmented `Stop-and-Go | Continuous` selector;
  - Stop-and-Go state says `Tap the map to scan at that point` and shows scan progress/results;
  - Continuous idle state has an explicit `Start route` action, followed by selection of the start point on the map;
  - active Continuous state shows elapsed time/scan count and a compact red `Stop route` action.
- [x] Replace the pause glyph and oversized floating control with a standard square stop icon and the text `Stop route`; keep it inside the bottom dock.
- [x] Use one Material icon family, consistent 48dp touch targets, labels/tooltips/content descriptions, and the same active/disabled/error colors.
- [x] Define responsive states for small portrait screens and landscape without covering important map content.
- [x] Add UI/state tests for floor dropdown, add-floor entry, project form labels/defaults, mode transitions, and the Continuous stop action.
- [x] Validate the redesign on the connected phone with screenshots and an end-to-end multi-floor survey flow.

**Acceptance gate:** manually accepted on 2026-08-14, including multi-floor UI and survey workflow.

Target survey-screen hierarchy:

```text
┌ Back   Project name                         ⋮ ┐
│ Floor: Floor 3 ▾                             │  anchored dropdown
├──────────────────────────────────────────────┤
│                                              │
│                 floor-plan map               │
│                                              │
├──────────────────────────────────────────────┤
│ Stop-and-Go | Continuous                     │
│ contextual hint/status or Start/Stop route   │
│ Undo            Note            Scale        │
└──────────────────────────────────────────────┘
```

## Stage 0.6 — lossless ESX source preservation

This compatibility correction is part of Stage 0 and must be completed before Stage 1. Imported Ekahau measurements are reference data for WiProber: the Android UI needs their route geometry, but it does not edit their Wi-Fi or spectrum measurements.

- [x] Replace the lossy imported-project model with `immutable base.esx + persisted local delta` (local schema v3).
- [x] Copy the complete selected ESX archive into the app-owned project workspace before commit; never retain the whole expanded archive in memory or as a second unpacked copy.
- [x] Index only project/floor metadata, maps, notes, and survey route geometry needed by the UI. Do not open or decode imported `track-*.bin` or spectrum payloads.
- [x] Mark imported route/note geometry as read-only source data. Undo and ESX generation operate only on surveys, floors, notes, and media created or changed in WiProber.
- [x] For an unchanged imported project, export the immutable source archive directly.
- [x] For a changed imported project, stream every original ZIP entry into a new archive, preserving its uncompressed bytes and entry name; replace only explicitly patched catalogue JSON and append new WiProber survey/BIN/media entries.
- [x] Patch source JSON as generic trees so unknown fields and entities survive. Existing `survey-*.json`, Wi-Fi BIN, spectrum BIN, and unrelated payloads must not be regenerated.
- [x] Keep source floor/survey/image IDs stable and generate canonical UUID IDs for local additions. Migrate the former prefixed local floor/image IDs without losing saved scans, and revalidate all references introduced by WiProber.
- [x] Treat schema-v2 imported workspaces without their original archive as legacy lossy projects; keep them usable, but require re-import for the new preservation guarantee.
- [x] Add fixture tests for unchanged-entry SHA-256 hashes, unknown entries/JSON keys, malformed opaque BIN, new surveys/floors, archive bounds, and export/re-import.

Stage 0.6 storage contract:

```text
Imported project workspace
├── manifest.json                 schema v3 and source IDs
├── base.esx                      immutable complete source archive
└── floors/
    ├── map/                      extracted display map only
    ├── media/                    display/local note media only
    └── survey.json               lightweight imported geometry + local delta
```

Recompression may change ZIP-level compressed bytes and metadata. The preservation contract applies to entry names and the uncompressed content of every original entry; original Wi-Fi/spectrum binaries must therefore remain byte-for-byte identical after a changed export.

**Acceptance gate:** manually accepted on 2026-08-14 after opening the changed three-floor export in Ekahau.

## Stage 1 — screen state and Android boundaries

- [x] Move permission, Wi-Fi settings, location settings, scan-throttling settings, and every survey Activity Result contract into `SurveyAndroidCoordinator`.
- [x] Expose one immutable `StateFlow<SurveyScreenState>` instead of public mutable `LiveData` and in-place mutable collections.
- [x] Represent calibration, note placement, pending scan access, Stop-and-Go scanning, Continuous tracking, and project I/O as explicit mutually exclusive interactions.
- [x] Keep the pending Stop-and-Go/Continuous request in the ViewModel across permission and settings round trips.
- [x] Introduce injectable clock and ID sources for scan points, Continuous sessions/results, notes, and scan-throttling policy.
- [x] Remove `Uri` and `PointF` from ViewModel/screen state; keep URI parsing and PhotoView coordinate conversion at Android boundaries.
- [x] Add deterministic ViewModel tests for mode exclusion, pending-action identity, timestamps/durations, IDs, and interrupted-operation recovery.

**Acceptance gate:** manually accepted on 2026-08-15 after device review of the Stage 1 survey flow.

## Stage 2 — ESX and storage boundaries

- [x] Separate ESX archive I/O, bounded generic-JSON handling, binary tracks, supported-subset generation, overlay conversion, Android URI orchestration, and local project storage.
- [x] Keep full new-project generation intact in `GeneratedEsxArchiveWriter`; validate the complete ESX reference graph before writing either a new archive or an imported-project overlay.
- [x] Make report/import IDs and timestamps deterministic through injectable sources; normalize BSSIDs and reject values that cannot be represented by the ESX BIN format.
- [x] Add an independent generated-archive compatibility test using plain ZIP/JSON parsing and a separate test-only BIN decoder, not WiProber's importer or `BinaryTrackReader`.
- [x] Run the opt-in external fixture against archive format `2.0`, schema `1.8.0`, and Ekahau AI Pro `11.6.0.1`/`11.6.3.1` history.
- [x] Persist a bounded compatibility inventory for imported sources, including opaque Wi-Fi tracks, spectrum payloads, unindexed catalogues, unknown payloads, archive version, and project schema; backfill schema-v3 workspaces and verify the inventory before export.
- [x] Bound source/archive size, total uncompressed size, entry count/name/size, JSON bytes/depth/node count, generated JSON/BIN, floor/note images, persisted survey entity counts, and local JSON writes.
- [x] Add malformed/traversal/deep-JSON/range tests while retaining byte-for-byte tests for untouched imported payloads.

**Acceptance gate:** manually accepted on 2026-08-15 after device and Ekahau review; Stage 3 is approved to start.

Verification recorded on 2026-08-15:

- full JVM unit suite, lint, debug APK assembly, and androidTest compilation passed;
- independent generated-archive ZIP/JSON/BIN compatibility validation passed;
- the external customer fixture passed with archive `2.0`, schema `1.8.0`, and two floors;
- targeted device tests passed for full new-project generation, synthetic imported overlay, and a real customer source-archive overlay/re-import with unchanged-entry SHA-256 checks;
- the debug APK was installed with `adb install -r`; the existing Floor 5 data remained 1 Stop-and-Go, 1 Continuous, 4 waypoints, and 11 scan results after testing.

## Stage 3 — Wi-Fi scan lifecycle

- [x] Model each scan as a single tokenized operation with success, typed failure, 15-second timeout, and lifecycle cancellation; never allow a second request to replace the owner.
- [x] Replace process-wide mutable callbacks with an Activity-scoped suspend scanner whose receiver registration, unregister, delayed request, timeout, and continuation cleanup are idempotent.
- [x] Reject stale or unrelated successful broadcasts using the pre-request result timestamp baseline and current request window. On Android 10+, ignore uncorrelatable negative broadcasts and let the owned request reach its own timeout.
- [x] Keep Stop-and-Go and Continuous identities in the ViewModel across Wi-Fi panel, permission, and Location Services round trips; resolve prerequisites in deterministic Wi-Fi → permission → location order.
- [x] Replace recursive Continuous callbacks with one cancellable coroutine loop. Backgrounding cancels only the platform scan; the active route resumes on return. Explicit Stop cancels the in-flight request before closing the route.
- [x] Preserve Android 9 behavior where Stop-and-Go may disconnect before scanning, while avoiding the ineffective restricted disconnect call on Android 10+.
- [x] Add deterministic JVM coverage for ownership, stale/empty/negative broadcasts, obsolete timeout tokens, access denial order, pending-mode identity, and scan-throttling expiry.
- [x] Validate receiver cancellation/re-registration and a fresh real scan on the connected Android 16/API 36 phone without clearing app data.
- [ ] Complete the physical-device compatibility matrix on API 28 and API 29, including permission denial, Wi-Fi disable during a request, timeout, throttled scans, and Stop-and-Go reconnect behavior.

**Acceptance gate:** manually accepted on 2026-08-15 and Stage 4 was approved. The API 28/29 physical-device matrix remains explicit release debt and is not treated as permission to raise `minSdk`.

Verification recorded on 2026-08-15:

- full JVM unit suite, lint, debug APK assembly, and androidTest assembly passed;
- the focused scanner instrumentation suite passed lifecycle cancellation, receiver re-registration, caller cancellation cleanup, and a real fresh Wi-Fi scan on Android 16/API 36;
- the survey workspace instrumentation smoke test passed after the scanner migration;
- installation used `adb install -r`, and the existing Floor 5 project remained unchanged at 1 Stop-and-Go point, 1 Continuous session, 4 waypoints, and 11 Continuous scan results.

## Stage 4 — presentation and product hardening

- [x] Add safe project management to the hub:
  - explicit loading, empty, unreadable-workspace, and retry states;
  - per-project overflow actions and a destructive confirmation before deleting a complete local project;
  - atomically hide a project before recursive deletion and resume cleanup of interrupted deletions on the next repository access.
- [x] Keep floor management inside the complete project and show the active floor's Stop-and-Go, Continuous, and note counts. Do not offer deletion of imported source floors because that would violate the lossless source-archive contract.
- [x] Add blocking, labelled progress for project creation, import, load, floor switch/add, and complete-project export. A load/map error stays on the survey screen with Retry and Projects actions instead of closing without recovery.
- [x] Add explicit export success/failure results; the success summary counts every stored floor, point, route, and note in the immutable export snapshot.
- [x] Improve accessibility and responsive behavior:
  - dynamic floor-plan descriptions include the active floor and survey counts;
  - progress/error/status messages use live regions and icon-only actions have project-specific descriptions;
  - the project hub scrolls as one document on short screens;
  - the landscape survey dock uses width-safe rows and the invalid horizontal `AppBarLayout` crash is covered by a recreation test;
  - light/dark system-bar icon contrast is explicit.
- [x] Bound note-photo previews to 1024 px, keep map/list previews target-sized through Coil, and continue decoding image metadata off the main thread with existing dimension/pixel limits.
- [x] Define scoped retention and cleanup:
  - prune only unreferenced app-owned floor-media copies after the replacement survey and manifest are atomically committed;
  - keep imported `base.esx`, source entries, external/gallery originals, maps, and referenced note media outside cleanup;
  - at hub startup, remove only recognized stale import/export jobs after 24 hours and temporary camera captures after seven days;
  - report unreadable workspaces rather than silently presenting them as successfully loaded recents.
- [x] Add JVM/device coverage for summaries, scoped cache cleanup, atomic project deletion, orphan-media pruning, recoverable load errors, and portrait-to-landscape recreation.
- [x] Profile an existing 193 MB three-floor imported workspace on the connected Android 16/API 36 phone: first `SurveyActivity` frame in 347 ms, about 134 MB total PSS/224 MB RSS after the map loaded, without expanding or decoding the retained source ESX.
- [ ] Complete the release matrix on physical API 28 and API 29 devices and repeat the final changed-project open/export check in the supported Ekahau version.

**Acceptance gate:** Stage 4 implementation is complete on 2026-08-15 and stops here for manual product review. Stage 5 must not begin without explicit approval; `minSdk` remains 28.

Verification recorded on 2026-08-15:

- the full JVM unit suite, `lintDebug` (0 errors), debug APK, and androidTest APK passed;
- 11 targeted tests passed on the connected Android 16/API 36 phone: repository/catalog/delete/media cleanup, project form and survey chrome, portrait-to-landscape recreation, and recoverable load-error UI;
- the recreation test exposed and then verified the fix for the former landscape `AppBarLayout` inflation crash;
- the final build was installed with `adb install -r`; the four pre-existing project workspaces remained, and Floor 5 stayed at 1 Stop-and-Go point, 1 Continuous route, 4 waypoints, and 11 Continuous scan results;
- two test-only workspaces left by the deliberately reproduced pre-fix landscape crash were identified by manifest title/ID and removed; no customer project was touched;
- physical API 28/API 29 and final Ekahau checks remain open as listed above.

## Stage 5 — minimum Android version and richer Wi-Fi metadata

- Keep `minSdk = 28` through Stages 3 and 4; do not drop Android 9 or Android 10 support as part of the current refactoring.
- Inventory the real deployment-device/API distribution before proposing a new minimum supported Android version.
- Re-evaluate `minSdk = 30` as the first meaningful Wi-Fi data boundary: public beacon Information Elements, `wifiStandard`, scan-results callbacks, scan-throttling state, and 6 GHz capability become uniformly available.
- Re-evaluate `minSdk = 33` only for a controlled Android 13+ device fleet, where raw SSID bytes, structured security types, Wi-Fi 7/MLO, and 320 MHz metadata can become part of the baseline contract.
- Design richer scan metadata so newer APIs can be used opportunistically without weakening the Android 9/10 fallback: ordered `id`/`idExt` Information Elements, channel width and center frequencies, scan timestamp, band/channel, security types, and MLO identity.
- Separately fix bounded, extension-aware Information Element preservation; never treat an OS upgrade as proof that every chipset/driver exposes a complete beacon frame.
- Make any `minSdk` increase a separate product decision with an explicit compatibility matrix, device tests at the proposed minimum API, migration notes, and user approval.
