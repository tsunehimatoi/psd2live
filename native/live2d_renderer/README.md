# live2d_renderer (optional native Cubism preview)

Open-source C++ wrapper that produces `live2d_renderer.dll` for PSD2Live's optional
official Cubism 5-r.5 offscreen preview.

The wrapper sources live in this repository. The Live2D Cubism Core / Framework /
shaders are **not** redistributed and must be supplied by the builder under the
Live2D proprietary license.

## Dependency profile (important)

Release builds use **static MSVC CRT (`/MT`)** and link `Live2DCubismCore_MT.lib`.

The resulting DLL is expected to depend only on:

- `OPENGL32.dll`
- `KERNEL32.dll`
- `USER32.dll`
- `GDI32.dll`

It must **not** require `VCRUNTIME140.dll` / `MSVCP140.dll`. This matches the
historically validated DLL used with PSD2Live and avoids VC++ redistributable issues
when the host JVM process does not ship those runtimes next to the native library.

## Requirements

- Windows x86-64
- CMake 3.16+
- Visual Studio 2022 C++ (MSVC toolset 143)
- Locally extracted **Cubism 5 SDK for Native** (e.g. `CubismSdkForNative-5-r.5`)
  including `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`

## One-shot build + deploy

From the repository root (PowerShell):

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

`-Deploy` copies:

```text
src/main/resources/cubism/windows-x86_64/live2d_renderer.dll
src/main/resources/cubism/windows-x86_64/FrameworkShaders/   # 22 shaders from the SDK
```

That path is gitignored. Shaders are copied automatically at build time — no manual
shader extraction is required for the recommended flow.

## Manual CMake

Prefer the batch script. If configuring by hand, force static CRT and wipe any old `/MD` cache first:

```powershell
Remove-Item -Recurse -Force native/live2d_renderer/build -ErrorAction SilentlyContinue
cmake -G "Visual Studio 17 2022" -A x64 `
  -DCUBISM_SDK_ROOT="D:\path\to\CubismSdkForNative-5-r.5" `
  -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded `
  -B native/live2d_renderer/build `
  -S native/live2d_renderer
cmake --build native/live2d_renderer/build --config Release --target live2d_renderer
```

Artifacts:

```text
native/live2d_renderer/build/bin/Release/live2d_renderer.dll
native/live2d_renderer/build/bin/Release/FrameworkShaders/
```

Verify dependents with `dumpbin /DEPENDENTS` — expect only OPENGL32 / KERNEL32 / USER32 / GDI32.

## Without the DLL

PSD2Live still runs with the built-in CPU software rasterizer. Official Ground Truth
preview is optional.
