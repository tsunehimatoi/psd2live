# 实现导览与设计取舍

[Docs](../../README.md) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)

本页保留原 IMPLEMENTATION_COMPARISON 路径，但不再用未经基准测试的“常规实现更差”作比较。第三方来源只在许可说明中维护。

| 阶段 | 当前实现与取舍 |
| --- | --- |
| 源图 | 读取 PSD 层像素、层序和相关属性；分类依据名称与图像分析，复杂图层效果建议先栅格化。 |
| 分类 | NFKC、别名、侧别和数字后缀处理；未知层保留并允许界面纠正。 |
| 网格 | 依据 Alpha 轮廓生成自适应三角网格；密度与轮廓精度需要在效果和成本间选择。 |
| 贴图 | 打包为纹理页，处理边距与可选超分；高清化不改变画布坐标。 |
| Rig | 自动头身、五官、头发层级，再应用参数、结构、形状和路径编辑。 |
| 交互 | 主画布统一选择 / 变形 / 编辑 / 绘画；绘画保留独立会话。 |
| 历史 | 追加式分支快照与内容寻址素材，保存为便携工程。 |
| 导出 | 转换 CMO3 / MOC3 并组装边车；读回与几何检查产生诊断。 |

## 核心不变量

- 图像与画布原点在左上，X 向右、Y 向下；父级局部空间必须由对象类型决定。
- 拓扑修改需要同步 UV、关键形、Glue 引用和路径绑定。
- 源像素和可重放编辑是工程恢复依据，渲染快照不能独立取代它们。
- 模型配置中的相对资源路径必须在交付目录中闭合。
- 底层解析、字段透传、公开编辑和视觉一致性分别验证。

[Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [RigBuilder](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [CanvasEditor](../../../src/main/kotlin/io/github/psd2live/ui/CanvasEditor.kt) · [Runtime / export (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
