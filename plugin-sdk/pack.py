"""Standalone plugin packager: builds plugin.json with SHA-256 inventory and packs a .zip archive."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import zipfile
from pathlib import Path

SKIP_DIRS = {".git", "dist", "target", "__pycache__"}
SKIP_FILES = {".gitignore", "plugin.manifest.json", "plugin.json"}


def current_platform() -> str:
    sys_name = platform.system().lower()
    os_part = "windows" if "win" in sys_name else ("macos" if "darwin" in sys_name else "linux")
    arch = platform.machine().lower()
    arch_part = "x86_64" if arch in ("amd64", "x86_64") else ("aarch64" if arch in ("arm64", "aarch64") else arch)
    return f"{os_part}-{arch_part}"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def collect_payloads(source_dir: Path, manifest: dict) -> dict[str, Path]:
    # Source project mode (e.g. Cargo crate with target/release/<bin>): pack only the platform binary.
    if (source_dir / "Cargo.toml").is_file() or (source_dir / "target" / "release").is_dir():
        plat = current_platform()
        cmd = manifest.get("commands", {}).get(plat)
        if not cmd:
            raise FileNotFoundError(f"No command declared for platform {plat} in plugin.manifest.json")
        exe_name = cmd[0]
        for candidate in (source_dir / "target" / "release" / exe_name, source_dir / exe_name):
            if candidate.is_file():
                manifest["commands"] = {plat: cmd}
                return {exe_name: candidate}
        raise FileNotFoundError(f"Release binary not found for {plat}: {source_dir / 'target' / 'release' / exe_name}")

    # Staging directory mode: pack all payload files relative to source_dir.
    payloads: dict[str, Path] = {}
    for root, dirs, filenames in os.walk(source_dir):
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name in SKIP_FILES:
                continue
            full = Path(root) / name
            payloads[full.relative_to(source_dir).as_posix()] = full
    return payloads


def pack(
    source_dir: Path,
    output_zip: Path | None = None,
    protocol: int | None = None,
    version: str | None = None,
    deps: list[str] | None = None,
) -> Path:
    manifest_in = source_dir / "plugin.manifest.json"
    if not manifest_in.is_file():
        raise FileNotFoundError(f"Missing {manifest_in}")
    manifest = json.loads(manifest_in.read_text(encoding="utf-8"))

    if version:
        manifest["version"] = version
    if protocol is not None:
        manifest["protocol"] = protocol
    elif "protocol" not in manifest:
        manifest["protocol"] = 2
    if deps:
        existing = [d["id"] for d in manifest.get("dependencies", []) if isinstance(d, dict) and "id" in d]
        merged = list(dict.fromkeys([*existing, *deps]))
        manifest["dependencies"] = [{"id": d} for d in merged]

    payloads = collect_payloads(source_dir, manifest)
    manifest["files"] = {rel: sha256_file(path) for rel, path in sorted(payloads.items())}

    out = output_zip or (source_dir / "dist" / f"{manifest['id']}-{manifest['version']}.zip")
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("plugin.json", json.dumps(manifest, indent=2, ensure_ascii=False))
        for rel, path in sorted(payloads.items()):
            zf.write(path, arcname=rel)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="Package a standalone PSD2Live plugin archive.")
    parser.add_argument("source", type=Path, help="Plugin directory containing plugin.manifest.json")
    parser.add_argument("output", nargs="?", type=Path, default=None, help="Optional output .zip archive path")
    parser.add_argument("-o", "--output-flag", dest="output_flag", type=Path, default=None, help="Output .zip archive path")
    parser.add_argument("--protocol", type=int, default=None, choices=[1, 2])
    parser.add_argument("--version", default=None, help="Override manifest version")
    parser.add_argument("--dep", action="append", default=[], help="Add dependency plugin id (repeatable)")
    args = parser.parse_args()

    out = pack(
        args.source,
        output_zip=args.output_flag or args.output,
        protocol=args.protocol,
        version=args.version,
        deps=args.dep,
    )
    print(out)


if __name__ == "__main__":
    main()
