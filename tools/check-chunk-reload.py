"""Reopen a disposable copy of the local saved-wire fixture with C2ME.

Fixture: run/server/world with a saved copper wire at (1,90,0) in a forced chunk.
Prepare it using the local server/RCON tools, save-all, then stop that server.
Never pass a live player world: only the supplied disposable copy is launched.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', required=True)
    parser.add_argument('--mod', type=Path, required=True)
    parser.add_argument('--c2me', type=Path, required=True)
    parser.add_argument('--dependencies', type=Path, required=True, help='Directory with Fabric API and bundled Polymer jars')
    parser.add_argument('--fixture', type=Path, default=ROOT / 'run/server/world')
    parser.add_argument('--java', type=Path, default=ROOT / '.runtime/zulu25.38.107-ca-jre25.0.5-win_x64/bin/java.exe')
    parser.add_argument('--jcmd', type=Path, default=Path('jcmd'), help='JDK jcmd executable; defaults to PATH')
    args = parser.parse_args()
    if not args.name.replace('-', '').replace('_', '').isalnum():
        parser.error('Use a new alphanumeric attempt name')
    work = ROOT / 'build/validation' / args.name
    work.mkdir(parents=True, exist_ok=False)
    shutil.copytree(args.fixture, work / 'world', ignore=shutil.ignore_patterns('session.lock'))
    inputs = [args.mod, args.c2me, next(args.dependencies.glob('fabric-api-*.jar')),
              next(args.dependencies.glob('polymer-bundled-*.jar'))]
    copied = []
    for source in inputs:
        dest = work / source.name
        shutil.copy2(source, dest)
        copied.append(dest)
    values = shlex.split((ROOT / 'build/runServer.args').read_text())
    cp = values.index('-classpath') + 1
    paths = [p for p in values[cp].split(os.pathsep)
             if not any(s in p.replace('\\', '/') for s in
                        ('/build/classes/java/main', '/build/resources/main', '/eu.pb4/', '/maven.modrinth/polymer/', '/net.fabricmc.fabric-api/'))]
    values[cp] = os.pathsep.join(paths + [p.as_posix() for p in copied])
    argfile = work / 'server.args'
    argfile.write_text('\n'.join('"' + v.replace('\\', '/') + '"' for v in values) + '\n')
    (work / 'eula.txt').write_text('eula=true\n')
    (work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=28911\nonline-mode=false\nlevel-name=world\nview-distance=6\nsimulation-distance=6\nmax-tick-time=-1\n')
    config = work / 'config/polymer/auto-host.json'
    config.parent.mkdir(parents=True)
    config.write_text('{"enabled":false}')
    report = {'status': 'incomplete', 'scope': 'Saved wire chunk reload with C2ME; functional test only',
              'inputs': [{'name': p.name, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in copied]}
    flags = ['-XX:TieredStopAtLevel=1', '-XX:ActiveProcessorCount=4', '-Xmx2G']
    report['diagnosticFlags'] = flags
    with (work / 'stdout.log').open('w') as out, (work / 'stderr.log').open('w') as err:
        process = subprocess.Popen([str(args.java), *flags, '@' + str(argfile)], cwd=work, stdin=subprocess.PIPE,
                                   stdout=out, stderr=err, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        report['pid'] = process.pid
        (work / 'launch.json').write_text(json.dumps(report, indent=2))
        print(f'Chunk reload PID {process.pid}; logs: {work}', flush=True)
        start = time.monotonic()
        sent = False
        checked = False
        try:
            while process.poll() is None and time.monotonic() - start < 90:
                log = (work / 'stdout.log').read_text(errors='replace')
                if 'Done (' in log and not sent:
                    process.stdin.write(b'execute if block 1 90 0 copper_speakers:copper_wire run say COPPER_RELOAD_OK\n')
                    process.stdin.flush()
                    sent = True
                if '[Not Secure] [Server] COPPER_RELOAD_OK' in log or '[Server] COPPER_RELOAD_OK' in log:
                    report['status'] = 'passed'
                    report['savedWireAccessible'] = True
                    process.stdin.write(b'stop\n'); process.stdin.flush()
                    process.wait(timeout=30)
                    break
                if not checked and time.monotonic() - start >= 40:
                    dump = subprocess.run([str(args.jcmd), str(process.pid), 'Thread.print', '-l'], capture_output=True, text=True, timeout=15)
                    (work / 'threads.txt').write_text(dump.stdout)
                    checked = True
                    if 'AudioNetworks.refreshChunk' in dump.stdout and 'instrumentAwaitChunk' in dump.stdout:
                        report['status'] = 'reproduced-chunk-load-deadlock'
                        break
                time.sleep(.5)
            if report['status'] == 'incomplete':
                report['status'] = 'failed-or-timed-out'
        finally:
            if process.poll() is None:
                # Only this helper's isolated server and copied world are terminated.
                process.kill(); process.wait(timeout=10)
            report['exitCode'] = process.returncode
            report['elapsedSeconds'] = round(time.monotonic() - start, 3)
            if report['status'] == 'passed' and process.returncode != 0:
                report['status'] = 'failed-shutdown'
            (work / 'launch.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
