# Optional Cubism Native preview

[Docs](../../README.md) · [User guide](USER_GUIDE.md)

The built-in renderer and basic exports do not require the official SDK. This page configures the repository Windows x64 preview bridge for observing official runtime rendering and physics. It does not promise identical behavior for every editor feature or pixel.

## Requirements

Windows x64, CMake 3.16+, Visual Studio 2022 C++ / MSVC 143 and a local Cubism 5 SDK for Native. The build script uses the 5-r.5 layout and expects `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`.

## Build and deploy

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

Run from the repository root. `-Deploy` copies the DLL and shaders to the gitignored directory below. `-Clean` rebuilds without the old build cache.

```text
src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/
```

## Verify

Start the application, load a model and inspect the renderer status and logs. Missing or unloadable native resources use the built-in software renderer. A visible model alone does not prove the SDK was loaded.

## Other locations and troubleshooting

Alternatively use repository `cubism/windows-x86_64/`, `CUBISM_SDK_PATH`, `LIVE2D_SDK_PATH`, or JVM property `psd2live.cubism.path` pointing at deployed resources. These runtime paths differ from the SDK source root `CUBISM_SDK_ROOT` used for compilation.

Keep the DLL and FrameworkShaders together. VCRUNTIME / MSVCP failures can indicate an old /MD build; the current script uses /MT and static Core. Inspect with dumpbin /DEPENDENTS. Restart the launching process after changing environment variables.

Only open-source bridge code is supplied. Obtain the official Core, Framework and shaders separately and follow their license. This guide grants no rights to those components.

[Native build](../../../native/live2d_renderer/README.md) · [Build script](../../../native/build_live2d_renderer.bat) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)
