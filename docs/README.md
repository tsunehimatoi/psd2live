# 文档目录 · Documentation · ドキュメント

[中文首页](../README.md) · [English](en/README.md) · [日本語](ja/README.md) · [한국어](ko/README.md) · [更新日志](zh/CHANGELOG.md)

学习操作请先用程序内的 **帮助 → 教程…**（`F1`）：零基础路线 18 课，Cubism 经验者路线 13 课。下列文档用于回看操作和查阅技术细节。中文为主要语言；尚无译文的页面在英文、日文列中标注为中文参考。

For hands-on learning, open **Help → Tutorials…** (`F1`). Chinese is the primary documentation language; pages without a translation link to the Chinese reference.

操作の習得には **ヘルプ → チュートリアル…**（`F1`）を使ってください。主要言語は中国語で、未翻訳のページは中国語版へリンクしています。

## 使用指南 · Guides

| 文档 | 内容 | 中文 | English | 日本語 |
| --- | --- | --- | --- | --- |
| 更新日志 | 各版本中使用者能感知到的变化 | [打开](zh/CHANGELOG.md) | [中文参考](zh/CHANGELOG.md) | [中国語参考](zh/CHANGELOG.md) |
| 操作速查 | 与程序内教程对应的文字版、常用快捷键、工作区与纹理工作区 | [打开](zh/guide/USER_GUIDE.md) | [Open](en/guide/USER_GUIDE.md) | [開く](ja/guide/USER_GUIDE.md) |
| 画布编辑 | 模式菜单（选择 / 变形 / 编辑 / 模拟 / 骨骼 / 绘画 / 预览）、形变、网格拓扑、Glue、权重、绘画会话 | [打开](zh/guide/CANVAS_EDITOR.md) | [中文参考](zh/guide/CANVAS_EDITOR.md) | [中国語参考](zh/guide/CANVAS_EDITOR.md) |
| 前后分层图文教程 | 衣领与脖子、头发与肩部装饰的前后遮挡，复制、黏合与橡皮擦修边 | [打开](zh/guide/DEPTH_SPLIT.md) | [中文参考](zh/guide/DEPTH_SPLIT.md) | [中国語参考](zh/guide/DEPTH_SPLIT.md) |
| 骨骼与姿态 | 骨架推断、绑定、IK、预设动作与烘焙结构 | [打开](zh/guide/SKELETON.md) | [中文参考](zh/guide/SKELETON.md) | [中国語参考](zh/guide/SKELETON.md) |
| 摇摆生成 | 左右 / 上下摇摆、画布手柄、摆锤与烘焙 | [打开](zh/guide/SWING.md) | [中文参考](zh/guide/SWING.md) | [中国語参考](zh/guide/SWING.md) |
| 物理 | 物理组来源、摆锤画布、输入输出、预置与导出 | [打开](zh/guide/PHYSICS.md) | [中文参考](zh/guide/PHYSICS.md) | [中国語参考](zh/guide/PHYSICS.md) |
| 模拟与烘焙 | 布料 / 头发的 2D 模拟、顶点权重组、胶水角色，烘焙为参数、关键形与摆锤 | [打开](zh/guide/SIMULATION.md) | [中文参考](zh/guide/SIMULATION.md) | [中国語参考](zh/guide/SIMULATION.md) |
| 模拟图文教程 | 从头发与服装预设开始，调整固定点权重、重新烘焙并检查导出效果 | [打开](zh/guide/SIMULATION_TUTORIAL.md) | [中文参考](zh/guide/SIMULATION_TUTORIAL.md) | [中国語参考](zh/guide/SIMULATION_TUTORIAL.md) |
| 变形路径（实验性） | 路径的创建、绑定与导出方式 | [打开](zh/guide/DEFORM_PATHS.md) | [中文参考](zh/guide/DEFORM_PATHS.md) | [中国語参考](zh/guide/DEFORM_PATHS.md) |
| 纹理高清化 | 本地 nunif 配置、参数与效果检查 | [打开](zh/guide/TEXTURE_UPSCALE.md) | [中文参考](zh/guide/TEXTURE_UPSCALE.md) | [中国語参考](zh/guide/TEXTURE_UPSCALE.md) |

## 构建与发布 · Build and release

| 文档 | 内容 | 中文 | English | 日本語 |
| --- | --- | --- | --- | --- |
| 开发与命令行 | 源码运行、CLI 参数、测试与打包、代码结构 | [打开](zh/guide/DEVELOPMENT.md) | [Open](en/guide/DEVELOPMENT.md) | [開く](ja/guide/DEVELOPMENT.md) |
| Cubism Native 预览 | 可选的官方 SDK 预览桥接构建与部署 | [打开](zh/guide/CUBISM_SDK_SETUP.md) | [Open](en/guide/CUBISM_SDK_SETUP.md) | [開く](ja/guide/CUBISM_SDK_SETUP.md) |
| CI 与发行 | GitHub Actions 测试与含 SDK 的发行流程 | [打开](zh/guide/CUBISM_CI_RELEASE.md) | [Open](en/guide/CUBISM_CI_RELEASE.md) | [中国語参考](zh/guide/CUBISM_CI_RELEASE.md) |

## 技术参考 · Reference

| 文档 | 内容 | 中文 | English | 日本語 |
| --- | --- | --- | --- | --- |
| PSD 素材与命名 | 分层要求、图层名称表、左右与差分 | [打开](zh/spec/PSD_LAYER_SPEC.md) | [Open](en/spec/PSD_LAYER_SPEC.md) | [開く](ja/spec/PSD_LAYER_SPEC.md) |
| 变形器与参数 | 自动生成的结构、坐标约定、默认参数 | [打开](zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) | [Open](en/spec/DEFORMER_AND_PARAMETER_SPEC.md) | [開く](ja/spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| 实现概览 | 流水线各阶段的实现与核心不变量 | [打开](zh/spec/IMPLEMENTATION_COMPARISON.md) | [Open](en/spec/IMPLEMENTATION_COMPARISON.md) | [開く](ja/spec/IMPLEMENTATION_COMPARISON.md) |
| 工程格式 v2 | `.psd2live` 归档布局、v1 迁移、保存与校验 | [打开](zh/spec/PROJECT_FORMAT.md) | [Open](en/spec/PROJECT_FORMAT.md) | [中国語参考](zh/spec/PROJECT_FORMAT.md) |
| 导出目标 | 中立绑定 IR、各导出格式与损失报告、模块许可证 | [打开](zh/spec/EXPORT_TARGETS.md) | [中文参考](zh/spec/EXPORT_TARGETS.md) | [中国語参考](zh/spec/EXPORT_TARGETS.md) |
| 运行时 | Rust 运行时、`.p2lrt` 格式、网页播放器与 Godot 节点 | [打开](zh/spec/RUNTIME.md) | [中文参考](zh/spec/RUNTIME.md) | [中国語参考](zh/spec/RUNTIME.md) |
| `.p2lrt` 2.0 格式 | 分块容器、核心层与扩展层、演进规则 | [打开](zh/spec/P2LRT_V2.md) | [中文参考](zh/spec/P2LRT_V2.md) | [中国語参考](zh/spec/P2LRT_V2.md) |
| 文档层 | 生成器依赖图、生成结果覆盖、缓存、逐层尺寸与拆分物化 | [打开](zh/spec/DOCUMENT_LAYER.md) | [中文参考](zh/spec/DOCUMENT_LAYER.md) | [中国語参考](zh/spec/DOCUMENT_LAYER.md) |
| 固化 Rig | 按修订保存的固化 Rig、检查点、再生成合并与实现进度 | [打开](zh/spec/MATERIALIZED_RIG.md) | [中文参考](zh/spec/MATERIALIZED_RIG.md) | [中国語参考](zh/spec/MATERIALIZED_RIG.md) |
| 运行时与导出边界 | 数据流、格式支持范围、交付检查 | [打开](zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) | [中文参考](zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) | [中国語参考](zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) |
| 网格拓扑与图层拆分 | 自适应网格生成、填充算法、图层拆分 | [打开](zh/spec/MESH_TOPOLOGY_AND_SPLIT.md) | [中文参考](zh/spec/MESH_TOPOLOGY_AND_SPLIT.md) | [中国語参考](zh/spec/MESH_TOPOLOGY_AND_SPLIT.md) |
| 绘画系统 | 绘画会话、组件分工、坐标与提交 | [打开](zh/spec/PAINT_SYSTEM_ARCHITECTURE_AND_PRD.md) | [中文参考](zh/spec/PAINT_SYSTEM_ARCHITECTURE_AND_PRD.md) | [中国語参考](zh/spec/PAINT_SYSTEM_ARCHITECTURE_AND_PRD.md) |
| 画布渲染器 | 编辑画布、纹理集页面与预览的渲染架构，播放与跟踪会话 | [打开](zh/spec/CANVAS_RENDERER.md) | [中文参考](zh/spec/CANVAS_RENDERER.md) | [中国語参考](zh/spec/CANVAS_RENDERER.md) |
| 归档 | 已完成的计划、调研与盘点（默认动画重构、人体关节蒙皮调研、骨骼工具盘点），不代表当前实现 | [打开](zh/spec/archive/) | [中文参考](zh/spec/archive/) | [中国語参考](zh/spec/archive/) |

## Agent（MCP）

| 文档 | 内容 |
| --- | --- |
| [MCP 使用与接口](zh/agent/MCP_AUTHORING.md) | 接入方式、189 项公开操作、原子批量与试运行、调用示例、状态与历史 |
| [Agent 设计与验收](zh/agent/AGENT_DESIGN.md) | 分工边界、工具设计原则、任务验收步骤 |
| [能力实测](zh/STATUS.md) | 真实任务的实测记录与记录格式 |
| [归档](zh/agent/archive/) | 2026-09-13 调研、应用层重构记录与 UI / MCP 对照表，历史背景，不代表当前接口 |

## 其他

- [路线图](zh/ROADMAP.md) · [能力实测](zh/STATUS.md)
- [示例素材与输出](../examples/readme.md)
- [原生桥接构建脚本](../native/README.md)
- [第三方组件声明](../THIRD_PARTY_NOTICES.md)

## 维护约定

1. README 只写定位、上手、能力边界与入口；逐步操作以程序内交互教程为准。
2. 描述须与源码一致：教程顺序对照 `InteractiveTutorial.kt`，快捷键对照 `ShortcutRegistry.kt`，CLI 对照 `Main.kt`，MCP 工具对照应用层操作注册（`application/Workspace*Operations.kt`、`WorkspaceCommandDefinitions.kt`）与 `AgentOperationTools.kt`。界面文案对照 `src/main/resources/i18n/`。
3. 区分"已实现""实测通过""设计中"。能解析不等于能编辑，导出成功不等于视觉一致。
4. 三种语言已有的页面同步结构与事实；没有译文时链接中文，不保留过时副本。
5. 保持文件路径稳定；修改时一并检查链接、图片与示例命令。截图位于 `docs/imgs/`，使用 WebP。
