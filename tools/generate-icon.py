"""Compose the mod icon from exact game textures; no generated art or smoothing."""
import argparse
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    args = parser.parse_args()
    with ZipFile(args.minecraft_jar) as jar:
        def texture(name):
            return Image.open(BytesIO(jar.read(f"assets/minecraft/textures/{name}.png"))).convert("RGBA")

        side = texture("block/jukebox_side")
        top = texture("block/jukebox_top")
        disc = texture("item/music_disc_cat")
        copper = texture("block/copper_block")
    speaker = Image.open(ROOT / "src/main/resources/assets/copper_speakers/textures/block/speaker_front.png").convert("RGBA")
    icon = Image.new("RGBA", (128, 128))
    mask = Image.new("L", icon.size)
    pen = ImageDraw.Draw(mask)
    # Three distinct routes, as in the supplied sketch. Copper is the wire model's texture.
    for route in (((52, 72), (52, 83), (24, 83), (24, 99)),
                  ((64, 72), (64, 99)),
                  ((76, 72), (76, 83), (104, 83), (104, 99))):
        pen.line(route, fill=255, width=4)
    cable = Image.new("RGBA", icon.size)
    for y in range(0, 128, 16):
        for x in range(0, 128, 16):
            cable.paste(copper, (x, y))
    icon.paste(cable, (0, 0), mask)

    def paste(sprite, xy, size):
        icon.alpha_composite(sprite.resize(size, Image.Resampling.NEAREST), xy)

    # Disc is occluded by the front lip, leaving its upper half in the slot.
    paste(top.transpose(Image.Transpose.ROTATE_90), (40, 20), (48, 12))
    paste(disc, (48, 8), (32, 32))
    paste(side, (40, 28), (48, 48))
    for x in (8, 48, 88):
        paste(speaker, (x, 92), (32, 32))

    target = ROOT / "src/main/resources/assets/copper_speakers/icon.png"
    icon.resize((512, 512), Image.Resampling.NEAREST).save(target)
    print(target)


if __name__ == "__main__":
    main()
