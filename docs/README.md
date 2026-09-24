# 文档导航 · Documentation · ドキュメント

[中文首页](../README.md) · [English](../README_en.md) · [日本語](../README_ja.md)

**学习操作请先用程序内「帮助 → 教程…」。** 文字版按相同 14 课提供速查；技术参考只讲数据、接口与限制，不再重复整套界面教程。

For hands-on learning, open **Help → Tutorials**. The short guides follow the same 14 lessons. 中文专题尚无翻译的地方，其他语言文档会明确指向中文参考。

操作学習は **ヘルプ → チュートリアル** から。文字版は同じ全 14 講座の復習用です。未翻訳の専門資料は中国語で提供しています。

## 使用与开发 · Guides

| 文档 | 中文 | English | 日本語 |
| --- | --- | --- | --- |
| 操作速查 / User guide | [打开](zh/guide/USER_GUIDE.md) | [Open](en/guide/USER_GUIDE.md) | [開く](ja/guide/USER_GUIDE.md) |
| 开发与 CLI / Development | [打开](zh/guide/DEVELOPMENT.md) | [Open](en/guide/DEVELOPMENT.md) | [開く](ja/guide/DEVELOPMENT.md) |
| 可选 Native SDK 预览 | [打开](zh/guide/CUBISM_SDK_SETUP.md) | [Open](en/guide/CUBISM_SDK_SETUP.md) | [開く](ja/guide/CUBISM_SDK_SETUP.md) |
| Cubism CI / 发行 | [打开](zh/guide/CUBISM_CI_RELEASE.md) | [Open](en/guide/CUBISM_CI_RELEASE.md) | 英文/中文参考 |
| 画布编辑速查 | [打开](zh/guide/CANVAS_EDITOR.md) | 中文参考 | 中文参考 |
| 变形路径（实验性） | [打开](zh/guide/DEFORM_PATHS.md) | 中文参考 | 中文参考 |
| 纹理高清化 | [打开](zh/guide/TEXTURE_UPSCALE.md) | 中文参考 | 中文参考 |

## 技术参考 · Reference

| 文档 | 中文 | English | 日本語 |
| --- | --- | --- | --- |
| PSD 素材与命名 | [打开](zh/spec/PSD_LAYER_SPEC.md) | [Open](en/spec/PSD_LAYER_SPEC.md) | [開く](ja/spec/PSD_LAYER_SPEC.md) |
| 变形器与参数 | [打开](zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) | [Open](en/spec/DEFORMER_AND_PARAMETER_SPEC.md) | [開く](ja/spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| 实现导览与取舍 | [打开](zh/spec/IMPLEMENTATION_COMPARISON.md) | [Open](en/spec/IMPLEMENTATION_COMPARISON.md) | [開く](ja/spec/IMPLEMENTATION_COMPARISON.md) |
| 工程格式 v1 | [打开](zh/spec/PROJECT_FORMAT.md) | [Open](en/spec/PROJECT_FORMAT.md) | 英文参考 |
| 工程、运行时与导出边界 | [打开](zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) | 中文参考 | 中文参考 |
| 绘画系统实现 | [打开](zh/spec/PAINT_SYSTEM_ARCHITECTURE_AND_PRD.md) | 中文参考 | 中文参考 |
| 网格拓扑与图层拆分 | [打开](zh/spec/MESH_TOPOLOGY_AND_SPLIT.md) | 中文参考 | 中文参考 |

## Agent 与维护

- [MCP 使用与接口](zh/agent/MCP_AUTHORING.md)：11 个公开工具、请求结构、空间映射与历史。
- [Agent 设计与验收](zh/agent/AGENT_DESIGN.md)：设计依据、任务验收和成本控制。
- [能力实测](../STATUS.md)：保留真实样本与证据缺口，不以接口存在推定效果。
- [路线图](../ROADMAP.md)：后续工作，不承诺排期。
- [原生桥接构建](../native/live2d_renderer/README.md)、[示例说明](../examples/readme.md)、[第三方表记](../THIRD_PARTY_NOTICES.md)。
- [历史调研：2026-09-13](zh/agent/archive/AGENT_RESEARCH_2026-09-13.md)：归档背景，不作为当前产品契约。

## 维护约定

1. README 只保留定位、上手、能力边界与入口；操作细节跟随程序内交互教程。
2. 教程顺序以 `InteractiveTutorial.kt` 为准，名称与行为核对语言资源；快捷键以 `ShortcutRegistry.kt` 为准。
3. CLI 参数核对 `Main.kt`；MCP 工具及分支核对 `AgentAuthoringTools.kt`，不要将底层方法写成公开接口。
4. 区分已实现、实测通过、设计和历史资料。格式可解析不等于可编辑，导出成功不等于视觉一致。
5. 三语言已有页面同步维护结构与事实；没有译文时明确链接参考语言，不保留过时副本。
6. 保留稳定文件路径，调整标题与内容；链接、图片、代码入口及示例命令随文档一起检查。

本次整理依据仓库当前源码；历史实测和许可原文保留其证据边界。
