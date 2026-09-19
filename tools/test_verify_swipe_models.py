import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('verify_swipe_models.py')


class SwipeModelsTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('verify_swipe_models', SCRIPT)
        assert spec is not None and spec.loader is not None
        self.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.validator)

    def test_rejects_lfs_pointer(self):
        with tempfile.TemporaryDirectory() as root:
            for name in self.validator.MODELS:
                p = Path(root) / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(b'version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 123\n')
            with self.assertRaisesRegex(ValueError, 'LFS pointer'):
                self.validator.verify_directory(Path(root))

    def test_rejects_missing_model(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises((ValueError, FileNotFoundError)):
                self.validator.verify_directory(Path(root))

    def test_validates_lfs_size_and_hash(self):
        import hashlib
        payload = b'fixture model bytes'
        pointer = ('version https://git-lfs.github.com/spec/v1\noid sha256:' +
                   hashlib.sha256(payload).hexdigest() + '\nsize ' + str(len(payload)) + '\n').encode()
        self.validator.verify_bytes('fixture', payload, pointer)
        with self.assertRaisesRegex(ValueError, 'size|hash'):
            self.validator.verify_bytes('fixture', payload[:-1], pointer)
        with self.assertRaisesRegex(ValueError, 'hash'):
            self.validator.verify_bytes('fixture', b'x' * len(payload), pointer)

    def test_apk_accepts_pinned_model_bytes(self):
        import hashlib
        from unittest.mock import patch
        payload = b'fixture model bytes'
        pointer = ('version https://git-lfs.github.com/spec/v1\noid sha256:' +
                   hashlib.sha256(payload).hexdigest() + '\nsize ' + str(len(payload)) + '\n').encode()
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                for name in self.validator.MODELS:
                    z.writestr('assets/futo-swipe/' + name, payload)
            with patch.object(self.validator, 'pointer_for', return_value=pointer):
                self.validator.verify_apk(apk, Path(root))

    def test_apk_rejects_pointer(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                for name in self.validator.MODELS:
                    z.writestr('assets/futo-swipe/' + name, b'version https://git-lfs.github.com/spec/v1\n')
            with self.assertRaisesRegex(ValueError, 'LFS pointer'):
                self.validator.verify_apk(apk, Path(root))


if __name__ == '__main__':
    unittest.main()
