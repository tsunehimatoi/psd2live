<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/CUBISM_CI_RELEASE.md) · **English** · [한국어](../../ko/guide/CUBISM_CI_RELEASE.md) · [日本語](../../ja/guide/CUBISM_CI_RELEASE.md)

# Cubism CI and release workflow

[Documentation](../README.md) · [Cubism native preview](CUBISM_SDK_SETUP.md)

This page describes two GitHub Actions workflows in the repository: public tests (without the SDK) and optional Cubism preview packaging. It does not replace the Live2D license, and it does not grant the right to redistribute proprietary components.

## The two workflows

| Workflow | File | Role |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | On `push` / `pull_request` to `master` (and `main`), runs `./gradlew test` on Ubuntu and Windows, x64 and arm64. It does not download the Cubism SDK, and it does not set `PSD2LIVE_INCLUDE_CUBISM`. |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | On a manual `workflow_dispatch`, or when a `v*` tag is pushed, privately fetches the SDK, compiles the native bridge, and builds Windows/Linux installers that include Cubism (Linux includes x64 and arm64). It also builds a Windows ARM64 package without Cubism, and it can create a GitHub Release. |

macOS packaging is deferred. This workflow does not build it.

## Public repositories and SDK leaks (read this)

**On a public repository, attachments on a GitHub Release are visible to everyone by default.** Do not attach `CubismSdkForNative-*.zip` to a Release of this public application repository.

Recommended approach:

1. A **private sibling repository** stores the SDK zip and publishes a prerelease / tag (for example `internal-sdk/cubism-5-r.5`). The default attachment name is `CubismSdkForNative-5-r.5.zip`.
2. In this repository, set the variable `CUBISM_SDK_SOURCE_REPO` to that private repository (`owner/name`).
3. Set the secret `SDK_FETCH_TOKEN`: a PAT (or fine-grained token) that can read that private repository's Releases. When it is unset, the workflow falls back to `GITHUB_TOKEN`, which usually **cannot** read other private repositories.

Alternative: put the zip in other private storage, then change the workflow's fetch logic separately. The current implementation uses `gh release download`.

## Variables and secrets

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `CUBISM_SDK_SOURCE_REPO` | Repository variable | The current repository `github.repository` | The repository that holds the SDK Release. **A public application repository must change this to a private repository.** |
| `CUBISM_SDK_RELEASE_TAG` | Repository variable | `internal-sdk/cubism-5-r.5` | The private SDK Release / tag name |
| `CUBISM_SDK_ASSET_NAME` | Repository variable | `CubismSdkForNative-5-r.5.zip` | The attachment file name |
| `SDK_FETCH_TOKEN` | Secret | (empty, then `GITHUB_TOKEN` is used) | Used when fetching the private SDK repository |

## Environment protection

The jobs `build-windows`, `build-linux`, and `release` use the GitHub Environment name **`release-cubism`**. Create that environment under repository Settings → Environments, and add required reviewers / deployment-branch restrictions, so an arbitrary collaborator cannot produce a package that contains the SDK directly.

## Publish a version

Prerequisites: the private SDK repository has uploaded the zip, and this repository has configured the variables, the secret, and the `release-cubism` environment from the table above. Below, `<version>` stands for the target version number, for example `3.8.0`.

1. **Bump the version first.** The workflow does not modify the repository contents. Change `version` in `build.gradle.kts`. `packageVersion` and `psd2live/version.properties` in the resources are both generated from it. Also change the version in the command-line help (`cli.usage` in the four `Messages*.properties`) and the first version in the four `src/main/resources/whatsnew/<language>.md` files. `VersionConsistencyTest` checks in CI that those places match the build version. Add this version's entry to `docs/zh/CHANGELOG.md`, `docs/en/CHANGELOG.md`, `docs/ja/CHANGELOG.md`, and `docs/ko/CHANGELOG.md`, and write this version's notes into `.github/release-notes.md` (Chinese first, English inside a collapsed `<details><summary>English</summary>`; do not write a download section, because at publish time `.github/scripts/release_notes.py` builds categorized download badges from the actual attachments). Commit and push.
2. Open Actions → **Release Cubism** → **Run workflow**, and fill `version` with `<version>` (without the `v`).
3. Check `create_github_release` as needed (on by default; when it is off, only build artifacts are produced). For a preview build, check `prerelease`. It is published as a prerelease and is not marked latest.
4. If the `release-cubism` environment has required reviewers, approve it here. When none are configured, the build starts immediately. Wait for the Windows and Linux builds to finish.
5. When publishing is on, the workflow creates or updates the GitHub Release for the tag `v<version>` (a full release by default, a prerelease when `prerelease` is checked). The attachments are:
   Names are unified as `PSD2Live-<version>-<os>-<arch>[-<kind>][-ffmpeg].<ext>`: the system is `windows` / `linux`, the architecture is `x64` / `arm64`, and the Windows kind is `setup` (installer) / `portable` (no install).
   - `PSD2Live-<version>-windows-x64-setup.exe`, `PSD2Live-<version>-windows-x64-portable.zip`
   - Each of the two above also has a build with ffmpeg built in: `PSD2Live-<version>-windows-x64-setup-ffmpeg.exe`, `PSD2Live-<version>-windows-x64-portable-ffmpeg.zip`
   - `PSD2Live-<version>-windows-arm64-setup.exe`, `PSD2Live-<version>-windows-arm64-portable.zip`: Windows ARM64, without Cubism (Live2D does not provide Cubism Core for that platform), and with no ffmpeg build
   - `PSD2Live-<version>-linux-x64.deb`, `PSD2Live-<version>-linux-arm64.deb` (arm64 uses the experimental Cubism Core in the SDK), plus `PSD2Live-<version>-linux-x64.tar.gz` and `PSD2Live-<version>-linux-arm64.tar.gz` with the same contents (an app image, for distributions without Deb)

   Versions 3.4.0 and earlier keep the old names (for example `PSD2Live-<version>.exe`, `-windows-x86_64-portable.zip`, `-linux-amd64.deb`).

You can also push the tag `v<version>` directly to trigger the same workflow. Pick one of the two entry points, so the full build matrix does not run twice.

## Artifacts and platform limits

- Linux preview needs X11/GLX (including XWayland / `xvfb-run`). Pure Wayland and musl/Alpine are not supported. See [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md).
- ARM64 packages have not been tested on real hardware yet. Whenever the attachments include an arm64 package, `release_notes.py` notes in the release notes that it has not been tested on real hardware, plus the known limits (Linux arm64 Cubism Core is experimental, and on a GPU that only supports OpenGL 3.1 the canvas may be unable to draw). After real-hardware verification, delete that note.
- v1 does not do a fragile GUI smoke test. On Linux, the check is that the `.deb` exists and is non-empty.
- Windows packages are built twice. The second pass adds `-Ppsd2live.ffmpegDir=<directory>` and places a pinned Gyan.dev ffmpeg essentials build (GPLv3, SHA-256 checked after download, and the encoders used by video and animation export are checked), together with its `LICENSE.txt` and `README.txt`, into the application's `resources/ffmpeg/`. When you upgrade ffmpeg, change `FFMPEG_URL` and `FFMPEG_SHA256` in the workflow at the same time. Linux packages do not bundle ffmpeg.
- The Windows installer is built by `packageExe` with Inno Setup 6 from the application image. The script is `packaging/windows/psd2live.iss`. `ISCC.exe` is taken, in order, from `-Ppsd2live.iscc`, the `ISCC` environment variable, and the default install directory of Inno Setup 6. The workflow installs Inno Setup with Chocolatey. Files are copied in place, unlike MSI, which first moves the old files into `Config.Msi`. The default is an install for all users (administrator required). You can also install for the current user only, from the dialog at startup or with `/CURRENTUSER`. An upgrade installs back into the directory that is already installed and reuses the install scope from that time. It deletes the previous version's `app` and `runtime` directories first, then copies, and files the user placed in the install directory are kept. Packages with and without ffmpeg share the same AppId and replace each other. If PSD2Live is running from the install directory before install, you are asked to close it and retry (a silent install aborts directly). 3.1.x and earlier are MSIs produced by jpackage: the installer finds them by their upgrade code, installs into their directory by default, and, before copying files, clears the install directory they recorded (3.0.0 and earlier empty that directory on uninstall) and deletes the files they installed, then silently uninstalls with `msiexec /x`. At that point the MSI only unregisters, and it does not have to move files into `Config.Msi` again. The uninstall log is written beside the Setup log (`PSD2Live MSI removal.log`). When the install is for the current user only and the old version was installed for all users, administrator permission is requested. Uninstall asks whether to delete user data as well (no by default; it runs the application's `--clear-user-data`, executed as the account that runs the uninstaller; a command-line uninstall can pass `/CLEARUSERDATA=1`). Every install and uninstall writes a log in `%TEMP%` (`Setup Log *.txt`).
- Packages that include Cubism are only for use within what the license allows. **Do not** redistribute the proprietary binaries on a public channel.

## License reminder

The repository's bridge code is GPL. Live2D Cubism Core / Framework / shaders are proprietary Live2D software. Obtain them yourself and follow their license. See [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md).

[Local SDK setup](CUBISM_SDK_SETUP.md) · [native scripts](../../../native/README.md)
