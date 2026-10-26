# Audio compatibility

Copper Speakers handles music through Minecraft's standard registered jukebox songs and `minecraft:jukebox_playable` component. It does not depend on a particular disc item, namespace, song name, server or music service. A song's sound event may have a different identifier from the song itself.

All 22 built-in discs are supported. Registered custom songs work through the same network using their original sound settings. Their usual client audio assets must be present. Mods that replace the jukebox with their own block or play music outside the standard song system may require a separate integration.

## Buffered and original audio

For unchanged built-in songs, the mod supplies `copper_speakers:buffered/<song>` jukebox-song aliases and `copper_speakers:buffered.<song>` sound events. These reference `minecraft:records/<song>` with `stream=false, preload=false`. No music is copied into the mod.

For other registered songs, the original song is used unless a matching buffered alias is available. Missing or invalid aliases fall back to the original song on the connected speakers. A datapack that replaces a vanilla song's sound event therefore plays that replacement, rather than the old built-in file.

Custom streamed audio starts normally at each source, but separate stream decoding can cause more timing variation than a shared decoded buffer. Minecraft also has a limited pool of streaming channels, so streamed tracks can reach the channel limit with fewer speakers or simultaneous networks. Copper Speakers does not force arbitrary sounds into memory or eagerly preload a catalogue.

## Optional aliases for pack authors

Any custom namespace can opt into buffering. For a song named `example:evening` whose original sound event is also `example:evening`, supply:

| Resource | Identifier or path |
| --- | --- |
| Original jukebox song | `example:evening` |
| Buffered jukebox song | `example:buffered/evening` |
| Buffered sound event | `example:buffered.evening` |
| Shared audio file | For example, `assets/example/sounds/music/evening.ogg` |

The buffered jukebox song must copy the original description, `length_in_seconds` and `comparator_output` exactly, changing only its sound event to `example:buffered.evening`. In `assets/example/sounds.json`, define the sound as:

```json
{
  "buffered.evening": {
    "sounds": [
      {
        "name": "example:music/evening",
        "type": "file",
        "stream": false,
        "preload": false
      }
    ]
  }
}
```

Reference the exact same audio file as the original song. Use a single file for predictable playback; random sound variants can choose different audio at different sources. Keep the original disc pointing at the original song so standalone jukeboxes preserve their normal sound settings. Aliases are an optimization, not a requirement for speaker support.

The server can validate the registered alias's metadata but cannot inspect every resource pack a client might load. Pack authors are responsible for keeping the sound definition and audio correct. Resource packs replacing built-in disc audio should replace the corresponding `minecraft:records/<song>` file or supply matching buffered sounds too.

## Network sessions

A bounded traversal discovers loaded, physically adjacent nodes at insertion. Cable length affects discovery work, not an intentional audio delay. At most 4,096 nodes participate; oversized circuits fall back to a standalone jukebox without partially selecting speakers. No chunk tickets are added.

Each session freezes its listeners and original sources, then starts four spare voices alongside them. A player within 64 blocks of any source receives starts for every source in the group, including distant speakers. The server sends the starts in a vanilla packet bundle; bundles are not atomic audio starts.

Original sources use ordinary jukebox level events. Spare voices use entity-bound sounds attached to invisible, non-colliding packet-only markers parked out of earshot. Connecting a new speaker moves an already-playing voice to it. Disconnecting that speaker parks the voice for reuse; no seeking or new sound start occurs.

Block changes trigger bounded topology updates. Disconnecting an original source stops it. Chunk unloads exclude unavailable routes and stop affected sources. Unloading the master ends the whole session. Saved jukebox items and vanilla timer data persist, but network sessions are not saved or resumed.

The earliest active session owns newly joined speakers when separate networks become connected. Already-playing groups keep their original sources. A later disc in an occupied circuit stays silent while its vanilla timer and container behavior continue; reinsertion is required to claim a new session.

## Completion and limits

Vanilla finishes the song timer at its declared duration plus 20 ticks. Natural completion releases network ownership and retires unused spare voices while allowing delayed client audio to finish. Ejection, replacement, destruction, hopper extraction or unload explicitly cancels any remaining tail. New playback stops overlapping old tails before starting.

Synchronization is best effort, not sample-accurate. There is no vanilla packet for seeking or acknowledging audio readiness. Players arriving later, changing dimensions or reloading sounds need a fresh disc insertion. A client that runs out of sound channels cannot play every source.

Buffered playback waits for full decoding and retains decoded data until a sound-engine/resource reload. Long tracks can use significant memory, and playing many different discs can accumulate buffers. These limits apply in single-player and on servers; Copper Speakers does not install a separate client audio implementation.
