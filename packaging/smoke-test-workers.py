#!/usr/bin/env python3
"""Start a bundled algorithm worker with the packaged Java runtime, without a system JDK or GUI."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def app_paths(app):
    # Accept Compose app images and the final macOS Swift-shell bundle.
    if (app / "Contents").is_dir():
        return app / "Contents/app", app / "Contents/runtime/Contents/Home/bin/java"
    if (app / "lib/app").is_dir():
        return app / "lib/app", app / "lib/runtime/bin/java"
    return app / "app", app / "runtime/bin/java.exe"


def check_worker(app, directory):
    jars, java = app_paths(app)
    assert java.is_file(), f"Packaged worker launcher is missing: {java}"
    directory.mkdir()
    worker = directory / "runtime.jar"
    resource = "algorithm-plugins/shared/runtime.jar"
    for path in sorted(jars.glob("*.jar")):
        with zipfile.ZipFile(path) as jar:
            if resource in jar.namelist():
                worker.write_bytes(jar.read(resource))
                break
    assert worker.is_file(), f"No bundled binding worker found in {jars}"

    home = directory / "home"
    home.mkdir()
    empty_path = directory / "empty-path"
    empty_path.mkdir()
    env = dict(os.environ)
    for key in ("_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)
    env.update(JAVA_HOME=str(directory / "nonexistent-jdk"), PATH=str(empty_path),
               JAVA_TOOL_OPTIONS=f'-Duser.home="{home}" -Djava.util.prefs.userRoot="{home / "prefs"}"',
               PSD2LIVE_PLUGIN_PROTOCOL="2")
    # The real binding worker registers its algorithms before reading stdin; EOF ends the loop cleanly.
    result = subprocess.run([str(java), "-jar", str(worker)], input=b"", cwd=directory, env=env,
                            capture_output=True, timeout=30)
    assert result.returncode == 0, (
        f"Bundled worker exited {result.returncode}:\n{result.stderr.decode(errors='replace')}"
    )
    print(f"{app}: bundled Java started the binding worker successfully")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="Packaged PSD2Live app directory (or PSD2Live.app on macOS)")
    args = parser.parse_args()
    app = args.app.resolve()
    with tempfile.TemporaryDirectory(prefix="psd2live-worker-") as directory:
        directory = Path(directory)
        check_worker(app, directory / "worker")
        # Copy the image to exercise executable and JAR paths with spaces, apostrophes and Unicode.
        relocated = directory / "A user's 模型" / app.name
        shutil.copytree(app, relocated, symlinks=True)
        check_worker(relocated, directory / "relocated worker 模型")


if __name__ == "__main__":
    main()
