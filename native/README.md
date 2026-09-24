# Native Build Scripts

This directory contains build and packaging scripts for PSD2Live's optional native components.

## Cubism SDK Preview Bridge

The `live2d_renderer/` wrapper provides optional native Cubism SDK preview support. Official Live2D SDK files are **never included** in this repository—developers must obtain and build against a local SDK install.

### Build Scripts

#### Windows
```powershell
# Build and deploy native renderer DLL
.\native\build_live2d_renderer.bat -Clean -Deploy
```

Requires:
- Visual Studio 2022 C++ / MSVC 143
- CMake 3.16+
- Local Cubism SDK for Native (5-r.5 layout)

#### Linux
```bash
# Build and deploy native renderer shared library
./native/build_live2d_renderer.sh --clean --deploy
```

Requires:
- Linux x86_64 with X11/GLX, including XWayland or `xvfb-run`
- Not supported: pure Wayland without XWayland, aarch64, or musl/Alpine systems
- GCC or Clang with C++14
- CMake 3.16+
- OpenGL and X11 development libraries
- Local Cubism SDK for Native (5-r.5 layout)

Set `CUBISM_SDK_ROOT` to your extracted SDK directory before running.

### Linux Packaging Helper

```bash
# Package without Cubism SDK (for public distribution)
./native/package_linux.sh

# Package with local Cubism SDK (PERSONAL USE ONLY)
./native/package_linux.sh --include-local-cubism
```

Default Gradle jars/distributions **exclude** `src/main/resources/cubism/**`. Opt in with `-Ppsd2live.includeCubism=true` (or `PSD2LIVE_INCLUDE_CUBISM=true`). The `--include-local-cubism` flag passes that property and also copies binaries beside the jar, with the launcher setting `CUBISM_SDK_PATH`.

**⚠️ IMPORTANT**: Packages created with `--include-local-cubism` contain proprietary Live2D binaries and **must not be redistributed publicly**. Public distributions should omit Cubism binaries and instruct users to build them locally.

## Documentation

Detailed setup instructions in multiple languages:
- [English](../docs/en/guide/CUBISM_SDK_SETUP.md)
- [简体中文](../docs/zh/guide/CUBISM_SDK_SETUP.md)
- [日本語](../docs/ja/guide/CUBISM_SDK_SETUP.md)

CI and Cubism-inclusive release packaging (GitHub Actions):
- [English](../docs/en/guide/CUBISM_CI_RELEASE.md)
- [简体中文](../docs/zh/guide/CUBISM_CI_RELEASE.md)

## License Compliance

This directory contains only open-source wrapper code under GPL v3. The Live2D Cubism SDK (Core, Framework, shaders) is proprietary software owned by Live2D Inc. and governed by their Proprietary Software License. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) for details.
