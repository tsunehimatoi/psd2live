#!/usr/bin/env python3
"""Validate release packages, write checksums and print the asset metadata for release notes."""

import argparse
import hashlib
import json
import re
from pathlib import Path


CUBISM_PACKAGES = {
    "windows-x64-setup.exe",
    "windows-x64-portable.zip",
    "windows-x64-setup-ffmpeg.exe",
    "windows-x64-portable-ffmpeg.zip",
    "linux-x64.deb",
    "linux-x64.tar.gz",
    "linux-arm64.deb",
    "linux-arm64.tar.gz",
}
OTHER_PACKAGES = {
    "windows-arm64-setup.exe",
    "windows-arm64-portable.zip",
    "macos-arm64.dmg",
    "macos-x86_64.dmg",
}


def assemble(version, directory, existing):
    prefix = f"PSD2Live-{version}-"
    retained = {prefix + suffix for suffix in OTHER_PACKAGES} if existing else set()
    rebuilt = {prefix + suffix for suffix in CUBISM_PACKAGES | OTHER_PACKAGES} - retained
    packages = {path.name: path for path in directory.iterdir() if path.name != "SHA256SUMS.txt"}
    if packages.keys() != rebuilt:
        missing = sorted(rebuilt - packages.keys())
        extra = sorted(packages.keys() - rebuilt)
        raise ValueError(f"Unexpected package set (missing {missing}, extra {extra})")

    assets = []
    for name, path in packages.items():
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError(f"Invalid package: {path}")
        with path.open("rb") as package:
            digest = hashlib.file_digest(package, "sha256").hexdigest()
        assets.append({"name": name, "size": path.stat().st_size, "sha256": digest})
    if existing:
        if existing["tag_name"] != f"v{version}" or existing["draft"] or existing["prerelease"]:
            raise ValueError("The existing release must be the matching stable release")
        published = {asset["name"]: asset for asset in existing["assets"]}
        for name in retained:
            asset = published[name]
            digest = re.fullmatch(r"sha256:([0-9a-f]{64})", asset.get("digest") or "")
            if digest is None or asset["size"] <= 0 or asset["state"] != "uploaded":
                raise ValueError(f"Missing uploaded data or digest for retained package: {name}")
            assets.append({"name": name, "size": asset["size"], "sha256": digest[1]})

    assets.sort(key=lambda asset: asset["name"])
    (directory / "SHA256SUMS.txt").write_text(
        "".join(f"{a['sha256']}  {a['name']}\n" for a in assets), encoding="utf-8", newline="\n"
    )
    return assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--existing-release", type=Path)
    args = parser.parse_args()
    existing = json.loads(args.existing_release.read_text(encoding="utf-8")) if args.existing_release else None
    print(json.dumps(assemble(args.version, args.directory, existing)))


if __name__ == "__main__":
    main()
