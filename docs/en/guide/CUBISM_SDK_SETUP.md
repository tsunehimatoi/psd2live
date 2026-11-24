<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/CUBISM_SDK_SETUP.md) · **English** · [한국어](../../ko/guide/CUBISM_SDK_SETUP.md) · [日本語](../../ja/guide/CUBISM_SDK_SETUP.md)

# Optional Cubism native preview

[Documentation](../README.md) · [CI and release](CUBISM_CI_RELEASE.md)

Built-in rendering and basic export do not need the official SDK. This page only configures this repository's native preview bridge, so you can check rendering and physics against the official runtime. It does not promise agreement with every editor feature or every pixel.

## Prepare

### Windows x64
- CMake 3.16+
- Visual Studio 2022 C++ / MSVC 143
- A local Cubism 5 SDK for Native (5-r.5 directory layout)
- Required: `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`

### Linux x86_64 / arm64
- Supported platforms: Linux x86_64 and arm64 with X11/GLX, including XWayland and `xvfb-run`. arm64 uses the experimental Cubism Core in the SDK
- Unsupported: pure Wayland without XWayland, or musl-based systems such as Alpine
- CMake 3.16+
- GCC or Clang, with C++14
- OpenGL / GLX development packages. For example, Debian/Ubuntu: `libgl1-mesa-dev` and `libglx-dev` (or `libglx-mesa-dev`). Fedora: `mesa-libGL-devel`
- X11 development libraries (`libx11-dev` / `libX11-devel`)
- At runtime, a usable X11 `DISPLAY` is required (a desktop session, or `xvfb-run` in a headless environment)
- A local Cubism 5 SDK for Native (5-r.5 directory layout)
- Required: `Core/lib/linux/x86_64/libLive2DCubismCore.a` (arm64 is `Core/lib/experimental/linux/arm64/libLive2DCubismCore.a`)

## Build and deploy

### Windows

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

Run this from the repository root. `-Deploy` copies the DLL and shaders into the gitignored directory below. `-Clean` clears the old build cache and rebuilds.

```text
src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/
```

### Linux

```bash
export CUBISM_SDK_ROOT=/path/to/CubismSdkForNative-5-r.5
./native/build_live2d_renderer.sh --clean --deploy
```

Run this from the repository root. `--deploy` copies the shared library and shaders into the gitignored directory below. `--clean` clears the old build cache and rebuilds.

```text
src/main/resources/cubism/linux-x86_64/   （arm64 为 linux-arm64/）
├── liblive2d_renderer.so
└── FrameworkShaders/
```

## Check

Start the application and open a model. Check the preview's renderer status and the log. When the native resources are missing or cannot be loaded, preview switches to the PSD2Live runtime (p2lrt), and the log records why Cubism is unavailable. Only when the runtime library is also unavailable does it fall back to the edit-canvas drawing. Read the specific error. A picture appearing on screen is not enough to conclude that the SDK is enabled.

## Other paths and failures

You can also place the files in the repository at `cubism/windows-x86_64/` (or `cubism/linux-x86_64/`), or point `CUBISM_SDK_PATH`, `LIVE2D_SDK_PATH`, or the JVM property `psd2live.cubism.path` at the deploy directory. Those are runtime paths. `CUBISM_SDK_ROOT`, used for the build, is the SDK source root.

The native library and FrameworkShaders must be a matching pair.

### Windows
If VCRUNTIME / MSVCP is missing, check whether a /MD build was used by mistake. The current script uses /MT and the static Core. You can check with `dumpbin /DEPENDENTS`.

### Linux
Check dependencies with `ldd liblive2d_renderer.so`. Expected system libraries: `libGL.so`, `libGLX.so` (or Mesa GLX), `libX11.so`, `libpthread.so`, and `libdl.so`.

Offscreen preview creates a GLX context and needs a valid X11 `DISPLAY`. In a headless environment, install Xvfb, for example `xvfb-run -a ./gradlew run` (or `xvfb-run -a java -jar …`). When `DISPLAY` is missing, native initialization fails and preview switches to the PSD2Live runtime (only when the runtime library is also unavailable does it fall back to the edit-canvas drawing).

After you change environment variables, restart the process that launches the application.

The repository provides only the open-source bridge code. It does not include the official Core, Framework, or shaders. Obtain the SDK yourself and follow its license. This page is not a license grant.

Automated builds and the release flow that includes Cubism are in [CUBISM_CI_RELEASE](CUBISM_CI_RELEASE.md) (on a public repository, do not attach the SDK zip to a public Release).

[Native build](../../../native/live2d_renderer/README.md) · [Build scripts](../../../native/) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)
