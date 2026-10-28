#!/usr/bin/env python3
"""Build and test the audio extension from explicit dependency inputs."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from release_common import HERE, compile_java, zip_bytes


def build(geyser, dependencies, output, javac, version, source=HERE):
    output.mkdir(parents=True, exist_ok=True)
    classpath = [geyser, *dependencies]
    nested = output / 'dependencies'
    nested.mkdir(exist_ok=True)
    with zipfile.ZipFile(geyser) as jar:
        for index, name in enumerate(jar.namelist()):
            if name.endswith('.jar'):
                path = nested / f'{index}.jar'
                path.write_bytes(jar.read(name))
                classpath.append(path)
    classes = output / 'classes'
    compile_java(javac, classpath, (source / 'src').rglob('*.java'), classes)
    shutil.copyfile(source.parents[1] / 'src/main/resources/assets/copper_speakers/lang/en_us.json',
                    classes / 'copper-speakers-en_us.json')
    (classes / 'audio-events.properties').write_text('# Legacy mappings are loaded at runtime.\n', encoding='ascii')
    (classes / 'extension.yml').write_text((source / 'extension.yml').read_text(encoding='utf-8').replace('${version}', version), encoding='utf-8')
    test_classes = output / 'test-classes'
    compile_java(javac, [classes, *classpath], (source / 'test').rglob('*.java'), test_classes)
    java = Path(javac).with_name('java.exe' if os.name == 'nt' else 'java')
    runtime = ['-cp', os.pathsep.join(map(str, [test_classes, classes, *classpath])),
               'dev.mcneds.copperspeakers.bedrock.ProtocolSmoke']
    runtime_args = output / 'test-java.args'
    runtime_args.write_text('\n'.join('"' + arg.replace('\\', '/').replace('"', '\\"') + '"' for arg in runtime), encoding='utf-8')
    subprocess.run([str(java), '@' + str(runtime_args)], check=True)
    target = output / f'CopperBedrock-{version}.jar'
    with zipfile.ZipFile(target, 'w') as jar:
        for path in sorted(classes.rglob('*')):
            if path.is_file(): zip_bytes(jar, path.relative_to(classes).as_posix(), path.read_bytes())
        zip_bytes(jar, 'META-INF/LICENSE-Copper-Speakers', (HERE.parents[1] / 'LICENSE').read_bytes())
    print('Built and tested', target, flush=True)
    return target, classpath


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--geyser-jar', required=True, type=Path)
    parser.add_argument('--classpath-file', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--javac', type=Path, default=Path(shutil.which('javac') or 'javac'))
    args = parser.parse_args()
    dependencies = [Path(p) for p in args.classpath_file.read_text(encoding='utf-8').strip().split(os.pathsep) if p]
    build(args.geyser_jar.resolve(), dependencies, args.output.resolve(), args.javac, args.version)
