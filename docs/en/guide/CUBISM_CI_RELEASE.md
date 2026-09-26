# Cubism CI and release workflows

[Docs](../../README.md) · [Cubism SDK local setup](CUBISM_SDK_SETUP.md)

This page describes the two GitHub Actions workflows: public tests (no SDK) and optional Cubism preview packaging. It does not replace the Live2D license or grant redistribution rights for proprietary components.

## The two workflows

| Workflow | File | Purpose |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | On `push` / `pull_request` to `master` (and `main`), runs `./gradlew test` on Ubuntu and Windows. Does not download the Cubism SDK and does not set `PSD2LIVE_INCLUDE_CUBISM`. |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | On manual `workflow_dispatch` or a `v*` tag push, privately fetches the SDK, builds the native bridges, packages Windows/Linux Cubism builds, and can publish a GitHub Release. |

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

## How to run workflow_dispatch for 1.3.0 (example)

Prerequisites: SDK zip uploaded to the private repo; variables/secrets above and the `release-cubism` environment configured.

1. Actions → **Release Cubism** → **Run workflow**.
2. Set `version` to `1.3.0` (no leading `v`).
3. Leave `create_github_release` enabled unless you only want artifacts.
4. Approve the environment gate, then wait for Windows and Linux jobs.
5. When publishing is enabled, tag `v1.3.0` is created or updated as a **formal** (non-prerelease) GitHub Release with:
   - `PSD2Live-1.3.0-windows-x86_64-portable.zip`
   - `PSD2Live-1.3.0.exe`
   - `PSD2Live-1.3.0.msi`
   - `PSD2Live-1.3.0-linux-amd64.deb`

You can instead push tag `v1.3.0` to trigger the same workflow. Prefer one entry path to avoid a full double matrix.

The workflow does **not** bump `packageVersion` in `build.gradle.kts`; the version is used for artifact names and the Release title only. Bump `version` / `packageVersion` and product-facing strings in the repo before shipping.

## Artifacts and platform limits

- Linux preview needs X11/GLX (including XWayland / `xvfb-run`). Pure Wayland, aarch64, and musl/Alpine are unsupported. See [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md).
- v1 skips fragile GUI smoke tests; Linux checks that the `.deb` exists and is non-empty.
- Cubism-inclusive packages are only for uses allowed by your license; **do not** redistribute proprietary binaries on public channels.

## License reminder

Bridge code in this repository is GPL. Live2D Cubism Core / Framework / shaders are proprietary; obtain them yourself and follow Live2D’s terms. See [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md).

[Local SDK setup](CUBISM_SDK_SETUP.md) · [native scripts](../../../native/README.md)
