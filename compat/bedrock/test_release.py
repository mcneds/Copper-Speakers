import hashlib
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from release_common import fetch, pack_version, sha256, zip_tree


class ReleaseIntegrityTests(unittest.TestCase):
    def test_dependency_bytes_are_verified_even_when_cached(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'input.jar'; source.write_bytes(b'pinned dependency')
            spec = {'file': 'cached.jar', 'url': source.as_uri(), 'sha256': sha256(source)}
            cache = root / 'cache'
            result = fetch(spec, cache)
            result.write_bytes(b'corrupted cache')
            self.assertEqual(fetch(spec, cache).read_bytes(), b'pinned dependency')
            source.write_bytes(b'changed upstream bytes')
            result.write_bytes(b'corrupted again')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'): fetch(spec, cache)
            self.assertFalse((cache / 'cached.jar.part').exists())

    def test_archives_do_not_depend_on_local_timestamps(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); tree = root / 'source'; tree.mkdir()
            file = tree / 'hello.txt'; file.write_text('hello', encoding='utf-8')
            zip_tree(tree, root / 'first.zip')
            os.utime(file, (123456789, 123456789))
            zip_tree(tree, root / 'second.zip')
            self.assertEqual(sha256(root / 'first.zip'), sha256(root / 'second.zip'))
            with zipfile.ZipFile(root / 'first.zip') as archive:
                self.assertEqual(archive.namelist(), ['hello.txt'])

    def test_pack_versions_are_numeric_and_reject_unsafe_release_names(self):
        self.assertEqual(pack_version('0.3.0'), [0, 3, 0])
        self.assertEqual(pack_version('0.3.1-beta.1'), [0, 3, 1])
        for version in ['latest', '../escape', '0.3', '0.3.0/other']:
            with self.assertRaises(ValueError): pack_version(version)


if __name__ == '__main__': unittest.main()
