# PSD2Live 文档

[项目主页](../README.md) · [English](../README_en.md) · [日本語](../README_ja.md)

PSD2Live 是一条自动化的 Live2D 模型生成流水线：输入一张按规范分层的 PSD，自动完成图层语义识别、连通域双侧拆分、自适应三角网格剖分、变形器层级与九轴面部经纬网构建、头发多摆物理与循环待机动作生成，最终导出可在 Live2D Cubism Modeler 5 中二次编辑的 `.cmo3` 工程与运行时 `.moc3` 文件族。完整特性展示、下载与构建说明见[项目主页](../README.md)。

<p align="center">
  <img src="imgs/mesh22.png" width="32%" alt="22 px 高密度自适应网格" />
  <img src="imgs/mesh64.png" width="32%" alt="64 px 平衡型自适应网格" />
  <img src="imgs/mesh115.png" width="32%" alt="115 px 低密度自适应网格" />
  <br>
  <em>自适应网格剖分：22 / 64 / 115 px 三种密度的拓扑对比</em>
</p>

本目录按 **用途分类 × 语言** 组织，路径规则为 `docs/<语言>/<分类>/<文档>.md`：

- **语言**：`zh` 中文 · `en` English · `ja` 日本語
- **分类**：`guide` 使用指南 · `spec` 规范与格式 · `agent` Agent 与 MCP

> [!NOTE]
> 各语言的翻译并非逐篇对齐：`en` / `ja` 覆盖部分 `guide` 与 `spec` 文档，`agent` 分类目前仅有中文。下表「可用语言」列如实标注现状，不做静默对齐。

## 从哪里开始

| 我想…… | 先读 | 接着读 |
| :--- | :--- | :--- |
| 确认手上的 PSD 能否生成 | [PSD 图层规范](zh/spec/PSD_LAYER_SPEC.md) | [用户操作指南](zh/guide/USER_GUIDE.md) |
| 尽快跑通一次导出 | [用户操作指南](zh/guide/USER_GUIDE.md) | [工程文件格式](zh/spec/PROJECT_FORMAT.md) |
| 使用路径制作网格 K 帧 | [变形路径（实验性）](zh/guide/DEFORM_PATHS.md) | [变形器与参数规范](zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| 弄清生成结果为什么长这样 | [变形器与参数规范](zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) | [实现对比与设计决策](zh/spec/IMPLEMENTATION_COMPARISON.md) |
| 让 AI 宿主接入并修改工程 | [MCP 接口契约](zh/agent/MCP_AUTHORING.md) | [Agent 设计](zh/agent/AGENT_DESIGN.md) |
| 开启与官方运行时的一致性对照 | [Live2D SDK 配置指南](zh/guide/CUBISM_SDK_SETUP.md) | — |
| 提高纹理分辨率 | [纹理高清化](zh/guide/TEXTURE_UPSCALE.md) | — |
| 解析、迁移或备份 `.psd2live` 工程 | [工程文件格式](zh/spec/PROJECT_FORMAT.md) | [实现对比与设计决策](zh/spec/IMPLEMENTATION_COMPARISON.md) |

## 文档地图

### guide/ 使用指南

回答「怎么用」与「报错怎么办」，面向安装、启动、操作与配置。

| 文档 | 内容 | 可用语言 |
| :--- | :--- | :--- |
| [用户操作指南](zh/guide/USER_GUIDE.md) | 四大工作区视图、独立日志坞、检视器面板、端到端生成流程、Agent / MCP 连接、快捷键与 CLI 参数、FAQ、单文件工程与历史树 | 中 · [英](en/guide/USER_GUIDE.md) · [日](ja/guide/USER_GUIDE.md) |
| [Live2D SDK 配置指南](zh/guide/CUBISM_SDK_SETUP.md) | 为什么要接官方 SDK、非分发原则、一键 `/MT` 自建 DLL（无 VC 运行库依赖）、部署与排障 | 中 · [英](en/guide/CUBISM_SDK_SETUP.md) · [日](ja/guide/CUBISM_SDK_SETUP.md) |
| [纹理高清化](zh/guide/TEXTURE_UPSCALE.md) | 可选 nunif / waifu2x 动漫超分：环境配置、桌面与 CLI 用法、透明边缘处理、显存策略与验证 | 中 |

### spec/ 规范与格式

回答「为什么」与「边界在哪」，覆盖 PSD 该怎么画、系统生成了什么、算法与格式的确定性约束。

| 文档 | 内容 | 可用语言 |
| :--- | :--- | :--- |
| [PSD 图层规范与命名指南](zh/spec/PSD_LAYER_SPEC.md) | 语义标签清单、侧别判定、8 邻域连通域拆分、五官/头发分层指南、变体命名、未识别图层策略、检查清单 | 中 · [英](en/spec/PSD_LAYER_SPEC.md) · [日](ja/spec/PSD_LAYER_SPEC.md) |
| [变形器与参数规范](zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) | 变形器拓扑与坐标空间、九轴经纬网数学模型、C1 连续曲线、五官二次解耦修形、身体与呼吸变形、物理动力学、参数映射表、几何自检 | 中 · [英](en/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [日](ja/spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| [工程文件格式](zh/spec/PROJECT_FORMAT.md) | `.psd2live` 归档条目、保存与恢复语义、校验规则、手工编辑限制、UI 与 MCP 入口 | 中（摘要）· [英（完整）](en/spec/PROJECT_FORMAT.md) |
| [实现对比与设计决策](zh/spec/IMPLEMENTATION_COMPARISON.md) | 逐阶段技术选型与取舍、坐标空间与格式不变性、自动化几何自检 | 中 · [英](en/spec/IMPLEMENTATION_COMPARISON.md) · [日](ja/spec/IMPLEMENTATION_COMPARISON.md) |
| [运行时、编辑与导出结构及功能缺口](zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) | 统一运行时、工程历史、CMO3/MOC3/边车数据流，格式覆盖矩阵与待实现优先级 | 中 |

### agent/ Agent 与 MCP

回答「现在能调什么」与「接下来要做什么」，面向让外部 AI 宿主接入并编辑工程的集成者与提示词作者。

| 文档 | 内容 | 状态 | 可用语言 |
| :--- | :--- | :--- | :--- |
| [MCP 接口契约](zh/agent/MCP_AUTHORING.md) | 当前对外暴露的 10 个合并工具及其分支契约、素材导入与去底色流程、姿态拼图、失败与拒绝条件；同时记录已从工具面移除的旧细粒度接口 | 描述已实现接口 | 中 |
| [Agent 设计](zh/agent/AGENT_DESIGN.md) | 产品边界与硬约束、领域模型、工具收敛与连续形变场、从建模流程反推的能力缺口、成本与信息压缩、分阶段实施与验收 | 设计口径，尚未全部实现 | 中 |
| [首轮调研存档](zh/agent/archive/AGENT_RESEARCH_2026-09-13.md) | 同类项目比较与首轮结论；优先级已被上述两份文档取代 | 仅供溯源 | 中 |

## 其他文档位置

以下内容属于文档性质，但因其功能属性或打包要求不放在本目录，在此登记以免游离：

| 位置 | 内容 | 说明 |
| :--- | :--- | :--- |
| [`README.md`](../README.md) / [`README_en.md`](../README_en.md) / [`README_ja.md`](../README_ja.md) | 项目介绍、核心特性、快速上手、PSD 命名速查、变形器层级、导出产物 | 三语入口文档，完整产品展示在这里 |
| [`ROADMAP.md`](../ROADMAP.md) | 计划实现表：待完成条目（复选框） | 不排期；已完成项不在此维护 |
| [`STATUS.md`](../STATUS.md) | Agent 能力实测总表：状态、评价、宿主环境、返工次数与证据 | 仅中文 |
| [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) | 第三方组件、许可与 Live2D 商标声明 | 合规文档 |
| `examples/` | 示例工程与版本差异说明 | 功能示例目录 |
| `docs/imgs/` | 文档插图（`use.gif`、`agent.png`、`mesh*.png`） | 被三语 README 与本页引用 |

## 维护约定

1. **归位**：新增文档放入 `docs/<语言>/<分类>/`，文件名使用 `UPPER_SNAKE_CASE.md`；语言目录只放该语言正文，不建占位文件。
2. **标注语言**：某文档只新增了一种语言时，只在「可用语言」列列出实际存在的版本；缺语言如实标注，不做静默对齐。
3. **语言切换**：每篇文档标题下方保留一行语言切换链接（存在对应译文时）。
4. **同步更新**：新增、移动或删除文档后，必须同步更新本页、`README.md` / `README_en.md` / `README_ja.md` 的文档索引，以及文档内的交叉引用与源码中的文档路径引用。
5. **归档而非删除**：被取代的设计稿与调研稿移入对应分类的 `archive/`，并在正文开头注明被哪份文档取代、以哪份为准。
6. **精简优先**：同一主题只保留一份现行文档，历史论证沉淀在归档中；现行文档只写仍然成立的结论与约束；产品展示留在三语 README，本页只做定位、导航与索引。
