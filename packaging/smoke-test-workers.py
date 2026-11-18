#!/usr/bin/env python3
"""Exercise the bundled algorithm worker and export an archived project with the relocated launcher."""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import zipfile


def app_paths(app):
    # Accept Compose app images and the final macOS Swift-shell bundle.
    if (app / "Contents").is_dir():
        return app / "Contents/app", app / "Contents/runtime/Contents/Home/bin/java", app / "Contents/MacOS/PSD2Live"
    if (app / "lib/app").is_dir():
        return app / "lib/app", app / "lib/runtime/bin/java", app / "bin/PSD2Live"
    return app / "app", app / "runtime/bin/java.exe", app / "PSD2Live.exe"


def check_worker(app, directory, *, verify_export=False):
    jars, java, launcher = app_paths(app)
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
    # A host without rig_objects must get a framed error before the worker reads any model data.
    request = json.dumps(dict(protocol=2, id=37, reset=True, method="evaluate", capabilities=[])).encode()
    frame = struct.pack("<I", len(request)) + request + struct.pack("<I", 0)
    result = subprocess.run([str(java), "-jar", worker.name], input=frame, cwd=directory, env=env,
                            capture_output=True, timeout=30)
    assert result.returncode == 0, (
        f"Bundled worker exited {result.returncode}:\n{result.stderr.decode(errors='replace')}"
    )
    size, = struct.unpack_from("<I", result.stdout)
    response = json.loads(result.stdout[4:4 + size])
    assert result.stdout[4 + size:] == struct.pack("<I", 0), "Unexpected response blobs or trailing output"
    assert response["protocol"] == 2 and response["id"] == 37 and response["error"], response
    print(f"{ascii(str(app))}: bundled worker responded over protocol 2")
    if verify_export:
        fixture = Path(__file__).resolve().parents[1] / "src/test/resources/projects/legacy-split.psd2live"
        shutil.copyfile(fixture, directory / "input.psd2live")
        result = subprocess.run([str(launcher), "export", "input.psd2live", "--target", "p2lrt",
                                 "--output", "out", "--name", "probe"], cwd=directory, env=env,
                                capture_output=True, timeout=60)
        assert result.returncode == 0, result.stderr.decode(errors="replace")
        output = directory / "out"
        report = json.loads((output / "probe.p2lrt.report.json").read_bytes())
        assert report["target"] == "p2lrt" and report["files"] == ["probe.p2lrt"], report
        assert (output / "probe.p2lrt").read_bytes().startswith(b"P2LRT\0\0\0")
        print("Packaged launcher opened a project and exported its runtime rig")


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
        check_worker(relocated, directory / "relocated worker 模型", verify_export=True)


if __name__ == "__main__":
    main()
