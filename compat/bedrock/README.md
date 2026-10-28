# Bedrock compatibility

## Download and install 0.3.0

Download [`copper-speakers-0.3.0-bedrock-fabric.zip`](https://github.com/mcneds/Copper-Speakers/releases/download/v0.3.0/copper-speakers-0.3.0-bedrock-fabric.zip) from [Copper Speakers 0.3.0](https://github.com/mcneds/Copper-Speakers/releases/tag/v0.3.0). This is the complete Fabric/Geyser compatibility bundle, including the Java mod. The standalone `copper-speakers-0.3.0.jar` is sufficient for Java players; Bedrock players also need the extensions, packs and mappings in the ZIP.

The **Bedrock Fabric bundle** is built with the Java mod for every release. It contains:

- The Copper Speakers mod for `mods/`.
- CopperBedrock and the patched GeyserDisplayEntity for `config/Geyser-Fabric/extensions/`.
- Both matching Bedrock packs for `config/Geyser-Fabric/packs/`.
- Block and item mappings for `config/Geyser-Fabric/custom_mappings/`.
- Installation instructions, compatibility metadata, checksums, licenses and the display fork's corresponding source.

Install matching Fabric API, Polymer and Geyser-Fabric separately. Follow the included [installation guide](INSTALL.md); remove older copies of these extension JARs when replacing them. No installer connects to or restarts a server.

1. Install Fabric Loader **0.19.5+**, Fabric API for **Minecraft 26.3**, the **Polymer 0.18.x bundle** for 26.3 (built against 0.18.4), and **Geyser-Fabric 2.12.1 build 1273**, using **Java 25**.
2. Stop the server and back up the files you will replace. Extract the ZIP's `mods/` and `config/` folders into the server directory. Keep one copy of each supplied mod/extension JAR and preserve unrelated files.
3. Keep Polymer AutoHost enabled and have Bedrock players accept both supplied resource packs. Start the server and check playback, wires, speakers, wall plates and note particles.

| Bundle location | Install into the server |
| --- | --- |
| `mods/copper-speakers-0.3.0.jar` | `mods/` |
| `config/Geyser-Fabric/extensions/*.jar` | Both CopperBedrock and the patched GeyserDisplayEntity in the same extensions folder |
| `config/Geyser-Fabric/packs/*.mcpack` | Both matching packs in the same packs folder |
| `config/Geyser-Fabric/custom_mappings/*.json` | Both block and item mappings in the same custom_mappings folder |

Use the extensions, packs and mappings from the same bundle. The release's `SHA256SUMS` verifies the downloaded artifacts; the ZIP includes a second `SHA256SUMS` for its contents. `compatibility.json` records the supported build and source pack fingerprint. The display extension's corresponding source is included inside the bundle and as a separate release download.

## Compatibility

The supported target is Java **26.3**, Java runtime **25**, Geyser-Fabric **2.12.1 build 1273**, and Bedrock **26.60 / protocol 2225**. Dependency URLs, checksums and the display source commit are in [dependencies.lock.json](dependencies.lock.json). Other Geyser builds need recompilation and protocol/client checks because this integration uses internal APIs.

CopperBedrock translates entity-bound audio anchors into Bedrock sound handles. It preserves current song positions when a spare voice moves to a speaker and uses original audio plus playback offsets for prepared late joins. New prepared events are self-describing; legacy events can be refreshed from the [runtime mapping file](../../docs/prepared-joining.md). Adding a song does not require rebuilding the extension.

The visual converter preserves the supplied Java pack's models and allocated states. The display fork renders Copper meshes and note particles while retaining upstream behavior for other displays. Wall plates retain their frame IDs and orientation. A scoped protocol-2225 hook supplies empty/unit-cube voxel shapes only when Geyser sends an empty registry.

## Build everything

Install JDK 25, Python 3.11 or newer, and the pinned Python dependency:

```sh
python -m pip install -r compat/bedrock/requirements.txt
./gradlew releaseBundle
```

On Windows use `gradlew.bat`. To select a Python virtual environment, add `-Ppython=/path/to/python`. Outputs are under **`build/release/dist/`**. The same command runs in CI; it builds/tests the Java mod, exports a baseline Polymer pack, downloads hash-checked dependencies, runs protocol/coordination and visual tests, builds both extensions and packs, then audits the assembled bundle.

The output directory must be empty. Use `-PreleaseOutput=build/release-second` for another build. Nothing is read from an installed server, private wiki, music catalogue or external icon directory. Inventory icons are rendered from the supplied models and textures.

## Servers with other Polymer mods

The published bundle uses the baseline allocations generated with Copper Speakers alone. Other mods or Polymer settings can change those allocations. Build matching mappings and display indices from the **actual server-generated resource pack**:

```sh
./gradlew releaseBundle -PbedrockJavaPack=/path/to/polymer/resource_pack.zip -PreleaseOutput=build/my-server-bundle
```

This reads that pack and writes a new bundle locally. It neither edits the pack nor contacts its server. `compatibility.json` records a content fingerprint of the source allocations/assets. Install the two extensions, packs and mappings from the same bundle. Prepared joining settings and ordinary music publication do not alter these visual allocations.

## Custom music

Keep your original music in its ordinary Java and Bedrock packs. This bundle includes buffered aliases for vanilla discs, not music files. Custom buffered event names are preserved. Where Java and Bedrock names differ, place an explicit mapping in the CopperBedrock extension's data folder as `sounds.json`:

```json
{"example_music:evening": "example_music:buffered.evening"}
```

The target definition must exist in your Bedrock music pack. Mappings are loaded at extension startup. Prepared joining uses the original recording and an offset, so it does not require the Java suffix packs on Bedrock. The local [per-song control interface](../../docs/prepared-joining.md) remains independent of music publication.

On a dedicated server, operators can use `/copperspeakers sync enable <sound>`, `/copperspeakers sync disable <sound>` and `/copperspeakers sync status <sound>`. First-time preparation accepts a quoted absolute server path to a mono Ogg/Vorbis recording after the sound identifier. Wait for `ready`, then reinsert the disc to start a session with the new settings. These commands prepare Java rejoin audio; publish the original recording in the Bedrock music pack separately. See [command examples](../../docs/late-listeners.md#per-song-control-while-the-server-runs).

## Verification and source

The build runs the exact supported Bedrock codec through stop, seek, volume, pause/resume and offset round trips; coordination tests exercise continuous listeners, attachment, replacement and disconnects. Visual tests verify geometry/UV conversion, face rotations, empty carriers and preservation of unrelated displays. Bundle audits check matching versions, file layout and source/license inclusion.

These automated checks do not measure physical Bedrock client timing or prove rendering on other protocols. Check playback, range entry, attachment, stopping, wires, plates and particles in game after installation. Java timing benchmarks apply to Java clients.

For a new speaker joining an active track, CopperBedrock prepares it muted, seeks to the current song position, then lets it advance silently while the audio settles. The final callback only restores its volume. This prevents a delayed callback from leaving the newcomer at an outdated predicted position. Initial starts still remain paused until their common release to preserve the opening. Coordination tests include a delayed callback and cancellation during silent settling; these check the scheduling behavior, not client audio latency.

GeyserDisplayEntity is [upstream AGPL-3.0 source](https://github.com/GeyserExtensionists/GeyserDisplayEntity). The release includes its complete patched source tree, license, patch inputs and portable rebuild recipe. Copper Speakers and CopperBedrock are MIT licensed. See [release maintenance](../../docs/releases.md) for changing versions and dependency pins.
