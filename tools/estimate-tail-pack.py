"""Estimate prepared-tail storage from local vanilla asset sizes and durations.

This is a constant-average-bitrate projection, not a generated resource pack.
No music is copied. Requires ffprobe and the pinned Minecraft asset cache.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, default=Path.home() / '.gradle/caches/fabric-loom/assets')
    parser.add_argument('--ffprobe', default='ffprobe')
    parser.add_argument('--output', type=Path, default=Path('build/sync-feasibility/tail-size-projection.json'))
    args = parser.parse_args()
    index = json.loads((args.assets / 'indexes/26.3-34.json').read_text())['objects']
    rows = []
    for name, meta in sorted(index.items()):
        if not (name.startswith('minecraft/sounds/records/') and name.endswith('.ogg')):
            continue
        source = args.assets / 'objects' / meta['hash'][:2] / meta['hash']
        if hashlib.sha1(source.read_bytes()).hexdigest() != meta['hash']:
            raise ValueError('Asset hash mismatch: ' + name)
        seconds = float(subprocess.check_output([
            args.ffprobe, '-v', 'error', '-show_entries', 'format=duration',
            '-of', 'csv=p=0', str(source)], text=True).strip())
        projected = {}
        for step in (2, 5, 10):
            count = math.ceil(seconds / step) - 1
            # Sum the remaining duration at step, 2*step, ..., count*step.
            projected[str(step)] = round(meta['size'] * (
                count - step * count * (count + 1) / (2 * seconds)))
        rows.append({'asset': name, 'sha1': meta['hash'], 'sourceBytes': meta['size'],
                     'durationSeconds': seconds, 'projectedTailBytesByIntervalSeconds': projected})
    result = {
        'scope': 'Projection only, not a generated pack: each tail is assumed to have the source average compressed bitrate. Excludes originals, zip overhead, re-encoding and custom songs.',
        'songs': rows,
        'sourceBytes': sum(r['sourceBytes'] for r in rows),
        'projectedTailBytesByIntervalSeconds': {
            str(s): sum(r['projectedTailBytesByIntervalSeconds'][str(s)] for r in rows)
            for s in (2, 5, 10)}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'songs'}, indent=2))


if __name__ == '__main__':
    main()
