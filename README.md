# Copper Speakers

Copper Speakers is a Fabric/Polymer mod for **Minecraft Java Edition 26.3**, for single-player worlds and multiplayer servers. Place a jukebox, lay copper wire, connect speakers, and insert a music disc to play it around your build.

**All vanilla music discs work.** Custom discs that use Minecraft's normal jukebox system also play through connected speakers, using the audio supplied by their mod or resource pack.

## Installation

Install Copper Speakers, **Fabric API**, and **bundled Polymer for Minecraft 26.3**. This release requires Fabric Loader **0.19.5 or newer**, Java **25**, and Polymer **0.18.4 or a later 0.18.x release**.

### Single-player

Put the three mod jars in your Fabric instance's `mods` folder, then open your world. Copper Speakers runs on the integrated server, and Polymer supplies the generated resource pack locally.

### Multiplayer servers

Put the three mod jars in the Fabric server's `mods` folder and restart it. Bundled Polymer includes AutoHost, which generates and serves the resource pack.

Players **do not need Copper Speakers, Fabric, or Polymer installed on their clients** to join. They only need to accept the server resource pack. Players using modded clients can join too; an unmodified client is not a requirement.

Keep Polymer AutoHost enabled and allow its mod-required resource pack setting. Servers behind proxies may need to configure the advertised pack address. After a pack update, reconnect to download the new version.

## Build your first system

1. Craft a speaker and some copper wire.
2. Place a jukebox and speaker on the floor, with a continuous wire route between their sides.
3. Stand near the jukebox or speaker and insert a music disc.
4. Add speakers along the route to play the same disc elsewhere in the build. Eject the disc to stop the group.

One jukebox supplies the network; speakers do not hold their own discs. Normal insertion, ejection, hoppers, comparators and redstone behavior are preserved.

### Recipes

| Item | Recipe | Output |
| --- | --- | --- |
| Copper wire | Three copper ingots in a horizontal row | 12 wire |
| Speaker | Paper above a note block, with copper ingots to its left, right and below | 1 speaker |
| Wall plate | Copper ingots in both outer columns, with copper wire in the center | 2 wall plates |

Collect copper and a note block to unlock the corresponding recipe-book entries.

## Wiring

- Place wire on a solid top surface or a hopper. It connects horizontally and follows one-block steps, like redstone dust. Put wire below and on top of the ledge and leave headroom over the lower wire.
- Jukeboxes, speakers and wall plates connect to wire at the same height. Unsupported vertical runs and jumps taller than one block do not connect.
- Wire carries music; it does not power or connect to redstone dust.
- Speakers face toward the player when placed. Music notes travel out of the front while playing; facing affects the visuals, not the listening range.

### Through walls

Use a wall plate on a full, solid block to fit sockets on its opposite faces. Wire in front of either face connects through the block. Plate adjoining full blocks to extend the route through thicker walls. The plated block connects to neighboring nodes horizontally at its own height.

Side clicks select the plate's direction; top or bottom clicks use the player's horizontal facing. Partial blocks, fluids, block entities and speakers cannot host plates. Punch either exposed plate to remove the pair and recover the item in survival.

### Insulation

Right-click wire with a **resin clump** to insulate it. Bare wire washes away in water; insulated wire survives and can be waterlogged with a water source. Rain does not affect playback.

Use a bucket to drain waterlogged wire. Right-click dry insulated wire with an **axe** to strip it without breaking the cable or interrupting music. This uses one durability in survival and does not return resin. Mining insulated wire in survival drops one wire and one resin clump.

## Discs and playback

Every built-in disc works without conversion or special speaker items. Custom discs work when they use the standard jukebox song system and their audio is available to the client. Install the disc's own mod, datapack and resource pack as normally required; Copper Speakers does not supply their music or remove their dependencies.

Built-in songs use buffered audio for closer alignment between speakers. Other songs retain their own audio settings unless their pack provides an optional buffered alias. **Streamed custom songs can have more timing variation** and consume streaming sound channels; playback is supported without claiming sample-accurate synchronization. Pack authors can find the optional buffering format in [Audio compatibility](docs/audio.md).

- Be within **64 blocks of any connected sound source when the disc starts**. You can then walk between that group's speakers while the route stays loaded.
- Players arriving later or changing dimensions need a fresh disc insertion to join the playback.
- If a speaker was connected at the start, up to **four additional speakers at a time** can join during that track. Disconnecting one of these frees its slot. Further speakers wait for a slot or the next insertion.
- The first active jukebox owns a connected network. A second disc on that network waits silently and must be ejected and reinserted to start its own group.
- Copper Speakers does not keep chunks loaded. Unloading the main jukebox ends its session; returning or restarting the game does not resume it.
- Large groups can reach the client's sound-channel limit. Networks are limited to **4,096 nodes**; an oversized network leaves the jukebox playing alone.

Buffered songs must decode before they begin, and long songs can take more time and client memory. Ejection, hopper extraction or immediate replacement stops any remaining audio, including a delayed ending. See [Audio compatibility](docs/audio.md) for the playback model and limits.

## Creative demo

With commands enabled, run:

```mcfunction
/function copper_speakers:demo
```

This **replaces an 11 by 7 by 4 area** around and below the command position with a platform, jukebox, cable and four speakers, then gives the player supplies. Use it in an empty area and insert the supplied Cat disc.

## Building and development

With a Java 25 JDK:

```powershell
./gradlew.bat build
```

The release jar is `build/libs/copper-speakers-0.1.9.jar`. The build runs graph unit tests and Fabric server GameTests. Test fixtures and audio observation code are excluded from the release jar.

See [Development](docs/development.md) for local launches, client checks and asset generation. Java Edition is the tested target; Bedrock playback through Geyser has not been verified.

## Benchmarks

The **0.1.9 benchmark** exercises actual disc insertion and production playback packets at 3, 8 and 16 sound positions, including the four reserved voices. It compares repeated cold-cache starts and cached replays for a vanilla disc and a synthetic custom disc with a buffered alias.

![Copper Speakers 0.1.9 measured start timing](docs/benchmarks/timing.png)

![Copper Speakers 0.1.9 observed startup wait](docs/benchmarks/startup.png)

These are instrumented client measurements on one machine, not a sample-accurate guarantee or a streamed-disc benchmark. [Results, memory measurements, limitations and reproduction instructions](docs/benchmarks/README.md) include every trial and failed launch attempt.

## License

[MIT](LICENSE). Minecraft and dependency assets retain their respective licenses.
