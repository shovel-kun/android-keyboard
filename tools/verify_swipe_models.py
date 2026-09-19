"""Verify swipe model bytes against the pinned submodule's Git LFS pointers."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile

MODELS = (
    'honorable_sturgeon/model_fp32.pte',
    'hungry_jellyfish/context_lm.pte',
    'magic_macaw/model_fp32.pte',
)
LFS_HEADER = b'version https://git-lfs.github.com/spec/v1'


def pointer_for(root: Path, name: str) -> bytes:
    return subprocess.check_output(['git', '-C', str(root), 'show', f'HEAD:{name}'])


def reject_pointer(name: str, data: bytes) -> None:
    if data.startswith(LFS_HEADER):
        raise ValueError(f'{name}: unresolved Git LFS pointer')


def verify_bytes(name: str, data: bytes, pointer: bytes) -> None:
    reject_pointer(name, data)
    oid = re.search(rb'^oid sha256:([0-9a-f]{64})$', pointer, re.MULTILINE)
    size = re.search(rb'^size ([0-9]+)$', pointer, re.MULTILINE)
    if not pointer.startswith(LFS_HEADER) or oid is None or size is None:
        raise ValueError(f'{name}: expected pinned Git LFS metadata')
    if len(data) != int(size[1]):
        raise ValueError(f'{name}: model size mismatch')
    if hashlib.sha256(data).hexdigest() != oid[1].decode('ascii'):
        raise ValueError(f'{name}: model hash mismatch')
    print(f'OK {name}: {len(data)} bytes, SHA-256 matches pinned model')


def verify_directory(root: Path) -> None:
    for name in MODELS:
        data = (root / name).read_bytes()
        reject_pointer(name, data)
        verify_bytes(name, data, pointer_for(root, name))


def verify_apk(apk: Path, root: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        for name in MODELS:
            data = archive.read('assets/futo-swipe/' + name)
            reject_pointer(name, data)
            verify_bytes(name, data, pointer_for(root, name))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, default=Path('java/assets/futo-swipe'))
    parser.add_argument('--apk', type=Path, nargs='+')
    args = parser.parse_args()
    if args.apk:
        for apk in args.apk:
            verify_apk(apk, args.models)
    else:
        verify_directory(args.models)
