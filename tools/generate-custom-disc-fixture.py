"""Regenerate the committed synthetic test tone and buffered-alias fixtures (requires ffmpeg)."""
import json
from pathlib import Path
import subprocess

resources = Path(__file__).resolve().parents[1] / 'src/gametest/resources'
name = 'disc_a1111111111141118111111111111111'
# Use a generic namespace; the fixture does not depend on any external music service.
namespace = 'example_music'
asset_dir = resources / 'assets' / namespace
song_dir = resources / 'data' / namespace / 'jukebox_song'
(asset_dir / 'sounds/music').mkdir(parents=True, exist_ok=True)
(song_dir / 'buffered').mkdir(parents=True, exist_ok=True)
tone = asset_dir / 'sounds/music' / (name + '.ogg')
subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                'sine=frequency=440:sample_rate=22050:duration=60', '-af', 'volume=0.1',
                '-c:a', 'libvorbis', str(tone)], check=True)
original = {'sound_event': {'sound_id': namespace + ':' + name},
            'description': {'text': 'Synthetic fixture - Speaker test tone'},
            'length_in_seconds': 60.0, 'comparator_output': 1}
(song_dir / (name + '.json')).write_text(json.dumps(original, indent=2) + '\n')
alias = {**original, 'sound_event': {'sound_id': namespace + ':buffered.' + name}}
(song_dir / 'buffered' / (name + '.json')).write_text(json.dumps(alias, indent=2) + '\n')
sounds = {name: {'sounds': [{'name': namespace + ':music/' + name, 'stream': True}]},
          'buffered.' + name: {'sounds': [{'name': namespace + ':music/' + name,
                                         'type': 'file', 'stream': False, 'preload': False}]}}
(asset_dir / 'sounds.json').write_text(json.dumps(sounds, indent=2) + '\n')

song_dir = resources / 'data/example_music/jukebox_song'
original = json.loads((song_dir / (name + '.json')).read_text())
for suffix in ('missing', 'stale', 'wrong_alias', 'replaced'):
    sound = 'example_music:disc_' + suffix
    song = {**original, 'sound_event': {'sound_id': sound if suffix != 'replaced' else 'minecraft:music_disc.cat'}}
    (song_dir / ('disc_' + suffix + '.json')).write_text(json.dumps(song, indent=2) + '\n')
    if suffix != 'missing':
        alias = {**song, 'sound_event': {'sound_id': 'example_music:buffered.disc_' + suffix}}
        if suffix == 'stale': alias['length_in_seconds'] = 61.0
        if suffix == 'wrong_alias': alias['sound_event']['sound_id'] = 'minecraft:music_disc.cat'
        (song_dir / 'buffered' / ('disc_' + suffix + '.json')).write_text(json.dumps(alias, indent=2) + '\n')
unknown = resources / 'data/copper_speakers_tests/jukebox_song/unknown.json'
unknown.parent.mkdir(parents=True, exist_ok=True)
unknown.write_text(json.dumps(original, indent=2) + '\n')
print('Generated test-only custom song, aliases and synthetic audio.')
