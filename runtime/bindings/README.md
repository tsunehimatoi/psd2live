# Using the PSD2Live runtime from other languages and platforms

The runtime is one C ABI, `include/p2l_runtime.h`. Build the library with `cargo build --release` in `runtime/`
(`p2l_runtime.dll` / `libp2l_runtime.so` / `libp2l_runtime.dylib`, plus a static library), check
`P2L_ABI_COMPATIBLE(p2l_abi_version())` at start, and draw either yourself by the header's drawing rules or with the
runtime's software renderer, `p2l_render`.

## C and C++ (and Unreal)

`examples/c/render_example.c` hands the runtime its own allocator and log first, loads a rig through a shared model,
plays a clip for a second and writes the software renderer's frame to a BMP. `examples/c/build_example.bat` builds it as C and as C++ with MSVC (`/W4 /WX`) against
`target/release/p2l_runtime.dll.lib`:

```
examples\c\build_example.bat
examples\c\render_example_c.exe model.p2lrt frame.bmp 512
```

An Unreal Engine module uses the header and library the same way: add them as a third-party module
(`PublicIncludePaths`, `PublicAdditionalLibraries` for the import library, `RuntimeDependencies` for the DLL), then
either upload `p2l_render`'s pixels to a `UTexture2D` each frame or build a procedural mesh from `p2l_mesh_vertices`
and draw it with materials that follow the header's rules.

## .NET and Unity

`csharp/P2lNative.cs` declares every function for P/Invoke; it is generated from the header by
`csharp/generate.py`, and `cargo test` fails when it falls behind. `csharp/P2l.cs` adds string helpers and `P2lRig`,
a handle that frees itself. `csharp/Smoke.cs` resolves every entry point (`Marshal.Prelink`) and loads, plays and
renders a rig; with the Visual Studio Build Tools' Roslyn compiler:

```
csc /langversion:6 /platform:x64 /out:Smoke.exe csharp\P2lNative.cs csharp\P2l.cs csharp\Smoke.cs
Smoke.exe model.p2lrt
```

For Unity, copy `csharp/P2lNative.cs`, `csharp/P2l.cs` and `unity/P2LCharacter.cs` into `Assets/`, the runtime library
into `Assets/Plugins/` (per platform), rename the exported rig to `.bytes`, and add `P2LCharacter` to a quad: it plays
the rig and shows it as a texture drawn by `p2l_render` each frame (`Rig` gives the handle for parameters, gaze and
lip sync). `P2LCharacter.cs` has been type-checked against stubs of the Unity API it uses, not run in Unity; the bindings
beneath it have been compiled and run (C# 6, .NET Framework).

## Android

`build_android.sh` builds `libp2l_runtime.so` for arm64-v8a, armeabi-v7a and x86_64 with the NDK's clang into
`target/android/<abi>/` (Unity: `Assets/Plugins/Android/libs/<abi>/`; Android apps: `jniLibs/<abi>/`):

```
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
ANDROID_NDK_HOME=/path/to/ndk ./build_android.sh
```

iOS needs `cargo build --release --lib --target aarch64-apple-ios` on a Mac, which links the static library
(`libp2l_runtime.a`) into the app; it has not been built here.

## WebAssembly and Godot

The web player (`targets/web`) and the Godot node (`runtime/godot`) ship with PSD2Live. See the runtime spec: [中文](../../docs/zh/spec/RUNTIME.md) · [English](../../docs/en/spec/RUNTIME.md) · [한국어](../../docs/ko/spec/RUNTIME.md) · [日本語](../../docs/ja/spec/RUNTIME.md).
