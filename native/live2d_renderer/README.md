# Native preview bridge

This open-source C++ wrapper builds `live2d_renderer.dll` for the optional Windows x64 Cubism preview. It needs a locally supplied SDK; proprietary Core, Framework and shaders are not included here.

## Build from the repository root

Requirements: CMake 3.16+, Visual Studio 2022 C++ / MSVC 143 and the Cubism SDK for Native 5-r.5 layout, including `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`.

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

The script builds the Release DLL and copies it with `FrameworkShaders/` into `src/main/resources/cubism/windows-x86_64/` (gitignored).

## Manual CMake

Use a fresh build directory when changing CRT settings:

```powershell
cmake -G "Visual Studio 17 2022" -A x64 -DCUBISM_SDK_ROOT="D:/path/to/CubismSdkForNative-5-r.5" -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded -B native/live2d_renderer/build-manual -S native/live2d_renderer
cmake --build native/live2d_renderer/build-manual --config Release --target live2d_renderer
```

Outputs are in `build-manual/bin/Release/`. Manual builds still need deployment of both the DLL and shaders.

## Runtime checks

The build uses static MSVC CRT (`/MT`) and static Core. Check dependencies with `dumpbin /DEPENDENTS`; the expected direct dependencies are system OpenGL / Kernel / User / GDI libraries, not VCRUNTIME / MSVCP. If an older build used `/MD`, rebuild cleanly.

Check application renderer status and logs. Without usable native resources, the app uses its software renderer; basic model export does not require this DLL.

[English setup](../../docs/en/guide/CUBISM_SDK_SETUP.md) · [中文](../../docs/zh/guide/CUBISM_SDK_SETUP.md) · [日本語](../../docs/ja/guide/CUBISM_SDK_SETUP.md) · [CMake configuration](CMakeLists.txt)
