"""Generate private development audio tails from locally cached vanilla Cat audio.

No Minecraft audio is committed or shipped in the mod. Requires FFmpeg/libvorbis.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, default=Path.home() / '.gradle/caches/fabric-loom/assets')
    parser.add_argument('--ffmpeg', default='ffmpeg')
    args = parser.parse_args()
    index = json.loads((args.assets / 'indexes/26.3-34.json').read_text())['objects']
    metadata = index['minecraft/sounds/records/cat.ogg']
    source = args.assets / 'objects' / metadata['hash'][:2] / metadata['hash']
    assert hashlib.sha1(source.read_bytes()).hexdigest() == metadata['hash']
    pcm = subprocess.check_output([args.ffmpeg, '-v', 'error', '-i', str(source),
                                   '-ac', '1', '-ar', '44100', '-f', 's16le', '-'])
    output = ROOT / 'build/generated/tail-probe/assets/tail_probe'
    (output / 'sounds').mkdir(parents=True, exist_ok=True)
    events, rows = {}, []
    for seconds in (6, 12):
        name = f'cat_{seconds:02d}'
        target = output / 'sounds' / (name + '.ogg')
        tail = pcm[seconds * 44100 * 2:]
        subprocess.run([args.ffmpeg, '-nostdin', '-v', 'error', '-y', '-f', 's16le',
                        '-ar', '44100', '-ac', '1', '-i', 'pipe:0', '-c:a', 'libvorbis',
                        '-q:a', '4', str(target)], input=tail, check=True)
        decoded = subprocess.check_output([args.ffmpeg, '-v', 'error', '-i', str(target), '-f', 's16le', '-'])
        if len(decoded) != len(tail):
            raise RuntimeError('Tail duration changed during encoding: ' + name)
        events[name] = {'sounds': [{'name': 'tail_probe:' + name, 'stream': False, 'preload': False}]}
        rows.append({'offsetSeconds': seconds, 'frames': len(tail)//2, 'bytes': target.stat().st_size})
    (output / 'sounds.json').write_text(json.dumps(events, indent=2) + '\n')
    result = {'sourceSha1': metadata['hash'], 'fullFrames': len(pcm)//2, 'sampleRate': 44100,
              'scope': 'Private generated test audio; not a production resource pack.', 'tails': rows}
    (ROOT / 'build/generated/tail-probe/fixture.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
