"""Rebuild code-native models and metadata. Copies no Mojang textures or audio."""
import argparse
import json
import math
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/main/resources"
NS = "copper_speakers"

def write(path, value):
    target = RES / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

def cube(start, end, texture):
    return {"from": start, "to": end, "faces": {
        side: {"texture": "#" + texture} for side in ("north", "east", "south", "west", "up", "down")}}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    args = parser.parse_args()
    sounds = {}
    with zipfile.ZipFile(args.minecraft_jar) as jar:
        for path in sorted(jar.namelist()):
            if not path.startswith("data/minecraft/jukebox_song/") or not path.endswith(".json"):
                continue
            song = json.loads(jar.read(path))
            name = Path(path).stem
            assert song["sound_event"] == "minecraft:music_disc." + name
            sounds["buffered." + name] = {"sounds": [{
                "name": "minecraft:records/" + name, "type": "file", "stream": False, "preload": False}]}
            song["sound_event"] = {"sound_id": NS + ":buffered." + name}
            write(f"data/{NS}/jukebox_song/buffered/{name}.json", song)
    write(f"assets/{NS}/sounds.json", sounds)
    for insulated in (False, True):
        for mask in range(16):
            # A one-pixel shell on each side makes resin legible without raised spots.
            lo, hi, height = (5, 11, 2) if insulated else (6, 10, 1)
            elements = [cube([lo, 0, lo], [hi, height, hi], "cable")]
            arms = [([lo, 0, 0], [hi, height, lo]), ([hi, 0, lo], [16, height, hi]),
                    ([lo, 0, hi], [hi, height, 16]), ([0, 0, lo], [lo, height, hi])]
            for i, (start, end) in enumerate(arms):
                if mask & (1 << i):
                    elements.append(cube(start, end, "cable"))
            name = ("insulated_wire_" if insulated else "wire_") + str(mask)
            write(f"assets/{NS}/models/block/{name}.json", {
                "parent": "minecraft:block/block",
                "ambientocclusion": False,
                "textures": {"cable": "minecraft:block/resin_block" if insulated else "minecraft:block/copper_block",
                             "copper": "minecraft:block/copper_block", "particle": "#cable"},
                "elements": elements})
            if insulated:
                write(f"assets/{NS}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:block/{name}"}})
    # Polymer displays supply the rising faces without exhausting the limited flat/waterlogged block carriers.
    for insulated in (False, True):
        lo, hi, depth = (5, 11, 2) if insulated else (6, 10, 1)
        # Meet the upper cable across a face, not just the ledge's corner.
        # One pixel also joins bare/resin transitions without protruding above bare wire.
        top = 17
        sides = {"north": ([lo, 0, 0], [hi, top, depth]),
                 "east": ([16 - depth, 0, lo], [16, top, hi]),
                 "south": ([lo, 0, 16 - depth], [hi, top, 16]),
                 "west": ([0, 0, lo], [depth, top, hi])}
        for side, (start, end) in sides.items():
            name = ("insulated_" if insulated else "") + "wire_riser_" + side
            write(f"assets/{NS}/models/block/{name}.json", {
                "ambientocclusion": False,
                "textures": {"cable": "minecraft:block/resin_block" if insulated else "minecraft:block/copper_block",
                             "particle": "#cable"},
                "elements": [cube(start, end, "cable")]})
            write(f"assets/{NS}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:block/{name}"}})
    # Leads are centered on the neighboring wire (for light sampling) and end at the socket's center.
    # Their front sits beyond both the plate backing and an ordinary full-height cable riser.
    for insulated in (False, True):
        lo, hi, depth = (6, 10, 3) if insulated else (7, 9, 2.5)
        # Bury the rear face slightly in the wall so the lead has no daylight behind it.
        sides = {"north": ([lo, 0, 16 - depth], [hi, 8, 16.125]),
                 "east": ([-.125, 0, lo], [depth, 8, hi]),
                 "south": ([lo, 0, -.125], [hi, 8, depth]),
                 "west": ([16 - depth, 0, lo], [16.125, 8, hi])}
        for side, (start, end) in sides.items():
            name = ("insulated_" if insulated else "") + "wall_plate_lead_" + side
            write(f"assets/{NS}/models/block/{name}.json", {
                "ambientocclusion": False,
                "textures": {"cable": "minecraft:block/resin_block" if insulated else "minecraft:block/copper_block",
                             "particle": "#cable"},
                "elements": [cube(start, end, "cable")]})
            write(f"assets/{NS}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:block/{name}"}})
    cabinet = cube([0, 0, 0], [16, 16, 16], "wood")
    cabinet["faces"]["north"] = {"texture": "#front", "uv": [0, 0, 16, 16]}
    write(f"assets/{NS}/models/block/speaker.json", {"parent": "minecraft:block/block",
          "textures": {"wood": "minecraft:block/note_block", "front": f"{NS}:block/speaker_front",
                       "particle": "#wood"}, "elements": [cabinet]})
    # Reference the client's vanilla note sprite without copying its bitmap.
    write("assets/minecraft/atlases/items.json", {"sources": [{"type": "minecraft:single",
          "resource": "minecraft:particle/note", "sprite": f"{NS}:item/speaker_note"}]})
    write(f"assets/{NS}/models/item/speaker_note.json", {"parent": "minecraft:item/generated",
          "textures": {"layer0": f"{NS}:item/speaker_note"}})
    for index in range(8):
        rgb = [int(255 * max(0, math.sin((index / 8 + offset) * math.tau) * .65 + .35))
               for offset in (0, 1/3, 2/3)]
        color = (255 << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]
        write(f"assets/{NS}/items/speaker_note_{index}.json", {"model": {"type": "minecraft:model",
              "model": f"{NS}:item/speaker_note", "tints": [{"type": "minecraft:constant", "value": color - 2**32}]}})
    for item, model in (("copper_wire", "wire_15"), ("speaker", "speaker")):
        write(f"assets/{NS}/items/{item}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:block/{model}"}})
        pools = [{"rolls": 1,
                "entries": [{"type": "minecraft:item", "name": f"{NS}:{item}"}],
                "condition": {"type": "minecraft:survives_explosion"}}]
        if item == "copper_wire":
            pools.append({"rolls": 1, "condition": {"type": "minecraft:survives_explosion"},
                          "entries": [{"type": "minecraft:item", "name": "minecraft:resin_clump",
                                       "condition": {"type": "minecraft:match_block", "blocks": f"{NS}:copper_wire",
                                                     "state": {"insulated": "true"}}}]})
        write(f"data/{NS}/loot_table/blocks/{item}.json", {"type": "minecraft:block", "pools": pools})
    write(f"data/{NS}/recipe/copper_wire.json", {"type": "minecraft:crafting_shaped", "category": "redstone",
          "pattern": ["CCC"], "key": {"C": "minecraft:copper_ingot"}, "result": {"id": f"{NS}:copper_wire", "count": 12}})
    write(f"data/{NS}/recipe/speaker.json", {"type": "minecraft:crafting_shaped", "category": "redstone",
          "pattern": [" P ", "CNC", " C "], "key": {"P": "minecraft:paper", "C": "minecraft:copper_ingot", "N": "minecraft:note_block"},
          "result": {"id": f"{NS}:speaker", "count": 1}})
    # Inventory silhouette: two copper plates joined by an insulated through-wall stem (an H).
    plate_textures = {"copper": "minecraft:block/copper_block", "shell": "minecraft:block/resin_block",
                      "socket": "minecraft:block/black_concrete", "screw": "minecraft:block/iron_block",
                      "particle": "#copper"}
    write(f"assets/{NS}/models/item/wall_plate.json", {
        "parent": "minecraft:block/block", "textures": plate_textures,
        "elements": [cube([1, 2, 5], [4, 14, 11], "copper"),
                     cube([12, 2, 5], [15, 14, 11], "copper"),
                     cube([4, 6, 6], [12, 10, 10], "shell"),
                     cube([0.5, 6, 7], [1, 10, 9], "socket"),
                     cube([15, 6, 7], [15.5, 10, 9], "socket")],
        "display": {"gui": {"rotation": [20, -25, 0], "scale": [.85, .85, .85]},
                    "ground": {"translation": [0, 3, 0], "scale": [.5, .5, .5]}}})
    write(f"assets/{NS}/models/item/wall_plate_face.json", {
        "parent": "minecraft:block/block", "textures": plate_textures,
        # Invisible frames put z=8 on the wall. Embed the backing's rear face to
        # close the mounting gap without a coplanar face flickering against the wall.
        "elements": [cube([4, 3, 7.875], [12, 13, 9.25], "copper"),
                     cube([6, 6, 9.25], [10, 10, 10], "shell"),
                     cube([7, 7, 10], [9, 9, 10.25], "socket"),
                     cube([7.5, 4, 9.25], [8.5, 5, 9.4], "screw"),
                     cube([7.5, 11, 9.25], [8.5, 12, 9.4], "screw")],
        "display": {"fixed": {"rotation": [0, 180, 0], "scale": [2, 2, 2]}}})
    write(f"assets/{NS}/items/wall_plate.json", {"model": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [{"when": "fixed", "model": {"type": "minecraft:model", "model": f"{NS}:item/wall_plate_face"}}],
        "fallback": {"type": "minecraft:model", "model": f"{NS}:item/wall_plate"}}})
    write(f"data/{NS}/recipe/wall_plate.json", {"type": "minecraft:crafting_shaped", "category": "redstone",
          "pattern": ["C C", "CWC", "C C"], "key": {"C": "minecraft:copper_ingot", "W": f"{NS}:copper_wire"},
          "result": {"id": f"{NS}:wall_plate", "count": 2}})
    write(f"assets/{NS}/lang/en_us.json", {"block.copper_speakers.copper_wire": "Copper Wire",
          "block.copper_speakers.speaker": "Speaker",
          "item.copper_speakers.wall_plate": "Wall Plate",
          "entity.copper_speakers.wall_plate": "Wall Plate",
          "message.copper_speakers.drain_before_stripping": "Drain the water before stripping this wire."})
    for recipe, ingredient in (("copper_wire", "copper_ingot"), ("speaker", "note_block"), ("wall_plate", "copper_ingot")):
        write(f"data/{NS}/advancement/recipes/{recipe}.json", {
            "parent": "minecraft:recipes/root",
            "criteria": {"has_material": {"trigger": "minecraft:inventory_changed",
                "conditions": {"items": [{"items": "minecraft:" + ingredient}]}}},
            "requirements": [["has_material"]], "rewards": {"recipes": [f"{NS}:{recipe}"]}})
    print(f"Generated {len(sounds)} buffered aliases, models, recipes and loot tables")

if __name__ == "__main__":
    main()
