#!/usr/bin/env python3
"""Mount the DMG and launch its bundled runtime from a path containing quotes and spaces."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, timeout=120, **kwargs)


def main():
    dmg = str(Path(sys.argv[1]).resolve())
    run("hdiutil", "verify", dmg)
    with tempfile.TemporaryDirectory(prefix="psd2live-smoke-") as directory:
        mount = Path(directory) / "mount"
        mount.mkdir()
        run("hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", str(mount), dmg)
        try:
            app = Path(directory) / "A user's Mac" / 'PSD2Live "test".app'
            run("ditto", str(mount / "PSD2Live.app"), str(app))
            run("codesign", "--verify", "--deep", "--strict", str(app))
            runtime = app / "Contents/runtime/Contents/Home/lib/server/libjvm.dylib"
            assert runtime.is_file(), "bundled libjvm missing"
            executable = str(app / "Contents/MacOS/PSD2Live")
            env = dict(os.environ, JAVA_HOME="/nonexistent-jdk")
            for command in ("--help", "targets"):
                result = run(executable, command, env=env, capture_output=True)
                assert result.stdout.strip(), f"{command}: no output: {result.stderr}"
                print(result.stdout)
        finally:
            run("hdiutil", "detach", str(mount))


if __name__ == "__main__":
    main()
