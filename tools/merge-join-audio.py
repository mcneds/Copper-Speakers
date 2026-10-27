"""Combine prepared format-2 catalogues without regenerating suffixes.

Rebuilds a compact shared base pack and copies only referenced joining packs.
The output must be a new directory. Inputs remain unchanged.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogues', nargs='+', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--exclude-sound', action='append', default=[])
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists; choose a new directory')
    result = {'format': 2, 'spacingSeconds': 0, 'packs': [], 'tracks': {}}
    tails, originals = {}, {}
    for root in args.catalogues:
        source = json.loads((root / 'catalog.json').read_text())
        if source['format'] != 2:
            parser.error('Only format-2 catalogues can be combined')
        result['spacingSeconds'] = max(result['spacingSeconds'], source['spacingSeconds'])
        packs = {p['file']: p for p in source['packs']}
        for pack in packs.values():
            file = (root / pack['file']).resolve()
            if not file.is_relative_to(root.resolve()) or hashlib.sha1(file.read_bytes()).hexdigest() != pack['sha1']:
                raise ValueError('Invalid input pack: ' + str(file))
        base_locations = {}
        for pack in packs.values():
            if not pack['base']:
                continue
            with zipfile.ZipFile(root / pack['file']) as archive:
                for name in json.loads(archive.read('assets/copper_speakers/sounds.json')):
                    base_locations['copper_speakers:' + name] = root / pack['file']
        for sound, track in source['tracks'].items():
            if sound in args.exclude_sound:
                continue
            track['packs'] = [packs[name]['sha1'] + '.zip' for name in track['packs']]
            if sound in result['tracks'] and result['tracks'][sound] != track:
                raise ValueError('Conflicting recording: ' + sound)
            result['tracks'][sound] = track
            event = track['entries'][0]['event']
            originals[event] = base_locations[event]
            for name, pack in packs.items():
                if pack['sha1'] + '.zip' in track['packs']:
                    tails[pack['sha1']] = root / name
    if not result['tracks']:
        parser.error('No tracks selected')
    args.output.mkdir(parents=True)
    for sha1, file in tails.items():
        dest = args.output / (sha1 + '.zip')
        shutil.copyfile(file, dest)
        result['packs'].append({'file': dest.name, 'sha1': sha1, 'bytes': dest.stat().st_size, 'base': False})
    archive, sounds, used, index = None, {}, 0, 0

    def close_base():
        nonlocal archive
        if archive is None:
            return
        archive.writestr(zipfile.ZipInfo('assets/copper_speakers/sounds.json'), json.dumps(sounds, sort_keys=True))
        archive.writestr(zipfile.ZipInfo('pack.mcmeta'), json.dumps({'pack': {
            'description': 'Copper Speakers prepared music', 'min_format': [97, 1], 'max_format': 97}}))
        path = Path(archive.filename)
        archive.close()
        result['packs'].append({'file': path.name, 'sha1': hashlib.sha1(path.read_bytes()).hexdigest(),
                                'bytes': path.stat().st_size, 'base': True})
        archive = None

    try:
        for event, path in sorted(originals.items()):
            name = event.split(':', 1)[1]
            file = 'assets/copper_speakers/sounds/' + name + '.ogg'
            with zipfile.ZipFile(path) as source:
                data = source.read(file)
            if archive is None or used + len(data) > 128 * 1024 * 1024:
                close_base()
                archive = zipfile.ZipFile(args.output / f'base-{index:03d}.zip', 'w', zipfile.ZIP_STORED)
                index += 1
                sounds, used = {}, 0
            archive.writestr(zipfile.ZipInfo(file), data)
            sounds[name] = {'sounds': [{'name': event, 'stream': False, 'preload': False}]}
            used += len(data)
        close_base()
    finally:
        if archive is not None:
            archive.close()
    (args.output / 'catalog.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'Combined {len(result["tracks"])} tracks in {len(result["packs"])} packs')


if __name__ == '__main__':
    main()
