# 变形器与参数参考

[Docs](../../README.md) · [PSD](PSD_LAYER_SPEC.md)

本页描述自动生成的默认结构与编辑约定。实际对象取决于素材、模型设置和后续编辑，应以层级树、参数面板或 MCP inspect 返回值为准。

## 结构

身体 XY 与身体 Z / 呼吸位于上层，头部旋转与头壳跟随位于其下。面部经纬网、脸部轮廓和五官位移承接眼眉鼻嘴耳；前后发各有头壳跟随与物理分支。实际层级还包含区域组、瞳孔保持 / 视线及可选嘴唇等对象，不能用旧文档的固定树替代工程查询。

## 坐标与关键形

画布原点在左上，X 向右、Y 向下。对象几何在父级局部空间中求值；Warp 的归一化局部坐标与 Rotation 局部坐标不可混用。静息网格和关键形在转换时还存在不同的坐标约定，导出通过 restMeshesToCanvasSpace 等转换处理。

直接绑定决定对象自己的形状轴，父级参数通过层级继承。关键形是参数坐标处的对象形状，不是动画时间帧；动画文件随时间驱动参数，物理也输出参数值。新增参数后仍需绑定形状才会有动作。

## 自动形变

头部 X/Y 以端点与中点组合生成九姿态；原画头部倾斜作为局部基准估计。面部采用经纬网和分区修形，眼眉、瞳孔、嘴和耳朵有独立补偿或遮罩逻辑。身体 XY、平面倾斜与呼吸分层处理。前后发分离于强面部形变，并分别输出摆动参数；眼球形变可由眨眼物理驱动。

这些是预设算法，不是通用 3D 重建。原画分层、锚点误差、宽发片、极端角度与重叠区域都可能需要人工修形。精确曲线常数和网格分割随实现演进，维护时直接核对源码，不复制脱离版本的公式。

## 默认参数

下表来自 RigParameters。网格模式、关闭变形器或缺少相应部件时，实际参数集合可能不同；用户也能增加参数与差分。

| ID | Range | Default |
| --- | --- | --- |
| `ParamAngleX` | -45…45 | 0 |
| `ParamAngleY`, `ParamAngleZ` | -30…30 | 0 |
| `ParamBodyAngleX`, `ParamBodyAngleY`, `ParamBodyAngleZ` | -10…10 | 0 |
| `ParamEyeLOpen`, `ParamEyeROpen` | 0…1 | 1 |
| `ParamEyeBallX`, `ParamEyeBallY`, `ParamEyeBallForm` | -1…1 | 0 |
| `ParamBrowLY`, `ParamBrowRY` | -1…1 | 0 |
| `ParamMouthForm` | -1…1 | 0 |
| `ParamMouthOpenY`, `ParamBreath` | 0…1 | 0 |
| `ParamHairFront`, `ParamHairBack` | -1…1 | 0 |

## 验证

检查中立姿态、端点、组合角和中间值；检查父级与局部形状是否重复施加运动。导出流水线包含几何诊断与读回检查，但警告不是“所有姿态已验证”的证明；运行时目标不支持的功能还可能被降级。

[RigBuilder / RigParameters](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [PuppetModel](../../../src/main/kotlin/org/umamo/runtime/model/PuppetModel.kt) · [Architecture (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
