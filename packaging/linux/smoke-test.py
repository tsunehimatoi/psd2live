#!/usr/bin/env python3
"""Check the actual packaged launcher, its dependency manifest and relocation without opening the GUI."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit
import zipfile


def check_classpath(app):
    directory = app / "lib/app"
    config = (directory / "PSD2Live.cfg").read_text()
    entries = [line for line in config.splitlines() if line.startswith("app.classpath=")]
    assert entries == ["app.classpath=$APPDIR/psd2live-classpath.jar"], entries
    with zipfile.ZipFile(directory / "psd2live-classpath.jar") as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    classpath = next(line.removeprefix("Class-Path: ") for line in manifest.splitlines() if line.startswith("Class-Path: "))
    paths = []
    for entry in classpath.split():
        uri = urlsplit(entry)
        assert not uri.scheme and not uri.netloc and not uri.query and not uri.fragment, entry
        path = (directory / unquote(uri.path)).resolve()
        assert path.is_relative_to(directory.resolve()) and path.is_file(), path
        paths.append(path)
    expected = {p.resolve() for p in directory.glob("*.jar") if p.name != "psd2live-classpath.jar"}
    assert set(paths) == expected, "Manifest must retain every packaged dependency"
    assert paths[0].name.startswith("psd2live-"), "Preserve the application JAR's classpath precedence"
    print(f"Verified {len(paths)} packaged classpath entries")


def check_launcher(app, home):
    home.mkdir()
    empty_path = home / "empty-path"
    empty_path.mkdir()
    env = dict(os.environ, JAVA_HOME="/nonexistent-jdk", PATH=str(empty_path), JAVA_TOOL_OPTIONS=(
        f'-Duser.home="{home}" -Djava.util.prefs.userRoot="{home / "prefs"}"'))
    for key in ("_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)
    for args in (("--help", "--lang", "en"), ("targets", "--lang", "en")):
        result = subprocess.run([str(app / "bin/PSD2Live"), *args], cwd=home, env=env,
                                capture_output=True, text=True, timeout=30)
        assert result.returncode == 0, f"{args}: exit {result.returncode}\n{result.stderr}"
        if args[0] == "--help":
            assert "--input" in result.stdout, result.stdout
        else:
            targets = {line.split()[0] for line in result.stdout.splitlines() if line and not line[0].isspace()}
            assert {"cmo3", "moc3", "p2lrt"} <= targets, targets
        print(f"{app.name}: {' '.join(args)} passed")


def main():
    app = Path(sys.argv[1]).resolve()
    check_classpath(app)
    # Copy, rather than link, so the relocation test cannot mutate the original app image.
    with tempfile.TemporaryDirectory(prefix="psd2live-launcher-") as directory:
        directory = Path(directory)
        check_launcher(app, directory / "home")
        relocated = directory / "A user's 模型" / 'PSD2Live "test"'
        shutil.copytree(app, relocated, symlinks=True)
        check_classpath(relocated)
        check_launcher(relocated, directory / "relocated-home")


if __name__ == "__main__":
    main()
