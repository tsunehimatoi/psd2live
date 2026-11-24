<!-- lang: zh en ko ja -->
> [中文](../README.md) · **English** · [한국어](../ko/README.md) · [日本語](../ja/README.md)

# Documentation

[Project home](../../README.en.md) · [Changelog](CHANGELOG.md) · [Roadmap](ROADMAP.md) · [Capability record](STATUS.md)

To learn the operations, start with **Tutorials** on the in-app Help page (`F1`): 19 lessons for beginners, 14 lessons for people with Cubism experience. The documents below are for looking up operations again and for the core formats and interfaces. Historical design drafts and process notes are archived (see the repository commit history, or the local `local/archive/`).

## Directory layout

Files in `en/`, `ja/`, and `ko/` correspond one-to-one with `zh/`. Only Chinese is expanded below. The other three languages use the same paths.

```text
docs/
├─ README.md            # 中文文档总索引（本文件）
├─ branding/            # 应用图标资源
├─ imgs/                # README 与图文教程截图（WebP）
├─ zh/                  # 中文（源文）
│  ├─ CHANGELOG.md      # 版本更新日志
│  ├─ ROADMAP.md        # 后续规划
│  ├─ STATUS.md         # 真实任务能力实测记录
│  ├─ guide/            # 使用指南、开发与构建文档
│  ├─ spec/             # 格式规范与核心架构契约
│  └─ agent/            # MCP 接口与 Agent 验收说明
├─ en/                  # English
├─ ja/                  # 日本語
└─ ko/                  # 한국어
```

## User guides

| Document | Contents |
| --- | --- |
| [Changelog](CHANGELOG.md) | Changes a user can notice in each version |
| [Quick reference](guide/USER_GUIDE.md) | The text version of the in-app tutorials, common shortcuts, workspaces, and the texture workspace |
| [Canvas editing](guide/CANVAS_EDITOR.md) | The mode menu, deformation, mesh topology, Glue, weights, and paint sessions |
| [Materials and layout](guide/LAYOUT.md) | The material library, place first then generate, regenerate, and semantic tags |
| [Depth-split tutorial](guide/DEPTH_SPLIT.md) | Front and back occlusion for a collar and neck, and for hair and shoulder ornaments: copy, glue, and eraser cleanup |
| [Skeleton and poses](guide/SKELETON.md) | Skeleton inference, binding, IK, preset motions, and the baked structure |
| [Swing generation](guide/SWING.md) | Left-right / up-down swing, canvas handles, pendulums, and baking |
| [Physics](guide/PHYSICS.md) | Where physics groups come from, the pendulum canvas, inputs and outputs, presets, and export |
| [Simulation and baking](guide/SIMULATION.md) | 2D simulation of cloth / hair, vertex-weight groups, and Glue roles, baked into parameters, keyforms, and pendulums |
| [Simulation tutorial](guide/SIMULATION_TUTORIAL.md) | Start from hair and clothing presets, adjust pin weights, bake again, and check the export |
| [Presets and modifiers](guide/MODIFIERS.md) | How to use presets and modifiers |
| [Deform paths (experimental)](guide/DEFORM_PATHS.md) | How paths are created, bound, and exported |
| [Texture upscaling](guide/TEXTURE_UPSCALE.md) | Local nunif setup, parameters, and how to check the result |
| [Algorithm plugins](guide/ALGORITHM_PLUGINS.md) | Developing and integrating algorithm plugins |

## Build and release

| Document | Contents |
| --- | --- |
| [Development and the command line](guide/DEVELOPMENT.md) | Running from source, CLI parameters, tests and packaging, and code structure |
| [Cubism native preview](guide/CUBISM_SDK_SETUP.md) | Optional official-SDK preview bridge: build and deploy |
| [CI and release](guide/CUBISM_CI_RELEASE.md) | GitHub Actions tests and the release flow that includes the SDK |

## Formats and core contracts

| Document | Contents |
| --- | --- |
| [PSD artwork and naming](spec/PSD_LAYER_SPEC.md) | Layering requirements, the layer-name table, and left/right and variants |
| [Deformers and parameters](spec/DEFORMER_AND_PARAMETER_SPEC.md) | The generated structure, coordinate conventions, and default parameters |
| [Project format v2](spec/PROJECT_FORMAT.md) | `.psd2live` archive layout, v1 migration, save, and validation |
| [Layout manifest](spec/LAYOUT_MANIFEST.md) | The JSON / zip interchange format for layered artwork |
| [Export targets](spec/EXPORT_TARGETS.md) | The neutral rig IR, each export format and its loss report, and module licenses |
| [Runtime](spec/RUNTIME.md) | The Rust runtime, the `.p2lrt` format, the web player, and the Godot node |
| [`.p2lrt` 2.0 format](spec/P2LRT_V2.md) | The chunked container, the core layer and the extension layer, and evolution rules |
| [Document layer](spec/DOCUMENT_LAYER.md) | The generator dependency graph, generated-result overrides, and caches |
| [Rig preset architecture](spec/RIG_PRESETS.md) | Parts, base algorithms, modules, and recipes |
| [Lite workspace and mobile (in design)](spec/LITE_WORKSPACE.md) | A zero-setup simplified interface, a touch wizard for depth split, MCP integration, and a staged mobile plan |

## Agent (MCP)

| Document | Contents |
| --- | --- |
| [MCP usage and API](agent/MCP_AUTHORING.md) | How to connect, public operations, atomic batches and dry runs, and call examples |
| [Agent design and acceptance](agent/AGENT_DESIGN.md) | Division of responsibility, tool-design principles, and task-acceptance steps |

## Other

- [Example artwork and output](../../examples/readme.md)
- [Native bridge build scripts](../../native/README.md)
- [Third-party component notices](../../THIRD_PARTY_NOTICES.md)

## Maintenance rules

1. The README covers only positioning, getting started, capability boundaries, and entry points. Step-by-step operations follow the in-app interactive tutorial.
2. Chinese is the source text. English, Japanese, and Korean are synchronized translations: documents live in `docs/en`, `docs/ja`, and `docs/ko`, and the project home pages are `README.en.md`, `README.ja.md`, and `README.ko.md` at the repository root. When the Chinese changes, change those three as well. The language switcher at the top of each page points at the same path in all four languages.
3. Do not add further scattered process design drafts or review drafts under `docs/` (process notes go in `local/`).
4. Descriptions must match the source. Tutorial order follows `InteractiveTutorial.kt`, shortcuts follow `ShortcutRegistry.kt`, the CLI follows `Main.kt`, and MCP tools follow the application-layer operation definitions and `AgentOperationTools.kt`.
