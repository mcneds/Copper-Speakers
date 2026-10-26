"""Run the current mod's buffered audio benchmark; retain every launch attempt.

Run `gradlew build prepareRuns` first, with development runtimes stopped.
No diagnostic JVM flags are used. Failed attempts are audited, never measurements.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True, help='New suite name')
    parser.add_argument('--rounds', type=int, default=10)
    parser.add_argument('--sources', type=int, nargs='+', default=[3, 8, 16])
    parser.add_argument('--sample-ticks', type=int, default=40)
    parser.add_argument('--attempts', type=int, default=2, help='Maximum attempts per configuration, all retained')
    parser.add_argument('--java', type=Path)
    args = parser.parse_args()
    if not args.name.replace('-', '').replace('_', '').isalnum():
        parser.error('Use letters, digits, hyphens and underscores for the suite name')
    if not 1 <= args.rounds <= 100 or not 1 <= args.sample_ticks <= 400 or not 1 <= args.attempts <= 5 or any(not 2 <= n <= 32 for n in args.sources):
        parser.error('Rounds: 1..100; sample ticks: 1..400; attempts: 1..5; sources: 2..32')
    destination = ROOT / 'build/validation' / args.name
    destination.mkdir(parents=True, exist_ok=False)
    jar = Path((ROOT / 'build/release-jar.txt').read_text().strip())
    digest = hashlib.sha256()
    for path in sorted((ROOT / 'src/main').rglob('*')):
        if path.is_file():
            digest.update(path.relative_to(ROOT).as_posix().encode() + b'\0')
            digest.update(path.read_bytes())
    report = {'status': 'incomplete', 'started': time.time(), 'rounds': args.rounds,
              'networkSources': args.sources, 'reservedSources': 4, 'sampleTicks': args.sample_ticks,
              'releaseJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'mainSourceSha256': digest.hexdigest(),
              'scope': 'Development classes from the current build, production packets and vanilla audio engine; local dedicated test server and instrumented client in one JVM.',
              'attempts': []}

    def save():
        (destination / 'suite.json').write_text(json.dumps(report, indent=2) + '\n')

    save()
    passed = 0
    for mode in ('smoke', 'custom'):
        for count in args.sources:
            for attempt in range(1, args.attempts + 1):
                name = f'{args.name}-{mode}-{count}-{attempt}'
                env = {**os.environ, 'COPPER_AUDIO_SOURCES': str(count),
                       'COPPER_AUDIO_ROUNDS': str(args.rounds),
                       'COPPER_AUDIO_SAMPLE_TICKS': str(args.sample_ticks)}
                command = [sys.executable, str(ROOT / 'tools/run.py'), mode, '--name', name, '--heap', '2G']
                if args.java:
                    command += ['--java', str(args.java)]
                print(f'Running {name}', flush=True)
                result = subprocess.run(command, cwd=ROOT, env=env)
                launch_path = ROOT / 'build/validation' / name / 'launch.json'
                launch = json.loads(launch_path.read_text()) if launch_path.exists() else {}
                ok = result.returncode == 0 and launch.get('status') == 'complete' and not launch.get('diagnosticFlags')
                report['attempts'].append({'name': name, 'mode': mode, 'networkSources': count,
                                           'included': ok, 'exitCode': launch.get('exitCode', result.returncode),
                                           'status': launch.get('status', 'no-launch-record')})
                save()
                if ok:
                    passed += 1
                    break
    report.update(status='complete' if passed == 2 * len(args.sources) else 'partial', finished=time.time())
    save()
    print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'complete' else 1


if __name__ == '__main__':
    sys.exit(main())
