#!/usr/bin/env python3
"""Build a complete, versioned Fabric/Bedrock release without an installed server."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMPAT = ROOT / 'compat/bedrock'
sys.path.insert(0, str(COMPAT))
from release_common import fetch, json_file, lock, pack_version, sha256, zip_tree
from convert_visuals import convert
from build_display import build as build_display

spec = importlib.util.spec_from_file_location('bedrock_audio_build', COMPAT / 'build.py')
audio_build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio_build)


def project_version():
    for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines():
        if line.startswith('mod_version='): return line.split('=', 1)[1].strip()
    raise ValueError('Missing mod_version')


def validate_tag(tag, version):
    pack_version(version)
    if tag != 'v' + version: raise ValueError(f'Release tag {tag!r} must match v{version}')


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    return destination


def pack_fingerprint(pack):
    """Content digest of relevant allocations/assets, independent of ZIP timestamps."""
    import hashlib
    digest = hashlib.sha256()
    with zipfile.ZipFile(pack) as archive:
        for name in sorted(archive.namelist()):
            if name.startswith(('assets/copper_speakers/', 'assets/minecraft/blockstates/')):
                data = archive.read(name)
                if name.endswith('.json'): data = json.dumps(json.loads(data), sort_keys=True, separators=(',', ':')).encode()
                digest.update(name.encode() + b'\0' + data + b'\0')
    return digest.hexdigest()


def audit_bundle(stage, version):
    geyser = stage / 'config/Geyser-Fabric'
    assert (stage / 'mods' / f'copper-speakers-{version}.jar').is_file()
    assert len(list((geyser / 'extensions').glob('*.jar'))) == 2
    assert len(list((geyser / 'packs').glob('*.mcpack'))) == 2
    assert len(list((geyser / 'custom_mappings').glob('*.json'))) == 2
    assert list((stage / 'sources').glob('*sources.zip'))
    versions = []
    for pack in (geyser / 'packs').glob('*.mcpack'):
        with zipfile.ZipFile(pack) as archive:
            assert archive.testzip() is None
            manifest = json.loads(archive.read('manifest.json'))
            versions.append(manifest['header']['version'])
            assert all(m['version'] == pack_version(version) for m in manifest['modules'])
    assert versions == [pack_version(version), pack_version(version)]
    for jar in (geyser / 'extensions').glob('*.jar'):
        with zipfile.ZipFile(jar) as archive:
            assert archive.testzip() is None
            assert 'extension.yml' in archive.namelist()
            assert not any('ProtocolSmoke' in n or n.endswith('Test.class') for n in archive.namelist())
    with zipfile.ZipFile(next((geyser / 'extensions').glob('CopperBedrock-*.jar'))) as archive:
        assert f'version: {version}' in archive.read('extension.yml').decode()
        assert 'dev/mcneds/copperspeakers/bedrock/CopperBedrock.class' in archive.namelist()
    with zipfile.ZipFile(next((stage / 'sources').glob('*sources.zip'))) as archive:
        assert 'LICENSE' in archive.namelist()
        assert 'copper-build/dependencies.lock.json' in archive.namelist()
        assert 'copper-build/display-models.json' in archive.namelist()
        assert any(n.endswith('/ItemDisplayEntity.java') for n in archive.namelist())
    print('PASS: bundle layout, versions, extension isolation and corresponding source', flush=True)


def build(inputs_file, output, cache, java_pack=None, client_jar=None):
    inputs = json.loads(inputs_file.read_text(encoding='utf-8'))
    version = inputs['version']
    if version != project_version(): raise ValueError('Stale Gradle release inputs; run writeReleaseInputs again')
    pins = lock()
    if inputs['minecraft'] != pins['minecraft']: raise ValueError('Minecraft version differs from Bedrock lock; retest and update the lock')
    pack_version(version)
    if output.exists() and any(output.iterdir()): raise ValueError('Choose a new empty output directory; release outputs are never overwritten')
    output.mkdir(parents=True, exist_ok=True)
    work, dist = output / 'work', output / 'dist'
    work.mkdir(); dist.mkdir()
    def download(item):
        name, descriptor = item
        path = fetch(descriptor, cache)
        print('Verified pinned input:', path.name, flush=True)
        return name, path
    with ThreadPoolExecutor(max_workers=4) as pool: dependencies = dict(pool.map(download, pins['downloads'].items()))
    java_pack = (java_pack or Path(inputs['javaPack'])).resolve()
    client_jar = (client_jar or Path(inputs['clientJar'])).resolve()
    for file in (java_pack, client_jar, Path(inputs['modJar']), Path(inputs['sourcesJar'])):
        if not file.is_file(): raise FileNotFoundError('Missing release input: ' + str(file))
    subprocess.run([sys.executable, str(ROOT / 'tools/check-artifacts.py'), inputs['modJar'], str(java_pack)], check=True)
    classpath = [Path(p) for p in Path(inputs['classpathFile']).read_text(encoding='utf-8').split(__import__('os').pathsep) if p]
    classpath.append(dependencies['checkerQual'])
    javac = Path(inputs['javac'])
    audio, audio_classpath = audio_build.build(dependencies['geyser'], classpath, work / 'audio', javac, version)
    visuals = work / 'visuals'
    convert(java_pack, client_jar, visuals, version=version)
    display, display_pack, display_source, display_license = build_display(
        dependencies['displaySource'], dependencies['displayJar'], dependencies['displayPack'],
        audio_classpath, work / 'display', javac, visuals / 'display-models.json', version)
    stage = work / 'bundle'
    geyser = stage / 'config/Geyser-Fabric'
    mod_jar = copy(Path(inputs['modJar']), stage / 'mods' / Path(inputs['modJar']).name)
    for jar in (audio, display): copy(jar, geyser / 'extensions' / jar.name)
    for pack in (visuals / 'copper-speakers-bedrock.mcpack', display_pack): copy(pack, geyser / 'packs' / pack.name)
    for name in ('copper-speakers-blocks.json', 'copper-speakers-items.json'): copy(visuals / name, geyser / 'custom_mappings' / name)
    copy(display_source, stage / 'sources' / display_source.name)
    copy(display_license, stage / 'licenses/GeyserDisplayEntity-AGPL-3.0.txt')
    copy(ROOT / 'LICENSE', stage / 'licenses/Copper-Speakers-MIT.txt')
    copy(COMPAT / 'INSTALL.md', stage / 'INSTALL.md')
    copy(COMPAT / 'dependencies.lock.json', stage / 'dependencies.lock.json')
    copy(COMPAT / 'sounds.example.json', stage / 'examples/sounds.json')
    metadata = {'format': 1, 'version': version, 'minecraft': inputs['minecraft'], 'geyserVersion': pins['geyser'],
                'geyserBuild': pins['geyserBuild'], 'bedrockProtocol': pins['bedrockProtocol'],
                'javaPackFingerprint': pack_fingerprint(java_pack), 'modSha256': sha256(mod_jar),
                'displayCommit': pins['displayCommit'], 'downloads': pins['downloads'],
                'tests': ['Minecraft GameTests and JUnit (Gradle)', 'Bedrock protocol and coordination', 'visual conversion', 'bundle audit']}
    json_file(stage / 'compatibility.json', metadata)
    audit_bundle(stage, version)
    (stage / 'SHA256SUMS').write_text(''.join(sha256(p) + '  ' + p.relative_to(stage).as_posix() + '\n'
        for p in sorted(stage.rglob('*')) if p.is_file()), encoding='ascii')
    bundle = dist / f'copper-speakers-{version}-bedrock-fabric.zip'
    zip_tree(stage, bundle)
    for artifact in (Path(inputs['modJar']), Path(inputs['sourcesJar']), display_source): copy(artifact, dist / artifact.name)
    json_file(dist / 'compatibility.json', metadata)
    (dist / 'SHA256SUMS').write_text(''.join(sha256(p) + '  ' + p.name + '\n' for p in sorted(dist.iterdir()) if p.is_file()), encoding='ascii')
    print('Release artifacts:', dist, flush=True)
    return dist


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, default=ROOT / 'build/release-inputs/inputs.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/release')
    parser.add_argument('--cache', type=Path, default=ROOT / 'build/release-dependencies')
    parser.add_argument('--java-pack', type=Path, help='Use a server-specific Polymer pack instead of the baseline')
    parser.add_argument('--client-jar', type=Path)
    parser.add_argument('--check-tag')
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    if args.check_tag: validate_tag(args.check_tag, project_version())
    if args.check_only:
        print(project_version()); return
    subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(COMPAT), '-p', 'test_*.py'], check=True)
    build(args.inputs.resolve(), args.output.resolve(), args.cache.resolve(), args.java_pack, args.client_jar)


if __name__ == '__main__': main()
