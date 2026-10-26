"""Generate synthetic two-second clips for the late-listener feasibility test.

These are test assets, not music shipped by the mod. Requires FFmpeg with libvorbis.
"""
import argparse
import json
import math
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    args = parser.parse_args()
    directory = ROOT / 'build/generated/section-probe/assets/section_probe'
    (directory / 'sounds').mkdir(parents=True, exist_ok=True)
    events = {}
    for number in range(12):
        pcm = b''.join(struct.pack('<h', round(1800 * math.sin(2 * math.pi *
                           (440.25 + number * .75) * sample / 44100))) for sample in range(88200))
        name = f'part_{number:02d}'
        target = directory / 'sounds' / (name + '.ogg')
        subprocess.run([args.ffmpeg, '-nostdin', '-v', 'error', '-y', '-f', 's16le',
                        '-ar', '44100', '-ac', '1', '-i', 'pipe:0', '-c:a', 'libvorbis',
                        '-q:a', '4', str(target)], input=pcm, check=True)
        decoded = subprocess.check_output([args.ffmpeg, '-v', 'error', '-i', str(target), '-f', 's16le', '-'])
        if len(decoded) != 88200 * 2:
            raise RuntimeError(f'{target.name} did not decode to exactly two seconds')
        events[name] = {'sounds': [{'name': 'section_probe:' + name, 'stream': False, 'preload': False}]}
    (directory / 'sounds.json').write_text(json.dumps(events, indent=2) + '\n')
    print('Generated 12 synthetic clips; each decodes to 88,200 mono samples at 44.1 kHz.')


if __name__ == '__main__':
    main()
