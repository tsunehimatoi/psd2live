#!/usr/bin/env python3
"""Compose a GitHub Release body: the notes with a download section of badge links built from the assets.

The notes are the Chinese text followed by an English translation in a <details> block; the download
section goes between them (or at the end), followed by any "## SHA-256" section of the notes.

  release_notes.py --tag v3.0.1 --repo owner/name --assets-dir release-assets --notes notes.md
  release_notes.py --tag v3.0.1 --repo owner/name --assets-json assets.json --notes notes.md
"""
import argparse
import base64
import json
import os
import re
import sys
from urllib.parse import quote

WINDOWS_LOGO = "data:image/svg+xml;base64," + base64.b64encode(
	b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="white">'
	b'<rect x="2" y="2" width="9.5" height="9.5" rx="1"/><rect x="12.5" y="2" width="9.5" height="9.5" rx="1"/>'
	b'<rect x="2" y="12.5" width="9.5" height="9.5" rx="1"/><rect x="12.5" y="12.5" width="9.5" height="9.5" rx="1"/></svg>'
).decode()

COLORS = {"exe": "0078D4", "msi": "0078D4", "zip": "6F42C1", "ffmpeg": "2E7D32", "deb": "D70A53", "tarball": "E95420", "dmg": "555555"}
BADGE_LABEL = {"exe": "EXE", "msi": "MSI", "zip": "ZIP", "deb": "DEB", "tarball": "TAR.GZ", "dmg": "DMG"}


def esc(text):
	return quote(text.replace("-", "--").replace("_", "__"), safe="")


def size_text(size):
	mb = size / 1048576
	return f"{mb:.0f} MB" if mb >= 10 else f"{mb:.1f} MB"


def badge(label, message, color, logo, url, alt):
	img = (f"https://img.shields.io/badge/{esc(label)}-{esc(message)}-{color}"
	       f"?style=for-the-badge&logo={quote(logo, safe='')}&logoColor=white")
	return f"[![{alt}]({img})]({url})"


def kind(name):
	lower = name.lower()
	if lower.endswith(".deb"):
		return "deb"
	if lower.endswith(".tar.gz"):
		return "tarball"
	if lower.endswith(".zip"):
		return "zip"
	if lower.endswith(".exe"):
		return "exe"
	if lower.endswith(".msi"):
		return "msi"
	if lower.endswith(".dmg"):
		return "dmg"
	return None


def is_arm64(name):
	return "arm64" in name.lower()


def version_tuple(tag):
	return tuple(int(p) for p in re.findall(r"\d+", tag)[:3])


def downloads(tag, repo, assets):
	base = f"https://github.com/{repo}/releases/download/{tag}/"
	order = {"exe": 0, "msi": 1, "zip": 2}
	win = sorted((a for a in assets if kind(a[0]) in order), key=lambda a: order[kind(a[0])])
	ffmpeg = [a for a in win if "-ffmpeg" in a[0]]
	debs = [a for a in assets if kind(a[0]) == "deb"]
	tarballs = [a for a in assets if kind(a[0]) == "tarball"]
	dmgs = [a for a in assets if kind(a[0]) == "dmg"]

	def tag(name):
		k = kind(name)
		system = "Linux" if k in ("deb", "tarball") else "Windows"
		arch = "arm64" if is_arm64(name) else "x64"
		text = f"{arch} {BADGE_LABEL[k]}" + (" + ffmpeg" if "-ffmpeg" in name else "")
		if "-ffmpeg" in name:
			logo = "ffmpeg"
		else:
			logo = {"deb": "debian", "tarball": "linux", "dmg": "apple"}.get(k, WINDOWS_LOGO)
		return text, ("ffmpeg" if "-ffmpeg" in name else k), logo

	groups = [
		("Windows x64", [a for a in win if not is_arm64(a[0]) and "-ffmpeg" not in a[0]]),
		("Windows x64 + ffmpeg", [a for a in win if not is_arm64(a[0]) and "-ffmpeg" in a[0]]),
		("Windows arm64", [a for a in win if is_arm64(a[0])]),
		("Linux x64", [a for a in debs + tarballs if not is_arm64(a[0])]),
		("Linux arm64", [a for a in debs + tarballs if is_arm64(a[0])]),
		("macOS Intel", [a for a in dmgs if not is_arm64(a[0])]),
		("macOS Apple Silicon", [a for a in dmgs if is_arm64(a[0])]),
	]
	lines = ["## 下载 · Downloads", "", "| 平台 · Platform | 安装 · Setup | 免安装 · Portable |", "| :-- | :-- | :-- |"]
	for title, group in groups:
		if not group:
			continue
		cells = {"setup": [], "portable": []}
		for name, size in group:
			text, color, logo = tag(name)
			col = "portable" if kind(name) in ("zip", "tarball") else "setup"
			cells[col].append(badge(text, size_text(size), COLORS[color], logo, base + quote(name), name))
		lines.append(f"| **{title}** | {' '.join(cells['setup'])} | {' '.join(cells['portable'])} |")
	lines.append("")
	notes = []
	if ffmpeg:
		notes.append(("带 ffmpeg 的包可直接导出视频和动图，其余包需自行安装 ffmpeg。",
		              "Packages with ffmpeg export video and animations directly; the others need ffmpeg installed."))
	# Releases without an MSI have the Inno Setup installer, which also replaces the MSI installs of 3.1.x and earlier.
	if any(kind(a[0]) == "exe" for a in win) and not any(kind(a[0]) == "msi" for a in win):
		notes.append(("EXE 覆盖安装到原目录，并自动移除 3.1.x 及更早的安装。",
		              "The EXE installs over the existing folder and removes installs of 3.1.x and earlier."))
	elif any(kind(a[0]) in ("exe", "msi") for a in win) and version_tuple(tag) >= (3, 0, 1):
		notes.append(("EXE / MSI 覆盖安装到原目录。", "EXE / MSI install over the existing folder."))
	if any(is_arm64(a[0]) for a in win + debs + tarballs):
		# Until the arm64 packages have been tried on real hardware.
		notes.append(("ARM64 包未经实机测试，Windows ARM64 无 Cubism 预览和 ffmpeg 版本；问题请提交 Issue。",
		              "ARM64 packages are untested on real hardware; Windows ARM64 has no Cubism preview or ffmpeg build. Please report problems as issues."))
	if tarballs:
		notes.append(("Linux tar.gz 解压后运行 `PSD2Live/bin/PSD2Live`；Deb 需 X11/GLX。",
		              "Linux tar.gz: extract and run `PSD2Live/bin/PSD2Live`; the Deb needs X11/GLX."))
	elif debs:
		notes.append(("Linux Deb 需 X11/GLX。", "The Linux Deb needs X11/GLX."))
	for zh, en in notes:
		lines.append(f"- {zh}<br><sub>{en}</sub>")
	return "\n".join(lines).rstrip() + "\n"


def split_sha(text):
	match = re.search(r"^## SHA-256\s*$.*?(?=^#{1,2} |\Z)", text, re.M | re.S)
	if not match:
		return text, ""
	return (text[:match.start()] + text[match.end():]).strip(), match.group(0).strip()


def compose(notes, section):
	idx = notes.find("<details>")
	head, tail = (notes[:idx], notes[idx:]) if idx >= 0 else (notes, "")
	head, sha = split_sha(head.strip())
	if re.fullmatch(r"<details>\s*<summary>[^<]*</summary>\s*</details>", tail.strip()):
		tail = ""
	parts = [p for p in (head, section.strip(), sha, tail.strip()) if p]
	return "\n\n".join(parts) + "\n"


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("--tag", required=True)
	ap.add_argument("--repo", required=True)
	ap.add_argument("--notes", required=True)
	group = ap.add_mutually_exclusive_group(required=True)
	group.add_argument("--assets-dir")
	group.add_argument("--assets-json", help="JSON list of {name, size}")
	args = ap.parse_args()
	if args.assets_dir:
		assets = [(n, os.path.getsize(os.path.join(args.assets_dir, n))) for n in sorted(os.listdir(args.assets_dir))]
	else:
		assets = [(a["name"], a["size"]) for a in json.load(open(args.assets_json, encoding="utf-8"))]
	notes = open(args.notes, encoding="utf-8").read()
	sys.stdout.reconfigure(encoding="utf-8", newline="\n")
	sys.stdout.write(compose(notes, downloads(args.tag, args.repo, assets)))


if __name__ == "__main__":
	main()
