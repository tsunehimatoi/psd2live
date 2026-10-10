# Cubism CI and release workflows

[Documentation](../../README.md) · [中文](../../zh/guide/CUBISM_CI_RELEASE.md) · [Cubism native preview](CUBISM_SDK_SETUP.md)

This page describes the two GitHub Actions workflows: public tests (no SDK) and optional Cubism preview packaging. It does not replace the Live2D license or grant redistribution rights for proprietary components.

## The two workflows

| Workflow | File | Purpose |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | On `push` / `pull_request` to `master` (and `main`), runs `./gradlew test` on Ubuntu and Windows, x64 and arm64. Does not download the Cubism SDK and does not set `PSD2LIVE_INCLUDE_CUBISM`. |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | On manual `workflow_dispatch` or a `v*` tag push, privately fetches the SDK, builds the native bridges, packages Windows/Linux Cubism builds (Linux for x64 and arm64) plus a Windows ARM64 build without Cubism, and can publish a GitHub Release. |

macOS packaging is deferred; this workflow does not build it.

## Public repos and SDK leakage (required reading)

**On a public repository, GitHub Release assets are public.** Do not attach `CubismSdkForNative-*.zip` to a Release on this public application repo.

Recommended approach:

1. Store the SDK zip in a **private sibling repository**, as a prerelease/tag (for example `internal-sdk/cubism-5-r.5`) with asset name `CubismSdkForNative-5-r.5.zip` by default.
2. Set repository variable `CUBISM_SDK_SOURCE_REPO` on this app repo to that private repo (`owner/name`).
3. Set secret `SDK_FETCH_TOKEN` to a PAT (or fine-grained token) that can read that private Release. If unset, the workflow falls back to `GITHUB_TOKEN`, which usually **cannot** read another private repo.

Alternative: keep the zip in other private storage and adapt the fetch step; the current workflow uses `gh release download`.

## Variables and secrets

| Name | Kind | Default | Notes |
| --- | --- | --- | --- |
| `CUBISM_SDK_SOURCE_REPO` | Repository variable | Current `github.repository` | Repo that hosts the SDK Release. **Public app repos must point this at a private repo.** |
| `CUBISM_SDK_RELEASE_TAG` | Repository variable | `internal-sdk/cubism-5-r.5` | Private SDK Release / tag name |
| `CUBISM_SDK_ASSET_NAME` | Repository variable | `CubismSdkForNative-5-r.5.zip` | Asset file name |
| `SDK_FETCH_TOKEN` | Secret | (empty → `GITHUB_TOKEN`) | Used when fetching from a private SDK repo |

## Environment protection

Jobs `build-windows`, `build-linux`, and `release` use the GitHub Environment named **`release-cubism`**. Create it under Settings → Environments and add required reviewers / deployment branch rules so arbitrary collaborators cannot mint SDK-inclusive packages alone.

## Publishing a release

Prerequisites: the SDK zip is uploaded to the private repository, and the variables, secrets and `release-cubism` environment above are configured. `<version>` below stands for the target version, such as `3.3.1`.

1. **Bump the version first.** The workflow does not modify the repository. Set `version` and `packageVersion` in `build.gradle.kts`, update version strings shown in the UI, add the release to `docs/zh/CHANGELOG.md` and write the release notes to `.github/release-notes.md` (Chinese first, English in a collapsed `<details><summary>English</summary>` block; no download section, `.github/scripts/release_notes.py` builds categorized download badges from the actual assets at release time), then commit and push.
2. Open Actions → **Release Cubism** → **Run workflow** and set `version` to `<version>` (no leading `v`).
3. Keep `create_github_release` enabled unless you only want build artifacts.
4. Approve the environment gate and wait for the Windows and Linux jobs.
5. With publishing enabled, the workflow creates or updates tag `v<version>` as a **formal** (non-prerelease) GitHub Release with:
   - `PSD2Live-<version>-windows-x86_64-portable.zip`
   - `PSD2Live-<version>.exe`
   - each of the two above also with ffmpeg included: `PSD2Live-<version>-windows-x86_64-portable-ffmpeg.zip`, `PSD2Live-<version>-ffmpeg.exe`
   - `PSD2Live-<version>-windows-arm64-portable.zip`, `PSD2Live-<version>-arm64.exe`: Windows ARM64, without Cubism (Live2D ships no Cubism Core for it) and without an ffmpeg build
   - `PSD2Live-<version>-linux-amd64.deb`, `PSD2Live-<version>-linux-arm64.deb` (arm64 uses the SDK's experimental Cubism Core)

Pushing tag `v<version>` triggers the same workflow. Use one entry point to avoid running the full matrix twice.

## Artifacts and platform limits

- Linux preview needs X11/GLX (including XWayland / `xvfb-run`). Pure Wayland and musl/Alpine are unsupported. See [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md).
- The ARM64 packages are not yet tested on real hardware: whenever the assets include an arm64 package, `release_notes.py` adds a note saying so, with the known limits (the experimental Cubism Core on Linux arm64; the canvas may fail to draw on GPUs with only OpenGL 3.1). Remove the note once they have been verified.
- v1 skips fragile GUI smoke tests; Linux checks that the `.deb` exists and is non-empty.
- Windows packages are built twice: the second pass adds `-Ppsd2live.ffmpegDir=<dir>`, putting a pinned Gyan.dev ffmpeg essentials build (GPLv3; its SHA-256 and the encoders the video and animated image exports use are checked) with its `LICENSE.txt` and `README.txt` into the app's `resources/ffmpeg/`. To update ffmpeg, change both `FFMPEG_URL` and `FFMPEG_SHA256` in the workflow. Linux packages do not include ffmpeg.
- The Windows installer is built by `packageExe` from the app image with Inno Setup 6 and `packaging/windows/psd2live.iss`; `ISCC.exe` comes from `-Ppsd2live.iscc`, the `ISCC` environment variable or Inno Setup 6's default install folders, and the workflow installs Inno Setup with Chocolatey. Files are copied in place, without the MSI way of first moving the old ones into `Config.Msi`. It installs for all users by default (administrator rights), or for the current user only from the dialog at start or with `/CURRENTUSER`; an upgrade goes back to the installed folder and install mode, deletes the previous version's `app` and `runtime` folders before copying, and keeps the user's own files in the installation folder. The builds with and without ffmpeg share one AppId and replace each other. When PSD2Live runs from the installation folder, setup asks for it to be closed and retried (a silent setup stops). Versions 3.1.x and earlier are jpackage MSI packages: setup finds them by their upgrade code, defaults to their folder, clears the folder they recorded (the uninstall of 3.0.0 and earlier empties it), deletes the files they installed and removes them with `msiexec /x` before copying any file, so Windows Installer only unregisters them and moves nothing into `Config.Msi` (its log is `PSD2Live MSI removal.log` beside the setup log), asking for administrator rights when a current-user setup meets an all-users install. Uninstalling asks whether to delete the user's data too (No by default; the app's `--clear-user-data`, run as the account that runs the uninstaller; a command-line uninstall can pass `/CLEARUSERDATA=1`). Every install and uninstall writes a log to `%TEMP%` (`Setup Log *.txt`).
- Cubism-inclusive packages are only for uses allowed by your license; **do not** redistribute proprietary binaries on public channels.

## License reminder

Bridge code in this repository is GPL. Live2D Cubism Core / Framework / shaders are proprietary; obtain them yourself and follow Live2D’s terms. See [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md).

[Local SDK setup](CUBISM_SDK_SETUP.md) · [native scripts](../../../native/README.md)
