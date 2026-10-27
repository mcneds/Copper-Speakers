"""Isolated dedicated server and two observing client JVMs, including a late login.

All networking is loopback. No client-side playback implementation is installed.
The development observer only reads native audio state and host monotonic time.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import shlex
import socket
import statistics
import subprocess
import sys
import time
import rcon

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--java', type=Path)
    parser.add_argument('--diagnostic', action='store_true', help='Functional validation only; no performance result')
    parser.add_argument('--pretouch-stacks', action='store_true')
    parser.add_argument('--sound', help='Catalogue sound event to exercise as a normal custom jukebox song')
    parser.add_argument('--extended', action='store_true', help='Also check independent groups, cancelled warmup and dimension return')
    parser.add_argument('--packaged', action='store_true', help='Run the dedicated server from the built release jar')
    parser.add_argument('--vanilla-smoke', action='store_true', help='After timing checks, run an untouched client and measure only its Windows audio-session peak')
    parser.add_argument('--client-heap', default='2G', choices=['768M', '1G', '2G'])
    parser.add_argument('--server-heap', default='2G', choices=['512M', '768M', '1G', '2G'])
    args = parser.parse_args()
    if not args.name.replace('-', '').isalnum():
        parser.error('Use letters, numbers and hyphens for the attempt name')
    for port in (28901, 28902):
        with socket.socket() as test:
            test.bind(('127.0.0.1', port))
    out = ROOT / 'build/validation' / args.name
    out.mkdir(parents=True, exist_ok=False)
    control = out / 'control'; control.mkdir()
    work = ROOT / 'run' / (args.name + '-server'); work.mkdir(exist_ok=False)
    (work / 'eula.txt').write_text('eula=true\n')
    (work / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=28901\nonline-mode=false\nenforce-secure-profile=false\n'
        'enable-rcon=true\nrcon.port=28902\nrcon.password=copper-local-development\nwhite-list=false\n'
        'gamemode=spectator\nforce-gamemode=true\nallow-flight=true\nspawn-protection=0\n'
        'view-distance=4\nsimulation-distance=4\nlevel-seed=1\n')
    shutil.copytree(args.catalog.resolve(), work / 'config/copper-speakers/tails')
    item = 'minecraft:music_disc_cat'
    if args.sound:
        track = json.loads((args.catalog / 'catalog.json').read_text())['tracks'][args.sound]
        pack = work / 'world/datapacks/custom-probe'
        song = pack / 'data/copper_speakers_probe/jukebox_song/custom.json'
        song.parent.mkdir(parents=True)
        (pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'min_format': 121, 'max_format': 121, 'description': 'Isolated custom-disc fixture'}}))
        song.write_text(json.dumps({'sound_event': {'sound_id': args.sound}, 'description': {'text': 'Custom audio probe'},
                                    'length_in_seconds': track['durationSeconds'], 'comparator_output': 1}))
        item += '[jukebox_playable="copper_speakers_probe:custom"]'
    config = work / 'config/polymer/auto-host.json'; config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(json.dumps({'enabled': True, 'required': True, 'type': 'polymer:automatic',
                                 'settings': {'forced_address': 'http://127.0.0.1:28901'}}))
    lock = ROOT / 'build/runtime-locks' / (str(os.getpid()) + '.json')
    lock.parent.mkdir(parents=True, exist_ok=True)
    lock.write_text(json.dumps({'pid': os.getpid(), 'mode': 'multiclient', 'attempt': args.name}))
    processes, files, checkpoints = [], [], []
    result = {'status': 'incomplete', 'scope': 'Two independent client JVMs on one host; normal JIT, 4 exposed processors per JVM; real loopback pack download and late login.', 'checkpoints': checkpoints}
    result['functionalOnly'] = args.diagnostic
    result['pretouchStacks'] = args.pretouch_stacks
    result['sound'] = args.sound or 'minecraft:music_disc.cat'
    result['extended'] = args.extended
    result['clientHeap'] = args.client_heap
    result['serverHeap'] = args.server_heap
    digest = hashlib.sha256()
    for source in sorted((ROOT / 'src/main').rglob('*')):
        if source.is_file():
            digest.update(source.relative_to(ROOT).as_posix().encode() + b'\0' + source.read_bytes())
    result['productionSourcesSha256'] = digest.hexdigest()
    result['catalogueSha256'] = hashlib.sha256((args.catalog / 'catalog.json').read_bytes()).hexdigest()
    server_args = ROOT / 'build/runServer.args'
    if args.packaged:
        jar = Path((ROOT / 'build/release-jar.txt').read_text().strip())
        values = shlex.split(server_args.read_text())
        cp = values.index('-classpath') + 1
        excluded = {(ROOT / p).resolve() for p in ('build/classes/java/main', 'build/resources/main')}
        values[cp] = os.pathsep.join([p for p in values[cp].split(os.pathsep) if Path(p).resolve() not in excluded] + [str(jar)])
        server_args = out / 'packaged-server.args'
        server_args.write_text('\n'.join('"' + v.replace('\\', '/') + '"' for v in values) + '\n')
        result['releaseJarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    if args.diagnostic:
        result['scope'] = 'Functional validation only: two client JVMs, tier-1 JIT, four exposed processors; not a performance benchmark.'

    def checkpoint(name):
        checkpoints.append({'name': name, 'hostNanos': time.perf_counter_ns()})
        print(name, flush=True)

    def launch(name, command, directory, env=None):
        output = (out / (name + '.log')).open('w', encoding='utf-8'); files.append(output)
        process = subprocess.Popen(command, cwd=directory, env=env, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        processes.append(process)
        return process

    def wait(predicate, seconds=150):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if predicate():
                return
            if any(p.poll() is not None and p.returncode != 0 for p in processes):
                raise RuntimeError('A test JVM or launcher failed; inspect the attempt logs')
            time.sleep(.25)
        raise TimeoutError('Timed out waiting for test stage')

    def server_ready():
        try:
            return 'players' in rcon.command('list')
        except (OSError, ConnectionError):
            return False

    try:
        java = args.java or ROOT / '.runtime/zulu25.38.107-ca-jre25.0.5-win_x64/bin/java.exe'
        server = launch('server', [str(java), '-Xmx' + args.server_heap, '-XX:ActiveProcessorCount=4', '@' + str(server_args)], work)
        wait(server_ready)
        for command in ['forceload add 0 0 16 16', 'fill 0 89 0 4 89 0 minecraft:stone',
                        'fill 1 89 1 3 89 1 minecraft:stone', 'fill 1 90 1 3 90 1 copper_speakers:copper_wire',
                        'setblock 0 90 0 minecraft:jukebox', 'setblock 1 90 0 copper_speakers:copper_wire',
                        'setblock 2 90 0 copper_speakers:speaker']:
            rcon.command(command)
        for role in ('a', 'b'):
            env = os.environ.copy()
            env.update(COPPER_OBSERVER_ROLE=role, COPPER_OBSERVER_CONTROL=str(control),
                       COPPER_OBSERVER_CATALOG=str(args.catalog.resolve() / 'catalog.json'))
            launch('launcher-' + role, [sys.executable, str(ROOT / 'tools/run.py'), 'observe',
                '--name', args.name + '-' + role, '--instance', args.name.replace('-', '') + role,
                '--heap', args.client_heap, *(['--diagnostic'] if args.diagnostic else ['--processors', '4']),
                *(['--pretouch-stacks'] if args.pretouch_stacks else []), '--java', str(java)], ROOT, env)
        wait(lambda: all((control / (r + '.ready')).exists() for r in ('a', 'b')))
        checkpoint('both-clients-ready')
        (control / 'a.connect').touch()
        wait(lambda: (control / 'a.connected').exists())
        name_a = (control / 'a.connected').read_text()
        rcon.command(f'tp {name_a} 0 91 -2')
        time.sleep(1)
        checkpoint('insert-disc')
        insertion = rcon.command('item replace block 0 90 0 container.0 with ' + item)
        print(insertion, flush=True)
        assert 'Replaced 1 slot(s)' in insertion, 'Fixture disc insertion failed: ' + insertion
        time.sleep(8)
        checkpoint('late-client-connect')
        (control / 'b.connect').touch()
        wait(lambda: (control / 'b.connected').exists())
        name_b = (control / 'b.connected').read_text()
        rcon.command(f'tp {name_b} 256 91 -2')
        checkpoint('late-client-outside-range')
        time.sleep(12)
        rcon.command(f'tp {name_b} 2 91 -2')
        checkpoint('late-client-enters-range')
        time.sleep(4)
        rcon.command('setblock 3 90 0 copper_speakers:copper_wire')
        rcon.command('setblock 4 90 0 copper_speakers:speaker')
        checkpoint('attach-speaker')
        time.sleep(3)
        rcon.command('setblock 2 90 0 minecraft:air')
        checkpoint('disconnect-speaker')
        time.sleep(2)
        if args.extended:
            for z in (8, 16):
                for command in [f'fill 0 89 {z} 2 89 {z} minecraft:stone', f'setblock 0 90 {z} minecraft:jukebox',
                                f'setblock 1 90 {z} copper_speakers:copper_wire', f'setblock 2 90 {z} copper_speakers:speaker']:
                    rcon.command(command)
                rcon.command(f'item replace block 0 90 {z} container.0 with ' + item)
            checkpoint('independent-groups-start')
            time.sleep(.5)
            rcon.command('setblock 0 90 16 minecraft:air')
            checkpoint('cancel-warmup')
            time.sleep(6)
            rcon.command('setblock 0 90 8 minecraft:air')
            checkpoint('independent-group-stop')
            time.sleep(2)
            checkpoint('initial-client-leaves-dimension')
            rcon.command(f'execute in minecraft:the_nether run tp {name_a} 0 100 0')
            time.sleep(3)
            rcon.command(f'execute in minecraft:overworld run tp {name_a} 0 91 -2')
            checkpoint('initial-client-returns')
            time.sleep(18)
        rcon.command('setblock 0 90 0 minecraft:air')
        checkpoint('stop-music')
        time.sleep(2)
        (control / 'stop').touch()
        for process in processes[1:]:
            process.wait(timeout=45)
            if process.returncode != 0:
                raise RuntimeError('Observer failed')
        reports = {r: json.loads((ROOT / 'build/validation' / (args.name + '-' + r) / 'observe.json').read_text()) for r in ('a', 'b')}
        assert all(r['status'] == 'complete' for r in reports.values())
        assert all(any(abs(s['x'] - 4.5) < .01 and abs(s['z'] - .5) < .01
                       for f in report['observations'] for s in f['sounds']) for report in reports.values()), 'Late speaker was not connected and playing'
        result['lateSpeakerPlayedForBothClients'] = True
        spreads = {}
        for role, report in reports.items():
            values = []
            for frame in report['observations']:
                starts = [s['nativeStartSeconds'] for s in frame['sounds'] if abs(s['z'] - .5) < .01]
                if len(starts) >= 2:
                    values.append((max(starts) - min(starts)) * 1000)
            assert values, 'No simultaneous master/speaker observations'
            spreads[role] = max(values)
            assert spreads[role] <= 25, f'{role}: speaker start spread {spreads[role]:.1f} ms'
        result['maxWithinClientSpeakerSpreadMs'] = spreads
        marks = {p['name']: p['hostNanos'] for p in checkpoints}
        root_samples = {r: [s for f in report['observations']
                           if r != 'a' or not args.extended or f['hostNanos'] < marks['initial-client-leaves-dimension']
                           for s in f['sounds'] if abs(s['x'] - .5) < .01 and abs(s['z'] - .5) < .01] for r, report in reports.items()}
        assert all(root_samples.values()), 'Both clients must receive audio'
        for samples in root_samples.values():
            assert len({(s['source'], s['buffer'], s['event']) for s in samples}) == 1, 'Existing source restarted'
            assert max(s['nativeStartSeconds'] for s in samples) - min(s['nativeStartSeconds'] for s in samples) < .002
        event_a, event_b = root_samples['a'][0]['event'], root_samples['b'][0]['event']
        assert event_a.endswith('/0') and not event_b.endswith('/0'), 'Late client must receive the current tail'
        epochs = {r: statistics.median(s['mediaEpochNanos'] for s in samples) for r, samples in root_samples.items()}
        error_ms = (epochs['b'] - epochs['a']) / 1e6
        result['alignmentErrorMs'] = error_ms
        alignment_failures = []
        if abs(error_ms) > 100:
            alignment_failures.append(f'Clients differ by {error_ms:.1f} ms')
        if args.extended:
            returned = [s for f in reports['a']['observations'] if f['hostNanos'] > marks['initial-client-returns'] + 12_000_000_000
                        for s in f['sounds'] if abs(s['x'] - .5) < .01 and abs(s['z'] - .5) < .01]
            assert returned and all(not s['event'].endswith('/0') for s in returned), 'Dimension return did not resume at current position'
            return_error = (statistics.median(s['mediaEpochNanos'] for s in returned) - epochs['b']) / 1e6
            result['dimensionReturnAlignmentErrorMs'] = return_error
            if abs(return_error) > 100:
                alignment_failures.append(f'Dimension return differs by {return_error:.1f} ms')
            for report in reports.values():
                assert any(abs(s['z'] - 8.5) < .01 for f in report['observations'] for s in f['sounds']), 'Independent group never played'
                assert not any(abs(s['z'] - 16.5) < .01 for f in report['observations'] for s in f['sounds']), 'Cancelled warmup played'
                assert not any(abs(s['z'] - 8.5) < .01 for f in report['observations']
                               if f['hostNanos'] > marks['independent-group-stop'] + 1_000_000_000 for s in f['sounds']), 'Independent group did not stop'
            result.update(dimensionReturnAlignmentErrorMs=return_error, independentGroupsIsolated=True, cancelledWarmupSilent=True)
        assert all(not f['sounds'] for report in reports.values() for f in report['observations'][-5:]), 'Stop left sounds playing'
        result.update(status='complete', existingSourcesContinuous=True,
                      initialEvent=event_a, lateEvent=event_b, bothClientsStopped=True)
        if not args.diagnostic:
            result['alignmentErrorMs'] = error_ms
        else:
            result['withinFunctionalAlignmentTolerance'] = True
        if args.vanilla_smoke:
            subprocess.run([sys.executable, str(ROOT / 'tools/check-vanilla-audio.py'), '--name', args.name + '-vanilla',
                            '--java', str(java), *(['--pretouch-stacks'] if args.pretouch_stacks else [])], check=True)
            result['unmodifiedClientAudioCheck'] = args.name + '-vanilla'
        assert not alignment_failures, '; '.join(alignment_failures)
        print(json.dumps(result), flush=True)
    except BaseException as error:
        result.update(status='failed', failure=repr(error))
        raise
    finally:
        (control / 'stop').touch()
        if processes and processes[0].poll() is None:
            try: rcon.command('stop')
            except Exception: pass
        for process in processes:
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                if os.name == 'nt':
                    # Only this still-running Popen process and its own children.
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                else:
                    process.terminate()
                process.wait(timeout=15)
        for file in files: file.close()
        lock.unlink(missing_ok=True)
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
