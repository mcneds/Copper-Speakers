"""Build server-only late-join audio packs from local Ogg/Vorbis assets.

Uses lossless packet copying, checks the decoded waveform, and records the actual
sample offset (Vorbis packet boundaries need not coincide with requested seconds).
Generated music belongs in the server's config/copper-speakers/tails directory,
not in the mod or Git. Requires FFmpeg, ffprobe and NumPy.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import struct
import tempfile
import urllib.request
import zipfile
import numpy as np
from functools import lru_cache


def download(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def vanilla_assets(cache):
    """Fetch only the official 26.3 index and record assets; verify Mojang's SHA-1s."""
    manifest = json.loads(download('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
    version = next(v for v in manifest['versions'] if v['id'] == '26.3')
    metadata = json.loads(download(version['url']))
    descriptor = metadata['assetIndex']
    raw = download(descriptor['url'])
    if hashlib.sha1(raw).hexdigest() != descriptor['sha1']:
        raise ValueError('Official asset index hash mismatch')
    index = cache / 'indexes/26.3-34.json'
    index.parent.mkdir(parents=True, exist_ok=True)
    index.write_bytes(raw)
    for name, item in json.loads(raw)['objects'].items():
        if not (name.startswith('minecraft/sounds/records/') and name.endswith('.ogg')):
            continue
        sha1 = item['hash']
        file = cache / 'objects' / sha1[:2] / sha1
        if file.exists() and hashlib.sha1(file.read_bytes()).hexdigest() == sha1:
            continue
        data = download(f'https://resources.download.minecraft.net/{sha1[:2]}/{sha1}')
        if hashlib.sha1(data).hexdigest() != sha1:
            raise ValueError('Official audio asset hash mismatch: ' + name)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data)
    return cache


def pcm(path):
    info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'a:0',
        '-show_entries', 'stream=codec_name,sample_rate,channels', '-of', 'json', str(path)]))['streams'][0]
    if info['codec_name'] != 'vorbis' or info['channels'] != 1:
        raise ValueError(f'{path}: positional music must be mono Vorbis; prepare a mono source first')
    data = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'f32le', '-'])
    return np.frombuffer(data, dtype='<f4'), int(info['sample_rate'])


@lru_cache(maxsize=64)
def vorbis_modes(setup):
    """Read the fixed-size mode records before the final framing bit.

    Vorbis I, section 4.2.4: each mode is 1+16+16+8 bits. Like FFmpeg's
    Vorbis parser, choose the longest valid suffix matching its mode count.
    https://xiph.org/vorbis/doc/Vorbis_I_spec.html
    """
    if not setup.startswith(b'\x05vorbis'):
        raise ValueError('Missing Vorbis setup header')
    value = int.from_bytes(setup, 'little')
    framing = value.bit_length() - 1
    modes = None
    for count in range(1, 65):
        start = framing - 41 * count - 6
        if start < 56 or ((value >> start) & 63) != count - 1:
            continue
        flags = []
        for index in range(count):
            record = (value >> (start + 6 + index * 41)) & ((1 << 41) - 1)
            if ((record >> 1) & 0xffffffff) or (record >> 33) > 63:
                break
            flags.append(record & 1)
        if len(flags) == count:
            modes = flags
    if modes is None:
        raise ValueError('Invalid Vorbis mode layout')
    return modes


def vorbis_lead_in(stream):
    packets, pending = [], bytearray()
    while len(packets) < 4:
        header = stream.read(27)
        if len(header) != 27 or header[:4] != b'OggS':
            raise ValueError('Missing Vorbis audio packet')
        for length in stream.read(header[26]):
            fragment = stream.read(length)
            if len(fragment) != length:
                raise ValueError('Truncated Ogg page')
            pending.extend(fragment)
            if length < 255:
                packets.append(bytes(pending))
                pending.clear()
                if len(packets) == 4:
                    break
    identification, _, setup, first = packets
    if not identification.startswith(b'\x01vorbis') or first[0] & 1:
        raise ValueError('Invalid Vorbis headers or first audio packet')
    short = 1 << (identification[28] & 15)
    long = 1 << (identification[28] >> 4)
    modes = vorbis_modes(setup)
    mode = (first[0] >> 1) & ((1 << (len(modes) - 1).bit_length()) - 1)
    if mode >= len(modes):
        raise ValueError('Invalid Vorbis audio mode')
    return ((long if modes[mode] else short) - short) // 4


def minecraft_lead_in(path):
    """JOrbis emits (first block - short block)/4 startup frames; libvorbis discards them.

    A packet's duration describes two overlapping blocks, so doubling FFprobe's
    duration is incorrect at short/long block transitions. Read its actual mode.
    """
    with path.open('rb') as stream:
        return vorbis_lead_in(stream)


def normalize_short_end(path, frames):
    """For a single audio page, JOrbis uses EOS granule directly as its output cap."""
    data = bytearray(path.read_bytes())
    cursor, packets, audio_pages, final = 0, 0, 0, None
    while cursor < len(data):
        if data[cursor:cursor + 4] != b'OggS':
            raise ValueError('Invalid Ogg page')
        segments = data[cursor + 26]
        lacing = data[cursor + 27:cursor + 27 + segments]
        size = 27 + segments + sum(lacing)
        packets += sum(v < 255 for v in lacing)
        if packets > 3:
            audio_pages += 1
        if data[cursor + 5] & 4:
            final = cursor, size
        cursor += size
    if audio_pages != 1 or final is None:
        return
    cursor, size = final
    struct.pack_into('<Q', data, cursor + 6, frames)
    data[cursor + 22:cursor + 26] = b'\0' * 4
    crc = 0
    for byte in data[cursor:cursor + size]:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04c11db7 if crc & 0x80000000 else 0)) & 0xffffffff
    struct.pack_into('<I', data, cursor + 22, crc)
    path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New output directory; never overwrites a catalogue')
    parser.add_argument('--assets', type=Path, help='Minecraft asset cache containing indexes/26.3-34.json and objects/')
    parser.add_argument('--vanilla', nargs='*', help='Vanilla names such as cat; no names means all built-in discs')
    parser.add_argument('--song', action='append', default=[], help='Registered sound-event=local-mono.ogg (repeatable)')
    parser.add_argument('--spacing', type=float, default=5, help='Target seconds between entry points (default 5)')
    parser.add_argument('--pack-mib', type=int, default=128, help='Maximum audio bytes per pack (default 128 MiB)')
    args = parser.parse_args()
    if not 1 <= args.spacing <= 30 or not 1 <= args.pack_mib <= 190:
        parser.error('Spacing must be 1..30 seconds and packs 1..190 MiB')
    if args.output.exists():
        parser.error('Output already exists; use a new directory and replace it only with the server stopped')
    sources = {}
    if args.vanilla is not None:
        if args.assets is None:
            args.assets = vanilla_assets(args.output.parent / '.audio-cache/assets')
        index = json.loads((args.assets / 'indexes/26.3-34.json').read_text())['objects']
        for name, entry in sorted(index.items()):
            if not (name.startswith('minecraft/sounds/records/') and name.endswith('.ogg')):
                continue
            disc = Path(name).stem
            if args.vanilla and disc not in args.vanilla:
                continue
            source = args.assets / 'objects' / entry['hash'][:2] / entry['hash']
            if hashlib.sha1(source.read_bytes()).hexdigest() != entry['hash']:
                raise ValueError('Source asset hash mismatch: ' + name)
            sources['minecraft:music_disc.' + disc] = source
        if args.vanilla and len(sources) != len(set(args.vanilla)):
            parser.error('One or more requested vanilla discs were not found')
    for value in args.song:
        event, path = value.split('=', 1)
        if not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+', event):
            parser.error('Invalid sound-event identifier: ' + event)
        sources[event] = Path(path)
    if not sources:
        parser.error('Choose --vanilla and/or --song')
    args.output.mkdir(parents=True)
    result = {'format': 2, 'spacingSeconds': args.spacing, 'packs': [], 'tracks': {}}
    base_audio = []
    base = False
    pack = None
    events = {}
    used = 0
    limit = args.pack_mib * 1024 * 1024

    def close_pack():
        nonlocal pack
        if pack is None:
            return
        pack.writestr(zipfile.ZipInfo('assets/copper_speakers/sounds.json'), json.dumps(events, sort_keys=True))
        pack.writestr(zipfile.ZipInfo('pack.mcmeta'), json.dumps({'pack': {
            'description': 'Copper Speakers prepared music', 'min_format': [97, 1], 'max_format': 97}}))
        filename = Path(pack.filename)
        pack.close()
        result['packs'].append({'file': filename.name, 'sha1': hashlib.sha1(filename.read_bytes()).hexdigest(),
                                'bytes': filename.stat().st_size, 'base': base})
        pack = None

    def add_audio(event, file):
        nonlocal pack, used, events
        size = file.stat().st_size
        if size > limit:
            raise ValueError('One audio file exceeds the pack limit: ' + str(file))
        if pack is None or used + size > limit:
            close_pack()
            pack = zipfile.ZipFile(args.output / f'audio-{len(result["packs"]):03d}.zip', 'w', zipfile.ZIP_STORED)
            events, used = {}, 0
        name = event.split(':', 1)[1]
        pack.writestr(zipfile.ZipInfo('assets/copper_speakers/sounds/' + name + '.ogg'), file.read_bytes())
        events[name] = {'sounds': [{'name': event, 'stream': False, 'preload': False}]}
        used += size

    try:
        prepared = {}
        with tempfile.TemporaryDirectory(prefix='copper-tails-') as temp:
            for event, source in sources.items():
                original, rate = pcm(source)
                original_lead = minecraft_lead_in(source)
                source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
                if source_hash in prepared:
                    result['tracks'][event] = prepared[source_hash]
                    continue
                duration = (len(original) + original_lead) / rate
                first_pack = len(result['packs'])
                track = {'sourceSha256': source_hash, 'sampleRate': rate, 'frames': len(original) + original_lead,
                         'durationSeconds': duration, 'entries': []}
                previous = -1
                for requested in np.arange(0, duration - .1, args.spacing):
                    if requested == 0:
                        file, offset, lead = source, 0, original_lead
                    else:
                        file = Path(temp) / 'tail.ogg'
                        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(source),
                            '-ss', str(float(requested)), '-c:a', 'copy', '-fflags', '+bitexact', '-avoid_negative_ts', 'make_zero', str(file)], check=True)
                        decoded, tail_rate = pcm(file)
                        offset = len(original) - len(decoded)
                        if tail_rate != rate or not 0 < offset < len(original) or not np.array_equal(original[offset:], decoded):
                            raise ValueError(f'{event} at {requested}s: copied tail differs from original decoded audio')
                        lead = minecraft_lead_in(file)
                        offset += original_lead - lead
                        normalize_short_end(file, len(original) + original_lead - offset)
                    if offset <= previous:
                        continue
                    tail_event = f'copper_speakers:tail/{source_hash[:24]}/{offset}'
                    if offset == 0:
                        base_audio.append((tail_event, source))
                    else:
                        add_audio(tail_event, file)
                    track['entries'].append({'offsetFrames': offset, 'offsetSeconds': offset / rate,
                                            'decoderLeadInFrames': lead, 'event': tail_event})
                    previous = offset
                close_pack()
                track['packs'] = [p['file'] for p in result['packs'][first_pack:]]
                result['tracks'][event] = track
                prepared[source_hash] = track
                print(f'{event}: {len(track["entries"])} verified entry points', flush=True)
        base = True
        for event, source in base_audio:
            add_audio(event, source)
        close_pack()
        (args.output / 'catalog.json').write_text(json.dumps(result, indent=2) + '\n')
        print(f'Complete: {len(sources)} tracks, {len(result["packs"])} packs, '
              f'{sum(p["bytes"] for p in result["packs"]) / 1024**2:.1f} MiB', flush=True)
    finally:
        if pack is not None:
            pack.close()


if __name__ == '__main__':
    main()
