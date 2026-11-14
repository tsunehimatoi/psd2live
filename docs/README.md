# 文档目录

[项目首页](../README.md) · [更新日志](zh/CHANGELOG.md) · [路线图](zh/ROADMAP.md) · [能力实测](zh/STATUS.md)

学习操作请先用程序内帮助页的 **教程**（`F1`）：零基础路线 19 课，Cubism 经验者路线 14 课。下列文档用于回看操作与查阅核心格式/接口；历史设计稿与过程记录已归档（见仓库提交历史或本地 `local/archive/`）。

## 目录结构

```text
docs/
├─ README.md            # 文档总索引
├─ branding/            # 应用图标资源
├─ imgs/                # README 与图文教程截图（WebP）
└─ zh/                  # 中文主文档
   ├─ CHANGELOG.md      # 版本更新日志
   ├─ ROADMAP.md        # 后续规划
   ├─ STATUS.md         # 真实任务能力实测记录
   ├─ guide/            # 使用指南、开发与构建文档
   ├─ spec/             # 格式规范与核心架构契约
   └─ agent/            # MCP 接口与 Agent 验收说明
```

## 使用指南

| 文档 | 内容 |
| --- | --- |
| [更新日志](zh/CHANGELOG.md) | 各版本中使用者能感知到的变化 |
| [操作速查](zh/guide/USER_GUIDE.md) | 与程序内教程对应的文字版、常用快捷键、工作区与纹理工作区 |
| [画布编辑](zh/guide/CANVAS_EDITOR.md) | 模式菜单、形变、网格拓扑、Glue、权重、绘画会话 |
| [素材与 Layout](zh/guide/LAYOUT.md) | 素材库、先摆放再生成、重新生成、语义标签 |
| [前后分层图文教程](zh/guide/DEPTH_SPLIT.md) | 衣领与脖子、头发与肩部装饰的前后遮挡，复制、黏合与橡皮擦修边 |
| [骨骼与姿态](zh/guide/SKELETON.md) | 骨架推断、绑定、IK、预设动作与烘焙结构 |
| [摇摆生成](zh/guide/SWING.md) | 左右 / 上下摇摆、画布手柄、摆锤与烘焙 |
| [物理](zh/guide/PHYSICS.md) | 物理组来源、摆锤画布、输入输出、预置与导出 |
| [模拟与烘焙](zh/guide/SIMULATION.md) | 布料 / 头发的 2D 模拟、顶点权重组、胶水角色，烘焙为参数、关键形与摆锤 |
| [模拟图文教程](zh/guide/SIMULATION_TUTORIAL.md) | 从头发与服装预设开始，调整固定点权重、重新烘焙并检查导出效果 |
| [预设与修改器](zh/guide/MODIFIERS.md) | 预设与修改器用法 |
| [变形路径（实验性）](zh/guide/DEFORM_PATHS.md) | 路径的创建、绑定与导出方式 |
| [纹理高清化](zh/guide/TEXTURE_UPSCALE.md) | 本地 nunif 配置、参数与效果检查 |
| [算法插件](zh/guide/ALGORITHM_PLUGINS.md) | 算法插件开发与接入 |

## 构建与发布

| 文档 | 内容 |
| --- | --- |
| [开发与命令行](zh/guide/DEVELOPMENT.md) | 源码运行、CLI 参数、测试与打包、代码结构 |
| [Cubism Native 预览](zh/guide/CUBISM_SDK_SETUP.md) | 可选的官方 SDK 预览桥接构建与部署 |
| [CI 与发行](zh/guide/CUBISM_CI_RELEASE.md) | GitHub Actions 测试与含 SDK 的发行流程 |

## 格式与核心契约

| 文档 | 内容 |
| --- | --- |
| [PSD 素材与命名](zh/spec/PSD_LAYER_SPEC.md) | 分层要求、图层名称表、左右与差分 |
| [变形器与参数](zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) | 自动生成的结构、坐标约定、默认参数 |
| [工程格式 v2](zh/spec/PROJECT_FORMAT.md) | `.psd2live` 归档布局、v1 迁移、保存与校验 |
| [Layout 清单](zh/spec/LAYOUT_MANIFEST.md) | 分层素材的 JSON / zip 交换格式 |
| [导出目标](zh/spec/EXPORT_TARGETS.md) | 中立绑定 IR、各导出格式与损失报告、模块许可证 |
| [运行时](zh/spec/RUNTIME.md) | Rust 运行时、`.p2lrt` 格式、网页播放器与 Godot 节点 |
| [`.p2lrt` 2.0 格式](zh/spec/P2LRT_V2.md) | 分块容器、核心层与扩展层、演进规则 |
| [文档层](zh/spec/DOCUMENT_LAYER.md) | 生成器依赖图、生成结果覆盖、缓存 |
| [Rig 预设架构](zh/spec/RIG_PRESETS.md) | 部位、基础算法、模块与配方 |
| [极简工作区与移动端（设计中）](zh/spec/LITE_WORKSPACE.md) | 零门槛精简界面、前后分层触控向导、MCP 接入与移动端分期方案 |

## Agent（MCP）

| 文档 | 内容 |
| --- | --- |
| [MCP 使用与接口](zh/agent/MCP_AUTHORING.md) | 接入方式、公开操作、原子批量与试运行、调用示例 |
| [Agent 设计与验收](zh/agent/AGENT_DESIGN.md) | 分工边界、工具设计原则、任务验收步骤 |

## 其他

- [示例素材与输出](../examples/readme.md)
- [原生桥接构建脚本](../native/README.md)
- [第三方组件声明](../THIRD_PARTY_NOTICES.md)

## 维护约定

1. README 只写定位、上手、能力边界与入口；逐步操作以程序内交互教程为准。
2. 仅维护中文主文档，不再同步多语言文档副本。
3. 不再新增零散的过程性设计稿/审阅稿到 `docs/`（过程笔记放 `local/`）。
4. 描述须与源码一致：教程顺序对照 `InteractiveTutorial.kt`，快捷键对照 `ShortcutRegistry.kt`，CLI 对照 `Main.kt`，MCP 工具对照应用层操作定义与 `AgentOperationTools.kt`。
