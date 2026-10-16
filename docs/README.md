# 文档目录 · Documentation · ドキュメント

[中文首页](../README.md) · [English](en/README.md) · [日本語](ja/README.md) · [한국어](ko/README.md) · [更新日志](zh/CHANGELOG.md)

学习操作请先用程序内帮助页的 **教程**（`F1`）：零基础路线 19 课，Cubism 经验者路线 14 课。下列文档用于回看操作和查阅技术细节。中文为主要语言，标题链接到中文原文，「译文」列只列出已有的翻译。

For hands-on learning, open the **Tutorials** on the Help page (`F1`). Chinese is the primary documentation language: each title links to the Chinese page, and the Translations column lists the translations that exist. Every user guide, format reference and the MCP reference has an English version.

操作の習得にはヘルプページの **チュートリアル**（`F1`）を使ってください。主要言語は中国語です。タイトルは中国語版へのリンクで、「译文 · Translations」列に既存の翻訳を示します。

## 使用指南 · Guides

| 文档 | 内容 | 译文 · Translations |
| --- | --- | --- |
| [更新日志](zh/CHANGELOG.md) | 各版本中使用者能感知到的变化 | — |
| [操作速查](zh/guide/USER_GUIDE.md) | 与程序内教程对应的文字版、常用快捷键、工作区与纹理工作区 | [English](en/guide/USER_GUIDE.md) · [日本語](ja/guide/USER_GUIDE.md) |
| [画布编辑](zh/guide/CANVAS_EDITOR.md) | 模式菜单（物体 / 变形 / 编辑 / 模拟 / 骨骼 / 绘画 / 预览）、形变、网格拓扑、Glue、权重、绘画会话 | [English](en/guide/CANVAS_EDITOR.md) |
| [素材与 Layout](zh/guide/LAYOUT.md) | 素材库、先摆放再生成、重新生成、语义标签 | [English](en/guide/LAYOUT.md) |
| [前后分层图文教程](zh/guide/DEPTH_SPLIT.md) | 衣领与脖子、头发与肩部装饰的前后遮挡，复制、黏合与橡皮擦修边 | [English](en/guide/DEPTH_SPLIT.md) |
| [骨骼与姿态](zh/guide/SKELETON.md) | 骨架推断、绑定、IK、预设动作与烘焙结构 | [English](en/guide/SKELETON.md) |
| [摇摆生成](zh/guide/SWING.md) | 左右 / 上下摇摆、画布手柄、摆锤与烘焙 | [English](en/guide/SWING.md) |
| [物理](zh/guide/PHYSICS.md) | 物理组来源、摆锤画布、输入输出、预置与导出 | [English](en/guide/PHYSICS.md) |
| [模拟与烘焙](zh/guide/SIMULATION.md) | 布料 / 头发的 2D 模拟、顶点权重组、胶水角色，烘焙为参数、关键形与摆锤 | [English](en/guide/SIMULATION.md) |
| [模拟图文教程](zh/guide/SIMULATION_TUTORIAL.md) | 从头发与服装预设开始，调整固定点权重、重新烘焙并检查导出效果 | [English](en/guide/SIMULATION_TUTORIAL.md) |
| [变形路径（实验性）](zh/guide/DEFORM_PATHS.md) | 路径的创建、绑定与导出方式 | [English](en/guide/DEFORM_PATHS.md) |
| [纹理高清化](zh/guide/TEXTURE_UPSCALE.md) | 本地 nunif 配置、参数与效果检查 | [English](en/guide/TEXTURE_UPSCALE.md) |

## 构建与发布 · Build and release

开发中功能：[内部造型生成器与编辑器入口](zh/spec/INTERIOR_SURFACE.md) · [English](en/spec/INTERIOR_SURFACE.md)。

| 文档 | 内容 | 译文 · Translations |
| --- | --- | --- |
| [开发与命令行](zh/guide/DEVELOPMENT.md) | 源码运行、CLI 参数、测试与打包、代码结构 | [English](en/guide/DEVELOPMENT.md) · [日本語](ja/guide/DEVELOPMENT.md) |
| [Cubism Native 预览](zh/guide/CUBISM_SDK_SETUP.md) | 可选的官方 SDK 预览桥接构建与部署 | [English](en/guide/CUBISM_SDK_SETUP.md) · [日本語](ja/guide/CUBISM_SDK_SETUP.md) |
| [CI 与发行](zh/guide/CUBISM_CI_RELEASE.md) | GitHub Actions 测试与含 SDK 的发行流程 | [English](en/guide/CUBISM_CI_RELEASE.md) |

## 格式与参考 · Formats and reference

| 文档 | 内容 | 译文 · Translations |
| --- | --- | --- |
| [PSD 素材与命名](zh/spec/PSD_LAYER_SPEC.md) | 分层要求、图层名称表、左右与差分 | [English](en/spec/PSD_LAYER_SPEC.md) · [日本語](ja/spec/PSD_LAYER_SPEC.md) |
| [变形器与参数](zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) | 自动生成的结构、坐标约定、默认参数 | [English](en/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [日本語](ja/spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| [实现概览](zh/spec/IMPLEMENTATION_COMPARISON.md) | 流水线各阶段的实现与核心不变量 | [English](en/spec/IMPLEMENTATION_COMPARISON.md) · [日本語](ja/spec/IMPLEMENTATION_COMPARISON.md) |
| [工程格式 v2](zh/spec/PROJECT_FORMAT.md) | `.psd2live` 归档布局、v1 迁移、保存与校验 | [English](en/spec/PROJECT_FORMAT.md) |
| [Layout 清单](zh/spec/LAYOUT_MANIFEST.md) | 分层素材的 JSON / zip 交换格式：图片、矩形、上下顺序、组、标签、差分 | [English](en/spec/LAYOUT_MANIFEST.md) |
| [导出目标](zh/spec/EXPORT_TARGETS.md) | 中立绑定 IR、各导出格式与损失报告、模块许可证 | [English](en/spec/EXPORT_TARGETS.md) |
| [运行时](zh/spec/RUNTIME.md) | Rust 运行时、`.p2lrt` 格式、网页播放器与 Godot 节点 | [English](en/spec/RUNTIME.md) |
| [`.p2lrt` 2.0 格式](zh/spec/P2LRT_V2.md) | 分块容器、核心层与扩展层、演进规则 | [English](en/spec/P2LRT_V2.md) |

## Agent（MCP）

| 文档 | 内容 | 译文 · Translations |
| --- | --- | --- |
| [MCP 使用与接口](zh/agent/MCP_AUTHORING.md) | 接入方式、190 项公开操作、原子批量与试运行、调用示例、状态与历史 | [English](en/agent/MCP_AUTHORING.md) |
| [Agent 设计与验收](zh/agent/AGENT_DESIGN.md) | 分工边界、工具设计原则、任务验收步骤 | — |
| [能力实测](zh/STATUS.md) | 真实任务的实测记录与记录格式 | — |

## 内部实现 · Internals

面向本项目开发者的实现说明，仅有中文。Implementation notes for contributors, in Chinese only.

| 文档 | 内容 |
| --- | --- |
| [文档层](zh/spec/DOCUMENT_LAYER.md) | 生成器依赖图、生成结果覆盖、缓存、逐层尺寸与拆分物化 |
| [固化 Rig](zh/spec/MATERIALIZED_RIG.md) | 按修订保存的固化 Rig、检查点、再生成合并与实现进度 |
| [素材与 Layout 实现](zh/spec/MATERIAL_LAYOUT_WORKSPACE.md) | 生成视图与待生成图层、素材库存储、语义标签映射、操作表 |
| [Layout 的 AI 辅助（设计中）](zh/spec/LAYOUT_AI_ASSIST.md) | 对齐、抠图、补全 |
| [无窗口宿主](zh/spec/HEADLESS_HOST.md) | 不依赖界面的应用层工作区宿主、桌面与无窗口适配、外部工具接入 |
| [运行时与导出边界](zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) | 数据流、格式支持范围、交付检查 |
| [网格拓扑与图层拆分](zh/spec/MESH_TOPOLOGY_AND_SPLIT.md) | 自适应网格生成、填充算法、图层拆分 |
| [绘画系统](zh/spec/PAINT_SYSTEM_ARCHITECTURE_AND_PRD.md) | 绘画会话、组件分工、坐标与提交 |
| [画布渲染器](zh/spec/CANVAS_RENDERER.md) | 编辑画布、纹理集页面与预览的渲染架构，播放与跟踪会话 |

## 其他

- [路线图](zh/ROADMAP.md) · [能力实测](zh/STATUS.md)
- [示例素材与输出](../examples/readme.md)
- [原生桥接构建脚本](../native/README.md)
- [第三方组件声明](../THIRD_PARTY_NOTICES.md)

## 维护约定

1. README 只写定位、上手、能力边界与入口；逐步操作以程序内交互教程为准。
2. 描述须与源码一致：教程顺序对照 `InteractiveTutorial.kt`，快捷键对照 `ShortcutRegistry.kt`，CLI 对照 `Main.kt`，MCP 工具对照应用层操作注册（`application/Workspace*Operations.kt`、`WorkspaceCommandDefinitions.kt`）与 `AgentOperationTools.kt`。界面文案对照 `src/main/resources/i18n/`。
3. 区分"已实现""实测通过""设计中"。能解析不等于能编辑，导出成功不等于视觉一致。
4. 用户指南、格式参考与 MCP 参考至少有中英两版；内部实现说明只写中文。已有译文的页面同步结构与事实，不保留过时副本；索引只列出存在的译文，不为缺失的译文放占位链接。
5. 保持文件路径稳定；修改时一并检查链接、图片与示例命令。截图位于 `docs/imgs/`，使用 WebP。
