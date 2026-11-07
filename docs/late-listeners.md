# Joining during playback

Players already in the dimension when a disc starts can walk or teleport into range during playback. Their audio continues on the existing timeline.

Players who connect or change dimensions after insertion hear the next song. Eject and reinsert the current disc to start it for everyone.

Prepared mid-song joining and login preloading have been removed. Copper Speakers no longer generates, hosts or requests the additional joining-audio packs, and `/copperspeakers sync` is no longer available. Ordinary disc playback and speaker attachment continue to work.

Old `config/copper-speakers/tails/` and `config/copper-speakers/joining/` directories are unused and can be deleted to reclaim storage. Keep original music files and ordinary Java/Bedrock music packs. If original recordings are stored in `joining/sources/`, move them to a separate music directory before deleting `joining/`.

Historical timing measurements remain in [the benchmark archive](benchmarks/late-listeners/README.md).
