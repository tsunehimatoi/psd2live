# live2d_renderer (optional native Cubism preview)

Open-source C++ wrapper that produces `live2d_renderer.dll` for PSD2Live's optional
official Cubism 5-r.5 offscreen preview. The wrapper source lives in this repository;
the Live2D Cubism Core / Framework / shaders are **not** redistributed and must be
supplied by the builder under the Live2D proprietary license.

## Requirements

- Windows x86-64
- CMake 3.16+
- Visual Studio 2022 C++ (MSVC toolset 143)
- Locally extracted **Cubism 5 SDK for Native** (e.g. `CubismSdkForNative-5-r.5`)

## Build

From the repository root (PowerShell):

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat
```

Or configure CMake yourself:

```powershell
cmake -G "Visual Studio 17 2022" -A x64 `
  -DCUBISM_SDK_ROOT="D:\path\to\CubismSdkForNative-5-r.5" `
  -B native/live2d_renderer/build `
  -S native/live2d_renderer
cmake --build native/live2d_renderer/build --config Release --target live2d_renderer
```

Artifacts:

```text
native/live2d_renderer/build/bin/Release/live2d_renderer.dll
native/live2d_renderer/build/bin/Release/FrameworkShaders/
```

Deploy into one of the paths documented in `docs/*/guide/CUBISM_SDK_SETUP.md`, or pass
`-Deploy` to the build script to copy into `src/main/resources/cubism/windows-x86_64/`
(gitignored).

Without this DLL, PSD2Live still runs with the built-in CPU software rasterizer.
