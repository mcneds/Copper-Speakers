#!/usr/bin/env python3
"""Build the pinned display fork and retain its complete corresponding source."""
import argparse
import json
import os
from pathlib import Path
import shutil
import zipfile
from patch_display import patch_source, patch_pack
from release_common import HERE, compile_java, json_file, lock, pack_version, sha256, zip_bytes, zip_tree


def build(source_zip, release_jar, upstream_pack, classpath, output, javac, display_models, version):
    pins = lock()
    output.mkdir(parents=True, exist_ok=True)
    source, classes = output / 'source', output / 'classes'
    source.mkdir(exist_ok=True)
    prefix = 'GeyserDisplayEntity-' + pins['displayCommit'] + '/'
    with zipfile.ZipFile(source_zip) as archive:
        for name in archive.namelist():
            if name.endswith('/'): continue
            if not name.startswith(prefix): raise ValueError('Unexpected display source revision')
            relative = name[len(prefix):]
            target = (source / relative).resolve()
            if not target.is_relative_to(source.resolve()): raise ValueError('Unsafe source archive path')
            target.parent.mkdir(parents=True, exist_ok=True)
            data = archive.read(name)
            if relative.startswith('src/main/java/') and relative.endswith('.java'):
                text = data.decode('utf-8-sig')
                text = text.replace('org.cloudburstmc.protocol.', 'org.geysermc.geyser.shaded.org.cloudburstmc.protocol.')
                text = text.replace('org.spongepowered.configurate.', 'me.geyserextensionists.geyserdisplayentity.libs.configurate.')
                data = patch_source(relative, text).encode('utf-8')
            target.write_bytes(data)
    compile_java(javac, [*classpath, release_jar], (source / 'src/main/java').rglob('*.java'), classes)
    models = json.loads(display_models.read_text(encoding='utf-8'))
    build_info = {'copperSpeakersVersion': version, 'displayCommit': pins['displayCommit'],
                  'sourceZipSha256': sha256(source_zip), 'upstreamJarSha256': sha256(release_jar),
                  'displayModelsSha256': sha256(display_models)}
    target = output / f'GeyserDisplayEntity-{pins["displayVersion"]}-copper-speakers-{version}.jar'
    with zipfile.ZipFile(release_jar) as original, zipfile.ZipFile(target, 'w') as jar:
        for name in sorted(original.namelist()):
            if name.endswith('/'): continue
            if name.startswith('me/geyserextensionists/geyserdisplayentity/') and '/libs/' not in name and name.endswith('.class'): continue
            if name.upper().startswith('META-INF/') and name.upper().endswith(('.SF', '.RSA', '.DSA')): continue
            zip_bytes(jar, name, original.read(name))
        for path in sorted(classes.rglob('*.class')): zip_bytes(jar, path.relative_to(classes).as_posix(), path.read_bytes())
        properties = '\n'.join(key.replace(':', '\\:') + '=' + str(value) for key, value in sorted(models['indices'].items()))
        zip_bytes(jar, 'copper-display-models.properties', properties.encode('ascii'))
        zip_bytes(jar, 'META-INF/LICENSE-GeyserDisplayEntity', (source / 'LICENSE').read_bytes())
        zip_bytes(jar, 'META-INF/copper-speakers-build.json', json.dumps(build_info, sort_keys=True).encode())
    pack = output / 'GeyserDisplayEntityPack.mcpack'
    shutil.copyfile(upstream_pack, pack)
    patch_pack(pack, models, pack_version(version))
    # Include the entire patched upstream tree, original build metadata/license,
    # exact patch inputs and a portable rebuild recipe with every release.
    recipe = source / 'copper-build'
    recipe.mkdir(exist_ok=True)
    for name in ['build_display.py', 'patch_display.py', 'display-native-methods.java.inc', 'release_common.py', 'dependencies.lock.json']:
        shutil.copyfile(HERE / name, recipe / name)
    shutil.copyfile(display_models, recipe / 'display-models.json')
    shutil.copyfile(source_zip, recipe / 'upstream-source.zip')
    (recipe / 'BUILDING.md').write_text(
        '# Rebuild the Copper Speakers display fork\n\n'
        'The adjacent tree contains all patched upstream source and its AGPL-3.0 license. '
        'dependencies.lock.json records the URLs and SHA-256s for upstream inputs. '
        'The Copper Speakers release source supplies the Minecraft compile classpath via `gradlew writeReleaseInputs`.\n\n'
        'From this directory, with the locked upstream JAR and pack downloaded:\n\n'
        '```sh\npython build_display.py --source-zip upstream-source.zip --release-jar /path/to/GeyserDisplayEntity-1.0.14.jar '
        '--upstream-pack /path/to/GeyserDisplayEntityPack.mcpack --classpath-file /path/to/classpath.txt '
        f'--display-models display-models.json --version {version} --output rebuilt\n```\n', encoding='utf-8')
    source_bundle = output / f'GeyserDisplayEntity-copper-speakers-{version}-sources.zip'
    zip_tree(source, source_bundle)
    json_file(output / 'provenance.json', build_info)
    print('Built', target, 'and corresponding source', source_bundle, flush=True)
    return target, pack, source_bundle, source / 'LICENSE'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-zip', type=Path, required=True)
    parser.add_argument('--release-jar', type=Path, required=True)
    parser.add_argument('--upstream-pack', type=Path, required=True)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--javac', type=Path, default=Path(shutil.which('javac') or 'javac'))
    parser.add_argument('--display-models', type=Path, required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    build(args.source_zip, args.release_jar, args.upstream_pack,
          [Path(p) for p in args.classpath_file.read_text(encoding='utf-8').strip().split(os.pathsep)],
          args.output.resolve(), args.javac, args.display_models, args.version)
