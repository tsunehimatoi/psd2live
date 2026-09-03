# Live2D Cubism SDK Configuration Guide

[中文](../zh/CUBISM_SDK_SETUP.md) | [日本語](../ja/CUBISM_SDK_SETUP.md)

This guide provides instructions on how to configure the official Live2D® Cubism® Native SDK runtime for PSD2Live to achieve **100% faithful rendering and physical dynamics parity with the official Cubism runtime (Consistency & Ground Truth)**.

---

## Table of Contents

- [Core Value: Consistency Over Pure Acceleration](#core-value-consistency-over-pure-acceleration)
- [Legal Notice & Non-Distribution Policy](#legal-notice--non-distribution-policy)
- [Out-of-the-Box Usage (No SDK Required)](#out-of-the-box-usage-no-sdk-required)
- [Required Components & File List](#required-components--file-list)
- [Setup Instructions](#setup-instructions)
  - [Step 1: Obtain the Official SDK](#step-1-obtain-the-official-sdk)
  - [Step 2: Extract OpenGL Shaders](#step-2-extract-opengl-shaders)
  - [Step 3: Obtain or Build the Native Renderer DLL](#step-3-obtain-or-build-the-native-renderer-dll)
  - [Step 4: Deploy to Target Path (3 Methods)](#step-4-deploy-to-target-path-3-methods)
- [Verification & Runtime Status](#verification--runtime-status)
- [Frequently Asked Questions (FAQ)](#frequently-asked-questions-faq)

---

## Core Value: Consistency Over Pure Acceleration

> [!NOTE]
> **The primary purpose of configuring the official SDK is NOT performance acceleration, but strict visual and behavioral Consistency with official production environments.**

In the Live2D asset pipeline, rendering and physics calculations are governed by proprietary runtime rules:
1. **Pixel-Perfect Rendering & Masking Parity**:
   - The official Framework shaders define exact formulas for Premultiplied Alpha, color blending modes (Multiply, Screen, Add, ColorBlend), and dedicated offscreen FBO clipping mask / inverted mask sampling.
   - Pure CPU software rasterization inevitably incurs minor nuances in interpolation or color blending. The official Native SDK guarantees that every stroke, clipping boundary, and blend mode in PSD2Live matches **Cubism Viewer** and live game engines bit-for-bit, eliminating edge artifacts, black fringes, or alpha bleed.
2. **Physics & Dynamics Consistency**:
   - Hair pendulums, chest breathing, and eye jelly bounce are evaluated by the official `Live2D_Update` physics subsystem.
   - Using the official runtime guarantees that the exported `physics3.json` exhibits the exact same damping, gravity response, and swing amplitude in production as seen during authoring.
3. **Authoritative Ground Truth Verification**:
   - The official SDK acts as the gold standard to verify that the generated `.moc3`, `.model3.json`, and motions are fully compatible with official runtime specifications before shipping.
   - *(Hardware acceleration and fluid framerates are welcome byproducts; rendering fidelity and behavioral consistency are the true objectives.)*

---

## Legal Notice & Non-Distribution Policy

> [!IMPORTANT]
> **This project strictly complies with open source licensing and Live2D's Proprietary License terms:**
> 1. **Non-Distribution Policy**: In accordance with Live2D Inc.'s *Live2D Proprietary Software License*, the Live2D Cubism Core native library and SDK official binary assets are proprietary property and **must NOT be redistributed in any form by third parties**.
> 2. **Repository Compliance**: The PSD2Live source repository **does not include, embed, or distribute** official Live2D proprietary SDK library binaries, dynamic link libraries (`.dll`), or copyrighted shader source code.
> 3. **Trademarks**: `Live2D`, `Cubism`, `.cmo3`, `.moc3`, and related marks are registered trademarks or trademarks of Live2D Inc., referenced herein solely for standard format interoperability and technical specifications.

---

## Out-of-the-Box Usage (No SDK Required)

**PSD2Live operates completely independently without the official SDK:**

- **Full Pipeline Independence**: PSD layer semantic classification, connected-component splitting, adaptive Delaunay triangulation, 9-axis face deformer assembly, eye jelly / pendulum physics dynamics, idle motion generation, and full export of editable `.cmo3` projects and runtime `.moc3` file families are **100% powered by the built-in pipeline and work completely out of the box**.
- **Built-in Software Rasterizer**: When the official SDK is not configured or when running on macOS/Linux, the GUI preview viewport automatically falls back to an internal pure CPU software rasterizer, supporting real-time mesh deformation and parameter slider inspection.

---

## Required Components & File List

To enable official consistency preview (Architecture: `Windows x86-64`):

```text
cubism/
└── windows-x86_64/
    ├── live2d_renderer.dll                     # Native renderer compiled with Live2D Cubism Core 5-r.5
    └── FrameworkShaders/                       # Official Framework OpenGL standard shaders (22 files)
        ├── FragShaderSrc.frag
        ├── FragShaderSrcAlphaBlend.frag
        ├── FragShaderSrcBlend.frag
        ├── FragShaderSrcColorBlend.frag
        ├── FragShaderSrcCopy.frag
        ├── FragShaderSrcMask.frag
        ├── FragShaderSrcMaskBlend.frag
        ├── FragShaderSrcMaskInverted.frag
        ├── FragShaderSrcMaskInvertedBlend.frag
        ├── FragShaderSrcMaskInvertedPremultipliedAlpha.frag
        ├── FragShaderSrcMaskInvertedPremultipliedAlphaBlend.frag
        ├── FragShaderSrcMaskPremultipliedAlpha.frag
        ├── FragShaderSrcMaskPremultipliedAlphaBlend.frag
        ├── FragShaderSrcPremultipliedAlpha.frag
        ├── FragShaderSrcPremultipliedAlphaBlend.frag
        ├── FragShaderSrcSetupMask.frag
        ├── VertShaderSrc.vert
        ├── VertShaderSrcBlend.vert
        ├── VertShaderSrcCopy.vert
        ├── VertShaderSrcMasked.vert
        ├── VertShaderSrcMaskedBlend.vert
        └── VertShaderSrcSetupMask.vert
```

---

## Setup Instructions

### Step 1: Obtain the Official SDK

1. Visit the Live2D SDK Download Center: [Live2D Cubism SDK for Native Download](https://www.live2d.com/en/sdk/download/native/).
2. Review and agree to the *Live2D Proprietary Software License Agreement*.
3. Download the **Cubism 5 SDK for Native** archive (e.g. `CubismSdkForNative-5-r.5.zip`) and extract it locally.

### Step 2: Extract OpenGL Shaders

1. Open the extracted SDK folder and navigate to:
   ```text
   CubismSdkForNative-5-r.5/Framework/src/Rendering/OpenGL/Shaders/Standard/
   ```
2. This directory contains 16 `.frag` fragment shaders and 6 `.vert` vertex shaders.
3. Copy all 22 shader files into a `FrameworkShaders/` folder in your target deployment directory.

### Step 3: Obtain or Build the Native Renderer DLL

`live2d_renderer.dll` is a lightweight native wrapper that statically links against `Live2DCubismCore_MD.lib` and the Native Framework from the SDK.

If building from source (requires CMake 3.16+ and Visual Studio 2022 MSVC toolchain):
1. Verify `Core/lib/windows/x86_64/143/Live2DCubismCore_MD.lib` exists in your extracted SDK.
2. Verify `Samples/OpenGL/thirdParty/glew` and `stb` exist.
3. Run CMake build:
   ```powershell
   cmake -G "Visual Studio 17 2022" -A x64 -B build
   cmake --build build --config Release --target live2d_renderer
   ```
4. Copy the resulting `live2d_renderer.dll` to your target directory.

### Step 4: Deploy to Target Path (3 Methods)

PSD2Live automatically searches for runtime files using the following priorities:

#### Method 1: Deploy to Project Internal Resources (Recommended)
Place `live2d_renderer.dll` and the `FrameworkShaders` folder into:
```text
psd2live/src/main/resources/cubism/windows-x86_64/
```
> [!NOTE]
> This path is ignored in `.gitignore` and exists only on your local machine; **it will never be committed to Git**. When executing Gradle builds or launch scripts, Gradle bundles it into the runtime classpath.

#### Method 2: Deploy to Project Root Directory
Create a `cubism` directory at the `psd2live` root:
```text
psd2live/cubism/windows-x86_64/
```
Place `live2d_renderer.dll` and `FrameworkShaders/` inside. (This path is also ignored by `.gitignore`).

#### Method 3: Specify via Environment Variable or JVM Property
If you maintain the binaries in an external tools directory (e.g. `D:\SDK\live2d_cubism_runtime`):
- **Environment Variable**: Set `CUBISM_SDK_PATH` or `LIVE2D_SDK_PATH` pointing to that directory.
  ```powershell
  [System.Environment]::SetEnvironmentVariable("CUBISM_SDK_PATH", "D:\SDK\live2d_cubism_runtime", "User")
  ```
- **JVM Argument**: Add `-Dpsd2live.cubism.path="D:\SDK\live2d_cubism_runtime"` when starting the JVM.

---

## Verification & Runtime Status

Launch the application:
```powershell
.\run-gui.bat
```

Load any model or PSD and inspect the status pill in the lower-left corner of the **Preview** viewport:
- **`Native Cubism (Live Physics)`** or **`Native Cubism`**: Indicates official Native SDK loaded successfully and GPU offscreen rendering is active.
- **`Software Rasterizer`**: Indicates SDK was not found or running on a non-Windows OS; the app has gracefully fallen back to CPU rendering without loss of core functionality.
- If any component fails to load (e.g. missing shaders), a diagnostic message is displayed in red in the upper-left viewport corner.

---

## Frequently Asked Questions (FAQ)

### Q1: Does omitting the SDK affect exporting `.cmo3` and `.moc3`?
**Not at all for core generation and export, but the official SDK provides authoritative ground-truth consistency.** All geometry generation, deformer hierarchy assembly, and file formatting are implemented in pure Kotlin/Java code. The core value of configuring the SDK lies in guaranteeing that clipping mask transparencies, premultiplied alpha blending, and physics dynamics match official game client runtimes with 100% fidelity before export.

### Q2: Error `Missing Cubism SDK 5-r.5 runtime resource: live2d_renderer.dll`
Check that:
1. `live2d_renderer.dll` is placed in one of the three supported locations;
2. Your operating system is Windows 64-bit;
3. If using Method 3, verify that `CUBISM_SDK_PATH` is correctly exported (restart your terminal/IDE after setting).

### Q3: Why does Git show `src/main/resources/cubism/` as deleted?
This is intentional for open source compliance. Proprietary Live2D binaries have been removed from git tracking and added to `.gitignore`. Your local files remain intact on disk.

