# Live2D Cubism SDK Configuration Guide

[中文](../../zh/guide/CUBISM_SDK_SETUP.md) | [日本語](../../ja/guide/CUBISM_SDK_SETUP.md)

This guide explains how to configure the official Live2D® Cubism® Native SDK runtime for PSD2Live to enable **100% faithful rendering and physical dynamics parity with the official Cubism runtime (Consistency & Ground Truth)**.

---

## Table of Contents

- [Core Value: Consistency Over Pure Acceleration](#core-value-consistency-over-pure-acceleration)
- [Legal Notice & Non-Distribution Policy](#legal-notice--non-distribution-policy)
- [Out-of-the-Box Usage (No SDK Required)](#out-of-the-box-usage-no-sdk-required)
- [Recommended Flow: One-Shot Build & Deploy](#recommended-flow-one-shot-build--deploy)
- [Runtime Layout & Dependency Profile](#runtime-layout--dependency-profile)
- [Alternate Deploy Locations](#alternate-deploy-locations)
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
> 2. **Repository Compliance**: The PSD2Live source repository **does not include, embed, or distribute** official Live2D proprietary SDK library binaries, dynamic link libraries (`.dll`), or copyrighted shader source code. This repository only ships the open-source wrapper under [`native/live2d_renderer/`](../../../native/live2d_renderer/); you link it locally against an SDK you obtained yourself.
> 3. **Trademarks**: `Live2D`, `Cubism`, `.cmo3`, `.moc3`, and related marks are registered trademarks or trademarks of Live2D Inc., referenced herein solely for standard format interoperability and technical specifications.

---

## Out-of-the-Box Usage (No SDK Required)

**PSD2Live operates completely independently without the official SDK:**

- **Full Pipeline Independence**: PSD layer semantic classification, connected-component splitting, adaptive Delaunay triangulation, 9-axis face deformer assembly, eye jelly / pendulum physics dynamics, idle motion generation, and full export of editable `.cmo3` projects and runtime `.moc3` file families are **100% powered by the built-in pipeline and work completely out of the box**.
- **Built-in Software Rasterizer**: When the official SDK is not configured or when running on macOS/Linux, the GUI preview viewport automatically falls back to an internal pure CPU software rasterizer, supporting real-time mesh deformation and parameter slider inspection.

---

## Recommended Flow: One-Shot Build & Deploy

Goal: build a `live2d_renderer.dll` with the **same dependency profile as the historically validated binary** (static MSVC CRT `/MT`, no VC++ redistributable), then deploy it with shaders.

### Prerequisites

| Item | Requirement |
| :--- | :--- |
| OS | Windows x86-64 |
| Toolchain | CMake 3.16+, Visual Studio 2022 C++ (MSVC toolset **143**) |
| Official SDK | Download and extract **Cubism 5 SDK for Native** (e.g. `CubismSdkForNative-5-r.5`) |
| Core library | `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib` (paired with `/MT`; **do not** use `*_MD.lib`) |

SDK download: [Live2D Cubism SDK for Native](https://www.live2d.com/en/sdk/download/native/) (proprietary license agreement required).

### One command

From the repository root (PowerShell):

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

The script will:

1. Configure and build with **`/MT` + `Live2DCubismCore_MT.lib`**
2. Copy the 22 official OpenGL shaders next to the DLL as `FrameworkShaders/`
3. With `-Deploy`, copy both into `src/main/resources/cubism/windows-x86_64/` (gitignored)

> [!IMPORTANT]
> If you previously configured CMake with `/MD` or `Core_MD.lib`, you **must** pass `-Clean` (or delete `native/live2d_renderer/build`). Otherwise the cache keeps producing a DLL that depends on `VCRUNTIME140.dll`.

See [`native/live2d_renderer/README.md`](../../../native/live2d_renderer/README.md) for more build details.

---

## Runtime Layout & Dependency Profile

```text
cubism/windows-x86_64/          # or src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/           # 22 official shaders (copied at build time; never commit)
```

**A correct DLL must depend only on:**

- `OPENGL32.dll`
- `KERNEL32.dll`
- `USER32.dll`
- `GDI32.dll`

It must **not** list `VCRUNTIME140.dll`, `MSVCP140.dll`, or `api-ms-win-crt-*.dll`. If it does, rebuild with `-Clean`.

Self-check: `dumpbin /DEPENDENTS live2d_renderer.dll`.

---

## Alternate Deploy Locations

PSD2Live searches in this order (any one is enough; the recommended flow writes method 1):

1. **Project resources (recommended)**: `src/main/resources/cubism/windows-x86_64/` (`-Deploy` default, gitignored)
2. **Repo root**: `cubism/windows-x86_64/` (also gitignored)
3. **External path**: env `CUBISM_SDK_PATH` / `LIVE2D_SDK_PATH`, or JVM `-Dpsd2live.cubism.path=...`, pointing at a directory that contains `live2d_renderer.dll` and `FrameworkShaders/`

---

## Verification & Runtime Status

```powershell
.\run-gui.bat
```

Load any model or PSD and check the status pill in the lower-left of Preview:

- **`Native Cubism (Live Physics)`** / **`Native Cubism`**: official runtime loaded
- **`Software Rasterizer`**: runtime missing or non-Windows; CPU fallback (core features intact)
- Missing shaders/resources surface as red diagnostics in the upper-left viewport

---

## Frequently Asked Questions (FAQ)

### Q1: Does omitting the SDK affect exporting `.cmo3` / `.moc3`?
No for core generation and export. The SDK's value is Ground Truth preview consistency, not export itself.

### Q2: Error `Missing Cubism SDK 5-r.5 runtime resource: live2d_renderer.dll`
1. Did you run `-Deploy` or place files in one of the three locations?
2. Are you on 64-bit Windows?
3. For an external path, did the env var reach the current terminal/IDE?

### Q3: Runtime asks for `VCRUNTIME140.dll` / `MSVCP140.dll`?
The DLL was built with dynamic CRT (`/MD`). Use the current `native/build_live2d_renderer.bat` with `-Clean` so the artifact only depends on system OpenGL/GDI.

### Q4: Why does Git ignore / show deletes under `src/main/resources/cubism/`?
Expected for license compliance. Local files remain on disk.

### Q5: Can I copy shaders manually without the build script?
Yes — from `Framework/.../Shaders/Standard/` into deploy `FrameworkShaders/`. The recommended flow already does this automatically.
