"""Verify generated audio against Minecraft's actual decoder, using prepareRuns' classpath."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('catalog', type=Path)
args = parser.parse_args()
values = shlex.split((ROOT / 'build/vanilla.args').read_text())
classpath = values[values.index('-classpath') + 1]
classes = ROOT / 'build/join-audio-verifier'
classes.mkdir(parents=True, exist_ok=True)
subprocess.run(['javac', '-classpath', classpath, '-d', str(classes), str(ROOT / 'tools/VerifyJoinAudio.java')], check=True)
result = subprocess.run(['java', '-Xmx1G', '-classpath', str(classes) + os.pathsep + classpath,
                         'VerifyJoinAudio', str(args.catalog.resolve())])
raise SystemExit(result.returncode)
