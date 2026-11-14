# Third-party Notices

[Documentation index](docs/README.md) · [Example artwork notices](examples/readme.md)

PSD2Live is licensed under GNU GPL version 3. See [LICENSE](LICENSE).

## Umamo

This project integrates portions of the Umamo project (`format`, `runtime`, `interop`, `render`, and `edit` core modules). Umamo is authored by Alexia E. Smith and contributors, licensed under GNU GPL version 3.


## Stretchy Studio

Copyright (c) 2026 Nguyen Phan. Licensed under the MIT License.

PSD2Live independently implements concepts inspired by See-Through semantic organization, connected-component layer splitting, image analysis, atlas packing, and adaptive mesh generation. It does not embed Stretchy Studio's React/WebGL user interface.

## Model Context Protocol Kotlin SDK and Ktor

The built-in local Agent bridge uses the official Model Context Protocol Kotlin SDK, maintained by the Model Context Protocol project in collaboration with JetBrains. New SDK contributions are licensed under Apache-2.0 and existing portions under MIT. The HTTP transport is provided by Ktor under the Apache-2.0 license.

## Runtime decompression crates

The PSD2Live runtime (`runtime/`, and the `p2l_runtime` library and WebAssembly module built from it) reads compressed `.p2lrt` chunks with [miniz_oxide](https://github.com/Frommi/miniz_oxide) (MIT, Zlib or Apache-2.0) and its dependency adler2 (0BSD, MIT or Apache-2.0), and with [ruzstd](https://github.com/KillingSpark/zstd-rs) (MIT) and its dependency twox-hash (MIT).

## aircompressor

The `.p2lrt` exporter (`:targets:runtime`) writes zstd-compressed chunks with [aircompressor](https://github.com/airlift/aircompressor) (Apache-2.0), a pure-Java implementation.

## FFmpeg

The Windows packages whose names end in `-ffmpeg` include `ffmpeg.exe` from the [Gyan.dev](https://www.gyan.dev/ffmpeg/builds/) essentials build (via [GyanD/codexffmpeg](https://github.com/GyanD/codexffmpeg/releases)), licensed under GNU GPL version 3. It sits in the app's `resources/ffmpeg/` with that build's `LICENSE.txt` and `README.txt`, which names the FFmpeg source commit and the versions of the libraries built in. PSD2Live runs it as a separate program to encode video and animated images; the other packages do not include it.

## MHR joint reference cages

`src/main/resources/skinning/mhr-joints.tsv` contains modified, sampled sagittal surface cages
derived from Meta's [MHR v1.0.1 model assets](https://github.com/facebookresearch/MHR/releases/tag/v1.0.1).
The model and its assets are licensed under Apache-2.0; the asset license is reproduced in
`licenses/MHR.txt`. The original model is not distributed with PSD2Live.
`tools/research/extract_mhr_joint_templates.py` records the sampling procedure. These small
pose references guide local knee and elbow deformation; they do not constitute a biomechanical model.

## Live2D Cubism

`Live2D`, `Cubism`, `.cmo3`, `.moc3`, and associated schema identifiers are trademarks or registered trademarks of Live2D Inc., used herein solely for format specification and interoperability purposes. This project is not affiliated with, endorsed by, or sponsored by Live2D Inc., and strictly complies with the Live2D Proprietary Software License: **it does not embed, include, or redistribute official proprietary Live2D Cubism SDK binaries, headers, or shader sources**.

An optional open-source C++ wrapper under `native/live2d_renderer/` can produce a native renderer library (`live2d_renderer.dll` on Windows, `liblive2d_renderer.so` on Linux) when linked against a **user-provided** local Cubism SDK for Native install. The wrapper sources are part of this repository; the proprietary Core library, Framework, and shaders must be obtained directly from Live2D and are never committed here.

For setup instructions, see:
- [Live2D SDK 配置指南 (中文)](docs/zh/guide/CUBISM_SDK_SETUP.md)
