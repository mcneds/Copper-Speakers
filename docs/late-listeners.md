# Joining music already in progress

Copper Speakers supports late listeners using a server-side audio catalogue and ordinary resource packs. Players need no client mod. Both standalone jukeboxes and wired speaker networks use this playback handling.

Players already in the dimension receive playback at insertion, including players outside listening range. Minecraft's normal distance attenuation makes it audible when they approach. Walking into range therefore needs no restart or new download.

Players connecting after insertion or returning from another dimension need prepared joining audio for the track. They wait for their packs to finish loading, then normally another **3–18 seconds** with the default settings. They start at a future point on the existing song timeline. The other listeners receive no restart, stop, or resource-pack refresh.

## Per-song control while the server runs

Operators with permission level 3 or higher can control reconnect synchronization in chat or from the server console:

```mcfunction
/copperspeakers sync enable example:song_sound
/copperspeakers sync disable example:song_sound
/copperspeakers sync status example:song_sound
```

Use the original **sound-event identifier**; tab completion lists registered jukebox sounds and configured tracks. Enabling reuses a saved source or existing prepared catalogue. For a song with no prepared audio or saved source, supply the absolute path to its published mono Ogg/Vorbis recording on the server:

```mcfunction
/copperspeakers sync enable example:song_sound "/srv/music/song.ogg"
```

On Windows, use a quoted path with forward slashes, such as `"C:/Music/song.ogg"`. First-time preparation needs Python 3, NumPy, FFmpeg and ffprobe installed for the Minecraft service user. Preparation runs in the background; use `status` to check for `ready` or inspect a failure. Once ready, eject and reinsert the disc to activate prepared joining. Disabling prevents new admissions while current listeners keep playing.

The default is 15-second joining points. These commands run without rebuilding the main music pack or restarting Minecraft. An already playing ordinary song keeps its listeners and uses prepared joining on the next insertion. Legacy catalogues remain supported. See the [local integration contract](prepared-joining.md) for file-based controls and deferred deletion.

## Prepare a legacy catalogue

On the server or another computer, install Python 3, NumPy, FFmpeg and ffprobe. The latter two must be on `PATH`. Run this from the repository:

```sh
python -m pip install numpy
python tools/prepare-join-audio.py --output prepared-music --vanilla
```

This downloads the official Minecraft 26.3 disc assets, verifies their hashes, and builds all vanilla entries. To reuse an installed Minecraft asset cache, add `--assets /path/to/assets`. `--vanilla cat blocks` prepares only those named discs.

For custom songs, add one or more `--song` arguments. Use the **sound-event identifier**, which may differ from the registered jukebox-song identifier:

```sh
python tools/prepare-join-audio.py --output prepared-music --vanilla \
  --song example:evening_sound=/path/to/evening.ogg
```

The source must be the same mono Ogg/Vorbis recording supplied by the song's pack. This works with ordinary registered jukebox songs and does not require a particular disc item, namespace, buffered alias or music service. Random sound variants and independently replaced client audio are not a shared timeline; choose one fixed recording for a prepared sound event. Stereo sources must first be converted to mono for positional playback.

Stop the dedicated server, copy the generated directory to **`config/copper-speakers/tails`**, and restart. It must contain `catalog.json` and its ZIP files. Keep Polymer AutoHost enabled with its required pack setting. Never edit or replace packs while the server is running. Regenerate the relevant catalogue when a recording changes or a new custom song is added; discs without a catalogue entry continue using original playback.

Prepared joining packs currently require a dedicated server. Polymer skips external pack delivery for an integrated server's owner, so single-player and LAN worlds use original playback. Walking into range in the same dimension works there too; returning from another dimension requires reinsertion.

The generator refuses to overwrite an existing output directory. Music stays outside the mod jar and Git repository. Do not publish generated third-party recordings with the source code.

## Delivery and timing

The server stores a full recording and continuous suffixes at roughly fifteen-second intervals. These are copied Vorbis packets rather than separately re-encoded chunks. The catalogue accounts for Minecraft's decoder lead-in and each suffix's actual sample offset. A small codec startup transient can remain at a join; there are no repeated segment boundaries during subsequent playback.

Legacy catalogue base packs are still delivered at login. Other prepared packs are requested after entering the world, only for a player who needs to join a playing song, so their action bar can show loading progress. New per-song full recordings are included in that live request. The server waits for that player's successful pack-load responses before scheduling audio. Other players keep their existing packs and sound sources.

Initial prepared playback gives clients three seconds for decoding after the warm-up packets are sent. If the server stalls before anyone starts, it defers the whole beginning rather than skipping the introduction. Once any listener has started, the session's timeline stays fixed. A newcomer gets at least three seconds of preparation, then the next available entry point; an admission that misses its deadline by more than 150 ms moves to a later entry point. There is no suitable entry point during the last few seconds of a track; a newcomer then waits for the next disc.

Synchronization is approximate. Vanilla clients cannot acknowledge when their audio actually starts, and network latency, frame stalls and output devices can introduce differences. The server does not seek or continually correct a running client. Local two-client measurements are documented in the benchmarks; they are not a guarantee for Internet connections or physical speakers.

Prepared packs use more server storage than original recordings because suffixes overlap. Downloads are split into packs below Minecraft's size limit; the default audio budget is 128 MiB per ZIP. `--spacing 30` reduces storage further and increases the maximum joining wait. Full decoded recordings also consume client memory, and every source uses a sound channel. Large networks or many concurrent tracks can exceed client limits.

Manually reloading client sounds/resources during playback can interrupt that client's audio; reconnect to rejoin. Unloading the main jukebox ends its session. Playback is not resumed across a server restart.

## Verify with Minecraft's decoder

From a development checkout with Java 25:

```sh
./gradlew build prepareRuns
python tools/verify-join-audio.py prepared-music
python tools/check-multiclient-audio.py --name join-test-01 --catalog prepared-music --extended
```

The decoder check verifies suffix frame counts and every sample after the codec lead-in against the full recording using Minecraft's own JOrbis implementation. The two-client test runs a dedicated loopback server and two separate observing clients. Its observer reads native audio state and does not implement playback, seeking or synchronization.

`--packaged` tests the built release jar. On a constrained test machine, `--client-heap 1G --server-heap 768M` reduces the isolated runtimes' maximum heaps; these settings are recorded with the results. Windows validation can also add `--vanilla-smoke` after installing `pycaw` into `build/audio-meter`. That check launches the untouched publisher client and reads only its own OS audio-session peak to verify distant silence, nearby music and explicit stopping. It records no audio and makes no synchronization measurement.

To combine separately prepared catalogues without regenerating audio:

```sh
python tools/merge-join-audio.py prepared-vanilla prepared-custom --output prepared-music
```
