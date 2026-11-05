"""Developer-side packager. The application installer never invokes this script or a compiler."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile


def package(payload, output, language, platform, command):
    files = {}
    for path in sorted(payload.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symlinks are not supported')
        if path.is_file():
            files[path.relative_to(payload).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = dict(protocol=1, id='example-' + language, version='1.0.0', name=language + ' · Fade',
                    commands={platform: command}, files=files, algorithms=[dict(
                        type='example-' + language + '/fade', version=1, name=language + ' · Fade',
                        category='plugins', targets=['mesh'], requires=[], reads=['mesh'], writes=['mesh'],
                        composableKinds=['mesh'],
                        configSchema=dict(type='object', properties=dict(factor=dict(
                            type='number', minimum=0, maximum=1, title='Opacity multiplier')),
                            required=['factor'], additionalProperties=False), defaults=dict(factor=0.5))])
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('plugin.json', json.dumps(manifest, ensure_ascii=False, indent=2))
        for name in files:
            archive.write(payload / name, name)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--language', choices=['python', 'kotlin', 'rust', 'c', 'cpp', 'csharp'], required=True)
    parser.add_argument('--platform', default='windows-x86_64')
    parser.add_argument('command', nargs='+')
    args = parser.parse_args()
    package(args.payload, args.output, args.language, args.platform, args.command)
