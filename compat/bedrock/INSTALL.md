# Install the Fabric Bedrock bundle

This bundle contains the Copper Speakers mod, CopperBedrock, the patched
GeyserDisplayEntity extension, their two matching resource packs and two mappings.
`compatibility.json` records the exact supported Minecraft, Geyser build and
Bedrock protocol. Install matching Fabric API, Polymer and Geyser-Fabric separately.

1. Back up the files you are replacing and stop your server for installation.
2. Copy `mods/` and `config/` from the bundle into your Fabric server directory.
   Replace older Copper Speakers/CopperBedrock/patched GeyserDisplayEntity JARs;
   keep one JAR for each extension. Preserve unrelated mods, packs and mappings.
3. Keep Polymer AutoHost enabled. Let Bedrock players accept both supplied packs.
4. Start the server and verify playback, wire geometry, wall plates and notes with
   a supported Bedrock client. Codec/build checks do not establish audible timing.

The release contains baseline Polymer allocations for Copper Speakers alone.
If your other mods or Polymer settings change allocations, rebuild the bundle
from that server's generated Polymer resource pack using `-PbedrockJavaPack=...`.
`compatibility.json` contains the source pack fingerprint. The extension and its
packs/mappings must come from the same build.

Custom music stays in your own Java/Bedrock music packs. Prepared joining uses
the original Bedrock recording plus playback offsets; no Java suffix audio is
bundled. For explicit custom sound aliases, copy `examples/sounds.json` to
`config/Geyser-Fabric/extensions/copperbedrock/sounds.json` and edit its entries.
Keys are Java sound events; values are existing sound definitions in your
Bedrock pack. Custom buffered events retain their names by default. The example
file is not installed as configuration automatically.

The patched display extension is AGPL-3.0. Its complete corresponding source,
upstream license, patch inputs and rebuild recipe are included under `sources/`.
The main mod and CopperBedrock use the included MIT license. Neither extension
is part of the main mod JAR. This bundle installs no server manager, deployment
agent or restart service.
