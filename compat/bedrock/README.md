# Bedrock compatibility

The **Bedrock Fabric bundle** is built with the Java mod for every release. It contains:

- The Copper Speakers mod for `mods/`.
- CopperBedrock and the patched GeyserDisplayEntity for `config/Geyser-Fabric/extensions/`.
- Both matching Bedrock packs for `config/Geyser-Fabric/packs/`.
- Block and item mappings for `config/Geyser-Fabric/custom_mappings/`.
- Installation instructions, compatibility metadata, checksums, licenses and the display fork's corresponding source.

Install matching Fabric API, Polymer and Geyser-Fabric separately. Follow the included [installation guide](INSTALL.md); remove older copies of these extension JARs when replacing them. No installer connects to or restarts a server.

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

## Verification and source

The build runs the exact supported Bedrock codec through stop, seek, volume, pause/resume and offset round trips; coordination tests exercise continuous listeners, attachment, replacement and disconnects. Visual tests verify geometry/UV conversion, face rotations, empty carriers and preservation of unrelated displays. Bundle audits check matching versions, file layout and source/license inclusion.

These automated checks do not measure physical Bedrock client timing or prove rendering on other protocols. Check playback, range entry, attachment, stopping, wires, plates and particles in game after installation. Java timing benchmarks apply to Java clients.

GeyserDisplayEntity is [upstream AGPL-3.0 source](https://github.com/GeyserExtensionists/GeyserDisplayEntity). The release includes its complete patched source tree, license, patch inputs and portable rebuild recipe. Copper Speakers and CopperBedrock are MIT licensed. See [release maintenance](../../docs/releases.md) for changing versions and dependency pins.
