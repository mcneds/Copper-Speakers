"""Audit release separation and buffered pack metadata, without launching Minecraft."""
import argparse
import json
import struct
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("mod", type=Path)
parser.add_argument("pack", type=Path)
parser.add_argument("--allow-source-audio", action="store_true", help="Permit unrelated audio in a server-specific input pack; the mod must still contain none")
args = parser.parse_args()
with zipfile.ZipFile(args.mod) as mod, zipfile.ZipFile(args.pack) as pack:
    entries = mod.namelist()
    assert not any("/test/" in n or "AudioSmoke" in n or "CopperGameTests" in n for n in entries)
    assert not any(n.endswith((".ogg", ".mp3", ".wav")) for n in entries)
    source_audio = sum(n.endswith((".ogg", ".mp3", ".wav")) for n in pack.namelist())
    assert args.allow_source_audio or source_audio == 0
    metadata = json.loads(mod.read("fabric.mod.json"))
    assert metadata["icon"] == "assets/copper_speakers/icon.png"
    assert struct.unpack(">II", mod.read(metadata["icon"])[16:24]) == (512, 512)
    assert "sodium" not in metadata["depends"] and "lithium" not in metadata["depends"]
    sounds = json.loads(pack.read("assets/copper_speakers/sounds.json"))
    assert len(sounds) == 22
    for alias, event in sounds.items():
        assert len(event["sounds"]) == 1
        audio = event["sounds"][0]
        assert audio["stream"] is False and audio["preload"] is False
        name = alias.removeprefix("buffered.")
        assert audio["name"] == "minecraft:records/" + name
        song = json.loads(mod.read(f"data/copper_speakers/jukebox_song/buffered/{name}.json"))
        assert song["sound_event"]["sound_id"] == "copper_speakers:" + alias
    for item in ("copper_wire", "speaker", "wall_plate"):
        assert f"assets/copper_speakers/items/{item}.json" in pack.namelist()
        assert f"data/copper_speakers/recipe/{item}.json" in entries
    front = "assets/copper_speakers/textures/block/speaker_front.png"
    assert pack.read(front) == mod.read(front)
    assert struct.unpack(">II", pack.read(front)[16:24]) == (16, 16)
    model = json.loads(pack.read("assets/copper_speakers/models/block/speaker.json"))
    assert model["textures"]["front"] == "copper_speakers:block/speaker_front"
    assert model["elements"][0]["faces"]["north"]["texture"] == "#front"
    for index in range(8):
        note = json.loads(pack.read(f"assets/copper_speakers/items/speaker_note_{index}.json"))
        assert note["model"]["model"] == "copper_speakers:item/speaker_note"
    atlas_data = json.loads(pack.read("assets/minecraft/atlases/items.json"))
    assert any(source.get("resource") == "minecraft:particle/note"
               and source.get("sprite") == "copper_speakers:item/speaker_note" for source in atlas_data["sources"])
    wire_loot = json.loads(mod.read("data/copper_speakers/loot_table/blocks/copper_wire.json"))
    assert wire_loot["pools"][1]["entries"][0]["condition"] == {
        "type": "minecraft:match_block", "blocks": "copper_speakers:copper_wire", "state": {"insulated": "true"}}
    print(json.dumps({"status":"passed", "aliases":len(sounds), "modBytes":args.mod.stat().st_size,
                      "packBytes":args.pack.stat().st_size, "privateAudioFiles":0, "sourceAudioFiles":source_audio,
                      "productionTestClasses":0}, indent=2))
