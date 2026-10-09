# WiProber contributor guide

## Scope and product

This file applies to the whole repository.

WiProber is a single-module Android application for on-site Wi-Fi surveys. A project owns one or more floors. Each floor has its own map, scale, Stop-and-Go points, Continuous routes, and positioned notes; export produces one Ekahau-compatible ESX archive for the complete project.

New projects are generated as a supported Ekahau subset. Imported projects use a different contract: keep the complete source archive immutable, index only UI geometry, and overlay WiProber additions during export. Do not decode or regenerate imported Wi-Fi/spectrum measurements. Every untouched source entry must keep the same name and uncompressed bytes; JSON catalogues may be patched only when required for a local addition or supported edit.

## Current code map

- `ProjectHubActivity.kt`: launcher screen for new projects, ESX import, recent local projects, unreadable-workspace status, and confirmed whole-project deletion.
- `CreateProjectActivity.kt`: labelled new-project form for project name, first-floor name, and the selected floor-plan preview.
- `SurveyActivity.kt`: survey rendering and Android UI adapter. It owns PhotoView coordinate adaptation, dialogs, Wi-Fi result mapping, scan coroutine presentation, and project I/O coroutines, but not permission/settings or Activity Result registration.
- `SurveyAndroidCoordinator.kt`: all survey Activity Result contracts plus Wi-Fi permission, Wi-Fi panel, Location Services, scan-throttling, and Developer Options coordination.
- `SurveyControlsPresenter.kt`: pure presentation mapping for Stop-and-Go and Continuous dock states.
- `SurveySummary.kt`: Android-free active-floor and complete-export count aggregation used by survey/export presentation.
- `ProjectRepository.kt`: schema-v3 multi-floor workspaces, schema-v1/v2 migration, immutable imported `base.esx`, recent metadata, atomic JSON persistence, map/media copies, orphan-media pruning, atomic whole-project deletion, and whole-project snapshots.
- `AppCacheMaintenance.kt`: scoped retention cleanup for recognized stale ESX import/export jobs and temporary camera captures; it must never traverse project workspaces or unrelated cache entries.
- `BoundedIO.kt`: shared byte-counted copies, bounded byte-array reads, and a decoder-facing limited input stream.
- `JsonFileIO.kt`: size-checked streaming Gson reads and writes for persisted survey snapshots.
- `EsxImportService.kt`: transactional ESX preparation and commit into a local project workspace.
- `EsxExportService.kt`: selects full generation for new/legacy projects or overlay export for schema-v3 imported projects.
- `EsxArchiveIO.kt`: the only raw ESX ZIP boundary: validated entry names/counts/sizes, bounded JSON preflight, payload extraction/copy, archive writing, and source compatibility inventory.
- `EsxArchiveOverlayWriter.kt`: generic-JSON catalogue append/patch and streaming merge of local additions over an immutable imported archive.
- `GeneratedEsxArchiveWriter.kt`: writes the complete supported-subset archive for a new/legacy local project without involving Android URI APIs.
- `EsxReportValidator.kt`: validates UUIDs and all cross-catalogue references before either generated or overlay output is written.
- `MainViewModel.kt`: immutable `StateFlow<SurveyScreenState>` and transitions for Stop-and-Go, Continuous, notes, calibration, project operations, and undo.
- `SurveyScreenState.kt`: Android-free screen state, mutually exclusive interaction states, pending scan request types, and project-operation types.
- `SurveyRuntimeSources.kt`: injectable millisecond clock and ID source used by deterministic survey logic.
- `WifiScanner.kt`: Activity-scoped suspend boundary around `WifiManager`; it owns one tokenized scan, receiver lifecycle, timeout, cancellation, Wi-Fi state changes, and terminal outcomes.
- `WifiScanOperation.kt`: Android-free ownership/freshness tracker and permission/Wi-Fi/location access policy. It rejects stale result sets and prevents old broadcasts/timeouts from completing a newer request.
- `ScanData.kt`: application survey models.
- `PointsOverlayView.kt`: draws survey points, routes, notes, and calibration geometry in original-image coordinates transformed through PhotoView's matrix.
- `ImageCoordinateMapper.kt`: converts between original floor-plan pixels and the potentially downsampled drawable used by PhotoView.
- `EkahauReportBuilder.kt`: maps application models into ESX DTOs and binary tracks.
- `EkahauModels.kt`: JSON DTOs and ESX constants.
- `BinaryDataSerializer.kt`: bounded big-endian ESX Wi-Fi track writer with representable-range checks.
- `EsxProjectImporter.kt`: maps bounded archive metadata, display payloads, notes, and route geometry into the local UI index; imported Wi-Fi/spectrum binaries stay opaque.
- `BinaryTrackReader.kt`: strict ESX Wi-Fi track reader used for generated-writer compatibility tests, never for imported project indexing.
- `NoteImageMetadataReader.kt`: bounded note-image metadata decoding and dimension/pixel validation off the UI thread.
- `app/src/main/assets/`: static ESX payloads copied into every export. These files are part of the export contract, not generic sample data.

Do not add more business logic to `SurveyActivity`. Prefer extracting a small, testable boundary (scanner, permission coordinator, survey state reducer/use case, ESX reader/writer, or storage component) as part of the change being made.

## Data and format invariants

- Survey coordinates are pixels in the original floor-plan image, never screen coordinates or zoomed PhotoView coordinates.
- A local project is the unit of import/export and owns one or more floors. Never silently turn separate floors into separate recent projects.
- Persist the active floor snapshot before switching floors; ESX export must snapshot and include every floor.
- `metersPerUnit` is metres per original-image pixel.
- Application timestamps and durations are milliseconds. ESX route points, scanning intervals, and survey duration are nanoseconds. Binary track timestamps are relative milliseconds stored as signed 32-bit integers.
- Binary track files begin with `02 01`, use big-endian values, and address AP measurements by their index in the matching `accessPointMeasurementIds` list.
- Every ESX entity ID written by WiProber must be a canonical UUID string. IDs referenced across `project.json`, `floorPlans.json`, `surveyLookups.json`, `survey-*.json`, image payloads, notes, AP measurements, radios, and binary files must remain referentially consistent.
- `PROJECT_CONFIG_ID` must continue to match the ID in `app/src/main/assets/projectConfiguration.json`. The Wi-Fi adapter ID in every `WifiTrack` must match the generated `wifiAdapterInformations.json` entry.
- Image metadata must describe the bytes actually copied into `image-<id>` payloads. Do not infer a format solely from an untrusted display name.
- Normalize and validate BSSIDs at boundaries. Do not silently merge different radios; the current product deliberately treats each BSSID as a separate measured AP.
- Never discard an entry from an imported source ESX. A changed export may recompress entries, but the uncompressed bytes of every untouched original entry—including all Wi-Fi and spectrum binaries—must remain byte-for-byte identical.
- Persist the bounded `EsxSourceCompatibility` inventory beside imported `base.esx`. It records archive/schema versions and unsupported opaque tracks, spectrum data, unindexed JSON catalogues, and unknown payloads; verify it again before export.
- Keep imported route/note geometry distinguishable from local survey data. Imported survey JSON/BIN is read-only; Undo and report generation affect only WiProber additions.
- A schema-v2 imported workspace has already lost its original archive and remains a documented legacy lossy project. Re-import the source ESX before claiming the schema-v3 preservation guarantee.

When changing ESX handling, add fixture-based tests that validate JSON relationships, ZIP entries, time-unit conversions, binary reader/writer compatibility, and malformed/truncated input. A writer test that is read only by the project's own reader is necessary but not sufficient for Ekahau compatibility.

## Android and Wi-Fi rules

- Never block the main thread while initiating or awaiting a scan, decoding a large image, parsing an ESX file, or writing an archive.
- Model a Wi-Fi scan as one owned operation with a result, failure, and timeout. Do not allow overlapping requests to overwrite callbacks or associate results with the wrong map coordinate.
- Registration of a broadcast receiver must have an explicit, idempotent lifecycle and a matching unregister path.
- Keep Stop-and-Go and Continuous pending actions distinct across permission, Wi-Fi-panel, and location-settings round trips. Resuming one mode must not accidentally invoke the other.
- Treat scan throttling, missing/stale broadcasts, Wi-Fi state changes, denied permissions, and lifecycle cancellation as normal outcomes.
- On every supported version (Android 11+), a scan-results broadcast may belong to the platform or another app. Accept a successful broadcast only when its newest `ScanResult.timestamp` is newer than the pre-request baseline and belongs to the current request window; do not attribute an uncorrelatable negative broadcast to the active request.
- Check permission behavior against every supported API range (`minSdk = 30`, current `targetSdk = 36`) before changing the manifest or runtime flow. Test on a real device; JVM tests cannot validate Android Wi-Fi behavior.
- `ScanResult.wifiStandard`, public beacon Information Elements, scan-availability broadcasts, and `WifiManager.isScanThrottleEnabled` are baseline APIs. Keep API guards only for capabilities introduced after Android 11.
- Wi-Fi capabilities and standards must be mapped deliberately. Add tests/fixtures before changing WPA/WPA2/WPA3/OWE/Enterprise or 2.4/5/6 GHz classification.

## State, storage, and threading

- Expose immutable screen state from the ViewModel. Avoid public mutable `LiveData`, mutable collections mutated in place, and UI types or Activity-owned enums in domain state.
- Make mutually exclusive modes explicit: importing/selecting a map, calibration, note placement, Stop-and-Go scanning, Continuous tracking, import/export, and undo must not race each other.
- Keep the pending scan kind in the ViewModel while Android settings/permission launchers are active. The Android coordinator must never infer whether it is resuming Stop-and-Go or Continuous.
- Keep `Uri`, `PointF`, PhotoView matrices, Activity Result contracts, permissions, and Settings intents out of `MainViewModel` and `SurveyScreenState`.
- Use an injectable clock/ID source in logic that needs deterministic tests.
- Content URIs and cache files are lifecycle-sensitive. Take persistable URI permission when appropriate or copy input into app-owned storage before relying on it after recreation/process death.
- Never delete the backing files of the currently loaded project before a replacement has been fully parsed, decoded, and accepted.
- Whole-project deletion is the only path allowed to remove an imported `base.esx`; it requires explicit user confirmation and must atomically hide exactly one validated project workspace before best-effort recursive cleanup.
- Retention cleanup must be allow-listed by app-owned directory/prefix and age. Never scan or delete arbitrary cache files, gallery originals, project maps, `base.esx`, or referenced note media.
- Bound archive entry count, per-entry size, total extracted size, decoded image dimensions, and JSON/binary allocations. Keep ZIP extraction confined to a dedicated app cache directory.
- Capture an immutable survey snapshot before export. Missing input/output streams or payload files are export failures, not successful optional steps.

## UI and resources

- Put new user-visible text in `res/values/strings.xml`; do not add more hard-coded UI strings.
- Keep overlay geometry aligned through PhotoView pan/zoom and test coordinate conversion when changing either view.
- Do not decode full-resolution floor plans or note photos on the main thread without size limits/downsampling.
- Preserve accessibility labels and usable layouts on small screens when adding controls.

## Verification

Run the smallest relevant checks while iterating, then the broader checks before handoff:

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

For Wi-Fi, URI, camera/gallery, and lifecycle work, also perform targeted device/emulator checks. For ESX changes, inspect the produced ZIP and, when available, open it in the supported Ekahau version. Record any check that could not be run.

The current external compatibility fixture is archive format `2.0`, project schema `1.8.0`, with Ekahau AI Pro `11.6.0.1`/`11.6.3.1` history. Keep customer ESX fixtures outside the repository and select them through `WIPROBER_ESX_FIXTURE`.

## Git hygiene

- The worktree may already contain user changes. Inspect `git status`, preserve unrelated edits, and never reset or overwrite them.
- Do not commit `local.properties`, build outputs, IDE workspace state, captured maps, note photos, or customer ESX files.
- Keep refactors behavior-preserving and incremental. Separate compatibility changes from structural moves when practical so each can be reviewed and reverted independently.
