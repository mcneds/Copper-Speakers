# Run at a clear location. Re-running replaces this 11 x 7 test platform.
fill ~ ~-1 ~ ~10 ~-1 ~6 minecraft:stone
fill ~ ~ ~ ~10 ~2 ~6 minecraft:air
setblock ~1 ~ ~3 minecraft:jukebox
fill ~2 ~ ~3 ~8 ~ ~3 copper_speakers:copper_wire
setblock ~3 ~ ~2 copper_speakers:speaker[facing=north]
setblock ~5 ~ ~2 copper_speakers:speaker[facing=north]
setblock ~7 ~ ~2 copper_speakers:speaker[facing=north]
setblock ~9 ~ ~3 copper_speakers:speaker[facing=west]
give @s minecraft:music_disc_cat
give @s copper_speakers:copper_wire 32
give @s copper_speakers:speaker 4
give @s minecraft:resin_clump 16
give @s minecraft:water_bucket
give @s copper_speakers:wall_plate 8
