# Native preview bridge

This open-source C++ wrapper builds the native renderer for the optional Cubism preview on Windows x64 and Linux x86_64. It needs a locally supplied SDK; proprietary Core, Framework and shaders are not included here.

## Build from the repository root

### Windows
Requirements: CMake 3.16+, Visual Studio 2022 C++ / MSVC 143 and the Cubism SDK for Native 5-r.5 layout, including `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`.

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

The script builds the Release DLL and copies it with `FrameworkShaders/` into `src/main/resources/cubism/windows-x86_64/` (gitignored).

### Linux
Requirements: CMake 3.16+, GCC/Clang with C++14 support, OpenGL and X11 development libraries, and the Cubism SDK for Native 5-r.5 layout, including `Core/lib/linux/x86_64/libLive2DCubismCore.a`.

```bash
export CUBISM_SDK_ROOT=/path/to/CubismSdkForNative-5-r.5
./native/build_live2d_renderer.sh --clean --deploy
```

The script builds the Release shared library and copies it with `FrameworkShaders/` into `src/main/resources/cubism/linux-x86_64/` (gitignored).

## Manual CMake

### Windows
Use a fresh build directory when changing CRT settings:

```powershell
cmake -G "Visual Studio 17 2022" -A x64 -DCUBISM_SDK_ROOT="D:/path/to/CubismSdkForNative-5-r.5" -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded -B native/live2d_renderer/build-manual -S native/live2d_renderer
cmake --build native/live2d_renderer/build-manual --config Release --target live2d_renderer
```

Outputs are in `build-manual/bin/Release/`. Manual builds still need deployment of both the DLL and shaders.

### Linux
```bash
cmake -DCUBISM_SDK_ROOT=/path/to/CubismSdkForNative-5-r.5 -DCMAKE_BUILD_TYPE=Release -B native/live2d_renderer/build-manual -S native/live2d_renderer
cmake --build native/live2d_renderer/build-manual --config Release --target live2d_renderer
```

Outputs are in `build-manual/bin/`. Manual builds still need deployment of both the shared library and shaders.

## Runtime checks

### Windows
The build uses static MSVC CRT (`/MT`) and static Core. Check dependencies with `dumpbin /DEPENDENTS`; the expected direct dependencies are system OpenGL / Kernel / User / GDI libraries, not VCRUNTIME / MSVCP. If an older build used `/MD`, rebuild cleanly.

### Linux
Check dependencies with `ldd liblive2d_renderer.so`; the expected direct dependencies are system libraries: `libGL.so`, `libX11.so`, `libpthread.so`, `libdl.so`, and standard C/C++ runtime libraries.

### Live preview updates
The preview loads models from memory (`Live2D_CreateModelFromMemory`) and swaps changed texture pages in place (`Live2D_ReplaceTexture`), so an edit that only changes pixels keeps the live model, and other edits rebuild it from memory without writing a temporary directory. The application looks these symbols up when it loads the library: without `Live2D_ReplaceTexture` every change rebuilds from memory, and without `Live2D_CreateModelFromMemory` it keeps the file-based reload. Rebuild the bridge to get texture swaps.

### Drawing on the application's context
The application draws Cubism on its window's own OpenGL context: `Live2D_Init` initializes the framework on the context current on the calling thread, and `Live2D_Draw` draws into the framebuffer bound there; `Live2D_InitOffscreen` and `Live2D_RenderToRgba` (a private hidden context and a read back frame) are no longer used by the application. Bridges that export `Live2D_UsesCallerContext` keep in-memory models on the caller's context after `Live2D_Init`. Older bridges create every in-memory model on their hidden context, so with them the application loads models from temporary files and rebuilds the model on every edit; rebuild the bridge to keep in-memory reloads and texture swaps.

Check application renderer status and logs. Without usable native resources, the app uses its software renderer; basic model export does not require this native library.

[中文设置指南](../../docs/zh/guide/CUBISM_SDK_SETUP.md) · [CMake configuration](CMakeLists.txt)
