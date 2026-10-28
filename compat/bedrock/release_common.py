"""Shared reproducible build helpers. No installed server or user configuration is read."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def lock():
    return json.loads((HERE / 'dependencies.lock.json').read_text(encoding='utf-8'))


def pack_version(version):
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?', version):
        raise ValueError('Use a semantic release version such as 0.3.0')
    return [int(part) for part in re.split(r'[-+]', version)[0].split('.')]


def zip_bytes(archive, name, data):
    entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.external_attr = 0o100644 << 16
    archive.writestr(entry, data)


def zip_tree(directory, destination):
    with zipfile.ZipFile(destination, 'w') as archive:
        for path in sorted(Path(directory).rglob('*')):
            if path.is_file(): zip_bytes(archive, path.relative_to(directory).as_posix(), path.read_bytes())


def fetch(spec, cache):
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / spec['file']
    if destination.exists() and sha256(destination) == spec['sha256']:
        return destination
    temporary = destination.with_suffix(destination.suffix + '.part')
    request = urllib.request.Request(spec['url'], headers={'User-Agent': 'Copper-Speakers-release-builder'})
    try:
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
            while chunk := response.read(1024 * 1024): output.write(chunk)
        if sha256(temporary) != spec['sha256']: raise ValueError('Pinned dependency hash mismatch: ' + spec['file'])
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def compile_java(javac, classpath, sources, output):
    output.mkdir(parents=True, exist_ok=True)
    args = ['--release', '21', '-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str, classpath)),
            '-d', str(output), *map(str, sorted(sources))]
    argfile = output.parent / (output.name + '-javac.args')
    argfile.write_text('\n'.join('"' + arg.replace('\\', '/').replace('"', '\\"') + '"' for arg in args), encoding='utf-8')
    subprocess.run([str(javac), '@' + str(argfile)], check=True)


def json_file(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')
