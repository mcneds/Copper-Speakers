# Prepared joining: local integration contract

This interface controls optional late joining in Copper Speakers. It does not publish songs, register jukebox songs, modify discs, rebuild the normal Java or Bedrock music packs, or restart Minecraft. Keep these settings separate from ordinary music publication records and jobs. Operators can also use the [in-game commands](late-listeners.md#per-song-control-while-the-server-runs), which persist changes through this same control plane.

Install the mod and matching Bedrock extension using their normal installation procedure. Once installed, these per-song operations run while the server stays up.

## Files and ownership

All paths below are relative to the dedicated server's `config/copper-speakers/joining/` directory.

| File | Owner | Purpose |
| --- | --- | --- |
| `settings.json` | Local bridge/operator | Desired per-song settings, format 1 |
| `status.json` | Copper Speakers | Last accepted revisions, preparation state and errors |
| `events.json` | Copper Speakers | Complete runtime prepared-event mappings for Bedrock |
| `preparer.json` | Operator | Optional JSON argv prefix for the preparation executable |
| `preparation.log` | Copper Speakers | Output from the latest preparation subprocess |
| `songs/<generation>/` | Copper Speakers | Immutable generated catalogue/packs and recovery metadata |

The bridge must serialize its writers and atomically replace the entire settings file in the same directory. Preserve other songs' rows. In-game commands serialize their own updates on the control worker; avoid simultaneous bridge writes and command updates, since external writers do not share that queue. Never edit generated directories, status, or mappings. File permissions should give write access only to the Minecraft operator and the trusted local bridge. Sources are local paths; executable configuration is administrator controlled. No shell evaluates request contents.

The server polls approximately once per second. A submitted revision is accepted only when it appears in `status.json`. Read `updatedAt` too: stale status is not a successful operation. The bridge may surface queued/preparing state immediately, then poll status. These files work through a shared local filesystem; an HTTP control service can wrap them without exposing Minecraft control publicly.

## Desired settings

Keys are the **original sound-event identifier**, not the disc UUID, item ID, buffered alias, or jukebox-song ID. Different songs using the same sound event share its setting because they share one recording.

```json
{
  "format": 1,
  "tracks": {
    "example_music:disc_example": {
      "revision": "unique-request-id",
      "enabled": true,
      "source": "/srv/music/published/disc_example.ogg",
      "spacingSeconds": 15,
      "deletePrepared": false
    }
  }
}
```

Every changed row needs a new unique `revision`. `enabled` is required; `spacingSeconds` defaults to **15**, with range 1–30. `deletePrepared` defaults to false and is valid only with `enabled: false`. `source`, when supplied, must be an absolute path to the exact published mono Ogg/Vorbis recording. Use immutable publication paths. Changing its bytes under the same path requires a new revision; content hashes prevent reuse of the previous recording. A source that changes during preparation fails validation.

Enabling with a source queues preparation of **only this sound**. Completed output is hash checked and registered with AutoHost on the server thread. It does not call Polymer's main pack generator. The preparation subprocess uses lossless Vorbis suffixes and waveform verification. Python 3, NumPy, FFmpeg and ffprobe must be installed for the Minecraft service user. The mod includes the Python preparer. The default command uses `python3` on Unix or `python` on Windows. For a virtual environment or a custom executable, `preparer.json` can contain an argv prefix such as:

```json
["/srv/music/venv/bin/python", "/srv/tools/prepare-join-audio.py"]
```

Copper Speakers appends `--output`, `--song`, and `--spacing` as separate arguments. The program must create a new output directory containing a valid single-song format-2 catalogue and its packs, then exit zero. The optional command is useful for a bridge-owned converter but must not invoke normal publication or restart scripts.

Enabling without `source` uses an existing catalogue entry. Songs omitted from settings preserve the existing catalogue's behavior; songs with no prepared entry play normally. An absent setting is not an explicit disable. Existing `tails/catalog.json` format-1 and format-2 catalogues remain intact and are still loaded.

## Disabling, deletion, and races

Set `enabled: false` to stop new admissions, including scheduled warmups. Existing listeners receive no stop, replay, or pack refresh. New insertions use ordinary playback while disabled. Keep the `source` and spacing fields so a later enable can reuse compatible prepared output.

Deletion is a separate request: set `enabled: false, deletePrepared: true` with a new revision. It removes this controller's generated versions only after their playback sessions and pending pack downloads release them. Disconnecting releases download protection. Natural completion retains a short buffer margin; ejection/unload releases playback immediately. Re-enable before deletion finishes to cancel deletion and reuse the audio. Original published files are never deleted.

**Legacy catalogues are immutable shared inputs.** They may contain several songs in the same ZIP. The controller does not rewrite or delete them; `legacyCatalogueRetained: true` reports their continued storage. Disabling still gates their admissions. An operator can migrate legacy audio separately if reclaiming that shared storage is needed. Managed per-song packs do not share this limitation.

Settings changes cancel obsolete jobs. A completion for an obsolete revision cannot activate that revision; verified unused output may be retained for reuse until deletion is requested. Failed conversion or validation leaves existing catalogue entries and current listeners untouched. `failed` is terminal for that revision: retry with a new revision. Malformed settings preserve the last valid configuration and report top-level `settingsError`. After a restart, completed owned generations are rediscovered; partial output is never activated. Playback sessions themselves are not resumed.

## Activation and player progress

`ready` means prepared audio is registered for the **next insertion**. An ordinary playing session has no dependable shared audio clock, so enabling cannot safely turn it into a prepared session. Its listeners continue without interruption. Eject/reinsert when you want the new behavior. Disabling and re-enabling admissions for an already prepared session can take effect during that session, using the prepared version it started with; replacing a recording or spacing uses the new version on the next insertion.

Joining runs silently during pack loading and scheduled admission. Loading and countdown messages are not sent to the action bar. A nearby listener receives the normal song title when playback begins. Only the joining player's packs and warmup change; existing listeners retain their continuous sources. The default joining wait after packs load is approximately 3–18 seconds, depending on the next actual Vorbis entry point and server/client delays.

## Status

```json
{
  "format": 1,
  "updatedAt": "2026-10-28T00:00:00Z",
  "tracks": {
    "example_music:disc_example": {
      "revision": "unique-request-id",
      "enabled": true,
      "spacingSeconds": 15,
      "state": "ready",
      "preparedVersions": 1,
      "activation": "next_insertion",
      "legacyCatalogueRetained": false,
      "activeSessions": 0,
      "pendingDownloads": 0
    }
  }
}
```

States are `preparing`, `ready`, `failed`, `disabled`, `deletion_pending`, and `unprepared`. `error` contains a preparation/validation error when present. `preparedVersions` counts controller-owned generations, including cached disabled versions. `activeSessions` includes protected natural endings; `pendingDownloads` counts outstanding pack requests for that song's prepared versions. Status rows correspond to explicitly configured songs. `disabled` with zero managed versions means managed deletion is complete; inspect `legacyCatalogueRetained` separately. A revision acknowledged as `preparing` still needs to reach `ready` before the next insertion can use it.

## Bedrock

The accompanying Geyser extension is in [`compat/bedrock/`](../compat/bedrock/README.md). Its existing audio coordination, voxel and plate fixes are preserved. The installed extension needs this update once; **subsequent songs do not require rebuilding it**.

New prepared sound IDs are self-describing:

```text
copper_speakers:tail/v1/<original-sound-utf8-hex>/<source-sha256-first-24>/<sample-rate>/<offset-frames>
```

The extension resolves the original event and `offsetFrames / sampleRate` directly, then uses its buffered sound mapping and Bedrock playback-position support. Nonzero starts reopen muted, seek while running, and unmute together so a dimension return resumes at the current position. It never needs Java suffix audio. This avoids a race between live registration and mapping refresh. Legacy opaque IDs also resolve through `events.json`, which is replaced atomically and loaded on a background thread once per second. Its format is:

```json
{"format":1,"events":{"copper_speakers:tail/opaque/123":{"sound":"example:original","offsetSeconds":15.25}}}
```

The extension keeps its last valid table on reload failure and retains the embedded legacy table as fallback. Custom sound names are preserved by default. Optional aliases in the extension's `sounds.json` map Java events to existing Bedrock buffered definitions; see [Bedrock setup](../compat/bedrock/README.md).

The default mapping path is `config/copper-speakers/joining/events.json` relative to Geyser's working directory; override with the JVM property `copperbedrock.preparedEvents`. A separate Geyser host can mirror this file atomically. Self-describing events need no shared filesystem.

The current extension enables offset handling for its supported Bedrock protocol, **2225 (26.60)**. Protocol codec tests verify offsets, sound handles, and seeking packets; they are not a physical Bedrock listening test. The original song's Bedrock audio and buffered definition must already be present in the player's ordinary pack. Delivering entirely new original audio to Bedrock remains the normal music publication workflow, with its own client pack/session requirements. Prepared joining does not rebuild or hot-reload that pack.

## Local commands and verification

```sh
python tools/joining-control.py --root /srv/minecraft/config/copper-speakers/joining enable example:original --source /srv/music/original.ogg
python tools/joining-control.py --root /srv/minecraft/config/copper-speakers/joining disable example:original
python tools/joining-control.py --root /srv/minecraft/config/copper-speakers/joining delete example:original
python tools/joining-control.py --root /srv/minecraft/config/copper-speakers/joining status
```

The helper submits an atomic settings revision and prints its ID; it does not wait for acceptance. Use `status` to follow it. For testing:

```sh
./gradlew build prepareRuns
python tools/prepare-join-audio.py --output prepared-test --assets /path/to/minecraft/assets --vanilla cat
python tools/verify-join-audio.py prepared-test
python tools/check-multiclient-audio.py --name live-joining-test --catalog prepared-test --live-source /path/to/original-cat.ogg --diagnostic --client-heap 1G --server-heap 768M
```

The last command starts a disposable loopback server and two clients. It checks live preparation while ordinary music plays, activation at reinsertion, late joining, silent admission, source continuity, disabled admissions after a dimension return, deferred deletion, and unchanged main-pack bytes. Diagnostic mode checks functionality only. Attempt logs and results stay under ignored `build/validation/`; no live-server credentials or deployment scripts are used.

Automated build coverage and release packaging are described in [Releases](releases.md).
