# AutoLive2D PSD 图层规范与命名指南 (PSD Layer Specification)

[English](../en/PSD_LAYER_SPEC.md) | [日本語](../ja/PSD_LAYER_SPEC.md)

AutoLive2D 采用 **See-Through** 语义规范作为标准命名风格，并深度兼容常见的中文、日文部件别名。本指南为画师（Illustrators）与模型师（Modelers）提供分层标准、命名约定、自动连通域拆分规则以及最佳实践。

---

## 目录

- [PSD 图像通用要求](#psd-图像通用要求)
- [语义标签清单 (Semantic Tags)](#语义标签清单-semantic-tags)
  - [1. 头部组件 (Head Group)](#1-头部组件-head-group)
  - [2. 身体组件 (Body Group)](#2-身体组件-body-group)
  - [3. 附加与特效组件 (Extra Group)](#3-附加与特效组件-extra-group)
- [侧别判定规则 (Side Matching)](#侧别判定规则-side-matching)
- [8 邻域连通域自动拆分 (Auto-Splitting)](#8-邻域连通域自动拆分-auto-splitting)
- [五官与特殊部件分层指南](#五官与特殊部件分层指南)
  - [1. 眼睛系统 (Eyes & Irides)](#1-眼睛系统-eyes--irides)
  - [2. 嘴巴与口腔系统 (Mouth & Internals)](#2-嘴巴与口腔系统-mouth--internals)
  - [3. 头发系统 (Front & Back Hair)](#3-头发系统-front--back-hair)
- [变体与副本命名 (Variants & Copies)](#变体与副本命名-variants--copies)
- [未识别图层处理策略 (Unknown Layers)](#未识别图层处理策略-unknown-layers)
- [分层检查清单 (Artist Checklist)](#分层检查清单-artist-checklist)

---

## PSD 图像通用要求

1. **颜色模式**：**RGB / 8 位通道**（请勿使用 CMYK、Lab 或 16 位/32 位颜色模式）。
2. **背景透明**：请勿保留纯白或纯色不透明背景层。
3. **图层合并与光栅化**：
   - 文本图层、矢量图层、形状图层需先转换为栅格图层；
   - 阴影、高光、描边等图层样式需合并到对应画元中；
   - 调整图层（色彩平衡、曲线等）需向下合并。
4. **图层命名**：保持命名规范、语义明确，避免全使用 `图层 1`、`Layer 2` 等无意义名字。

---

## 语义标签清单 (Semantic Tags)

AutoLive2D 内置 31 种核心语义标签，并根据名称自动分类归入三大组织层级：

### 1. 头部组件 (Head Group)

| 语义标签 (Tag) | 推荐英文名 | 常用中文别名 (简/繁) | 常用日文别名 | 说明与绑定行为 |
| :--- | :--- | :--- | :--- | :--- |
| `BACK_HAIR` | `back hair`, `backhair` | 后发, 后髪, 后脑勺 | 後ろ髪 | 挂载于 `DeformHairBackFollow` -> `DeformHairBackPhysics`（自动后发物理摆动） |
| `FRONT_HAIR` | `front hair`, `fronthair` | 前发, 前髪, 刘海, 瀏海 | 前髪 | 挂载于 `DeformHairFrontFollow` -> `DeformHairFrontPhysics`（自动前发物理摆动） |
| `HEADWEAR` | `headwear`, `hat` | 帽子, 头饰, 頭飾, 发饰 | 髪飾り | 挂载于头部容器 |
| `FACE` | `face`, `head` | 脸, 臉, 脸部, 面部 | 顔, 肌 | 头部与面部基底，提供面部中心定位参考 |
| `FACE_DETAIL`| `facedetail`, `blush` | 脸部细节, 腮红, 紅暈 | チーク | 腮红、泪痣、面纹等面部细节 |
| `IRIDES` | `irides`, `iris`, `pupil` | 瞳孔, 虹膜, 眼珠, 眼睛 | 目, 瞳 | 视线追踪 + 左右眼白自动剪切蒙版 + 果冻眼物理挤压回弹 |
| `EYEBROW` | `eyebrow`, `brow` | 眉毛, 眉 | まゆ | 上下移动联动 + 九轴透视平面跟随 |
| `EYEWHITE` | `eyewhite`, `eye_white` | 眼白, 白眼 | 目白 | 闭眼时收缩为闭眼参考线，作为瞳孔的剪切蒙版 |
| `EYELASH` | `eyelash`, `lash` | 睫毛 | まつ毛, まつげ | 睁眼原形 -> 闭眼沿原画中心轨迹弯曲为平滑 U 形覆盖线 |
| `EYE_CLOSE` | `eye_close`, `eye_c` | 闭眼, 閉眼 | 目閉じ, 閉じ目 | 可选闭眼贴图，睁眼时透明度为 0，闭眼时渐显 |
| `EYEWEAR` | `eyewear`, `glasses` | 眼镜, 眼鏡 | メガネ | 眼部配饰，挂载于面部九轴经纬网 |
| `EARS` | `ears`, `ear` | 耳朵, 耳 | 耳 | 负深度位移，头部大角度转动时远侧耳透明度自动淡出至 ~52% |
| `EARWEAR` | `earwear`, `earring` | 耳环, 耳環, 耳饰, 耳飾 | イヤリング | 耳部配饰 |
| `NOSE` | `nose` | 鼻子, 鼻 | 鼻 | 最大感知深度位移（鼻尖 > 鼻梁 > 鼻根），主导三维朝向感知 |
| `MOUTH` / `MOUTH_OPEN` | `mouth`, `mouth_open` | 嘴, 嘴巴, 口, 开口 | 口, 口開き | **最大张口原图**；`ParamMouthOpenY` 从张口向中线平滑压缩闭口 |
| `MOUTH_CLOSE`| `mouth_close`, `mouth_c` | 闭口, 闭嘴 | 口閉じ | 可选闭口线图层（开嘴时自动渐隐） |
| `TOOTH_T` | `tooth-t`, `upper tooth` | 上牙, 上歯 | 上歯 | 可选上牙，自动以最近的 `mouth` 为剪切蒙版 |
| `TOOTH_B` | `tooth-b`, `lower tooth` | 下牙, 下歯 | 下歯 | 可选下牙，自动以最近的 `mouth` 为剪切蒙版 |
| `TONGUE` | `tongue` | 舌头, 舌頭 | 舌 | 可选舌头，自动以最近的 `mouth` 为剪切蒙版 |

### 2. 身体组件 (Body Group)

| 语义标签 (Tag) | 推荐英文名 | 常用中文别名 | 说明与绑定行为 |
| :--- | :--- | :--- | :--- |
| `NECK` | `neck` | 脖子, 颈部, 頸部, 首 | 脖子基底，承接头部旋转与身体倾斜 |
| `NECKWEAR` | `neckwear`, `collar`, `scarf` | 领饰, 領飾, 围巾, 项链 | 领口、项圈等配饰 |
| `TOPWEAR` | `topwear`, `clothes`, `shirt` | 上衣, 衣服, 服装, 服 | 绑定身体 XYZ 与胸腔呼吸膨胀 |
| `HANDWEAR` | `handwear`, `hand`, `arm` | 手, 手臂, 腕, 手套 | 躯干及手臂部件 |
| `BOTTOMWEAR` | `bottomwear`, `pants`, `skirt` | 下装, 裤子, 裙子 | 下半身躯干 |
| `LEGWEAR` | `legwear`, `leg`, `legs` | 腿, 大腿 | 腿部画元 |
| `FOOTWEAR` | `footwear`, `shoe`, `shoes` | 脚, 鞋, 鞋子 | 足部画元 |

### 3. 附加与特效组件 (Extra Group)

| 语义标签 (Tag) | 推荐英文名 | 常用中文别名 | 说明与绑定行为 |
| :--- | :--- | :--- | :--- |
| `TAIL` | `tail` | 尾巴, 尾 | 兽尾等背部附加部件 |
| `WINGS` | `wings`, `wing` | 翅膀, 翼 | 羽翼等背部附加部件 |
| `OBJECTS` | `objects`, `prop`, `props` | 道具, 物件 | 角色手持或悬浮道具 |

---

## 侧别判定规则 (Side Matching)

对于成对存在的对称部件（如眼睛、眉毛、耳朵等），AutoLive2D 支持多种前缀与后缀语法进行侧别声明：

### 命名规则
- **后缀语法**：`eyelash-l`, `eyelash-r`, `eyelash_left`, `eyelash_right`, `eyelash l`, `睫毛左`, `睫毛右`
- **前缀语法**：`左-睫毛`, `右-睫毛`, `左睫毛`, `右睫毛`

> **重要：左右方向标准**
> AutoLive2D 严格遵循 **角色自身左右（Character's Own Left/Right）** 原则：
> - **角色左侧 (`-l` / `LEFT`)**：通常位于画面**右侧**（观察者视角右侧）。
> - **角色右侧 (`-r` / `RIGHT`)**：通常位于画面**左侧**（观察者视角左侧）。

---

## 8 邻域连通域自动拆分 (Auto-Splitting)

为了减轻画师的工作负担，如果画师将左右双眼、左右眉毛等合并在同一个图层中绘制（例如图层名为 `eyelash`、`eyewhite`、`irides`、`eyebrow`、`eye_close` 且**未带有 `-l/-r` 后缀**）：

1. 系统会自动对图层执行 **8 邻域连通域分析（BFS）**；
2. 过滤掉小于 12 像素的杂色噪点；
3. 提取出面积最大的两个独立连通块；
4. 校验这两个连通块是否分别位于面部中线（`faceCenterX`）的左右两侧；
5. 若条件满足，系统会自动将该图层切分为两个独立的虚拟图层（分别赋予 `:l` 与 `:r` 标识）并分别绑定对应的左右眼控制参数。

---

## 五官与特殊部件分层指南

### 1. 眼睛系统 (Eyes & Irides)

```text
[图层层序从上到下]
1. eyelash (睫毛)          -> 闭眼线覆盖
2. irides (瞳孔/虹膜)       -> 被 eyewhite 裁切，视线追踪 + 果冻回弹
3. eyewhite (眼白)         -> 提供剪切蒙版区域，闭眼时向中线收缩
```

- **眼白 (`eyewhite`)**：需绘制完整的眼眶白色区域，形状边缘需干净清晰。
- **瞳孔 (`irides`)**：即便部分被眼睑遮挡，也建议绘制完整圆形的虹膜图元。系统会自动以 `eyewhite` 作为蒙版将其限制在眼眶内，无需画师手动裁剪。
- **睫毛 (`eyelash`)**：绘制标准的睁眼状态上睫毛。系统在闭眼时会自动提取睫毛的 Alpha 权重中线，并弯曲为平滑的 U 形贴合眼眶。
- **可选闭眼贴图 (`eye_close`)**：若特定画风有专用的闭眼线，可单独提供 `eye_close` 图层，系统会在睁眼时将其透明度设为 0，闭眼时渐显。

### 2. 嘴巴与口腔系统 (Mouth & Internals)

AutoLive2D 采用了**整体口形插值机制**：

```text
[方案 A: 经典一体化嘴巴 (强烈推荐)]
- mouth (或 mouth_open)     -> 包含嘴唇、牙齿、舌头和口腔阴影的完整张口图

[方案 B: 独立口腔内部件]
- tooth-t (上牙)            -> 自动以 mouth 为剪切蒙版
- tooth-b (下牙)            -> 自动以 mouth 为剪切蒙版
- tongue (舌头)             -> 自动以 mouth 为剪切蒙版
- mouth (口腔基底与唇线)     -> 提供剪切边界并驱动闭合形变
```

- **整体嘴巴绘制要点**：
  - 请将 `mouth` 绘制为**角色最大的张口开心/说话状态**；
  - 参数 `ParamMouthOpenY` 会自动在闭合时通过中线向心挤压算法将其压缩为平滑自然的 1.25px 闭口缝；
  - 参数 `ParamMouthForm` 会自动根据嘴角曲率与宽度进行微笑/嘟嘴形变。

### 3. 头发系统 (Front & Back Hair)

- **前发 (`front hair`)**：包含刘海、鬓角等位于面部前方的发束。
- **后发 (`back hair`)**：包含后脑勺发量、披发、马尾等位于身体后方的发束。
- **物理模拟机制**：
  - 前后发会自动脱离面部 Warp，独立做头壳跟随；
  - 发根自动固定在头部锚点，发梢采用 $v^3$ 立方递增摆幅；
  - 自动根据发束的 `min(width, height)` 缩放物理响应，杜绝宽片刘海漂浮失真。

---

## 变体与副本命名 (Variants & Copies)

- **数字变体 (Variant Numbers)**：支持在末尾添加数字区分多组部件，例如 `front hair 1`, `front hair 2`, `mouth-1`, `mouth-2`。
- **Photoshop 副本清理 (Copy Suffixes)**：系统会自动识别并剥离 Photoshop 复制图层时生成的后缀：
  - 英文：`layer copy`, `layer copy 2`
  - 中文：`图层 副本`, `图层 拷贝 2`
  - 日文：`レイヤー のコピー`

---

## 未识别图层处理策略 (Unknown Layers)

如果 PSD 中的某个图层未命中任何预设的名称规则（标记为 `UNKNOWN`）：
1. **不会被丢弃**：系统仍会为其生成自适应三角网格与贴图切片；
2. **空间智能归类**：
   - 若图层的包围盒中心位于面部底部（`face.bottom`）以上，自动归入**头部容器 (`HeadContainer`)**；
   - 若图层的包围盒中心位于面部底部以下，自动归入**身体容器 (`BodyZ_Breath`)**；
3. **GUI 随时重映射**：您可以在桌面 GUI 的“图层”表格中随时将 `unknown` 更改为具体的语义标签。

---

## 分层检查清单 (Artist Checklist)

在导出 PSD 之前，请快速自检以下项目：

- [ ] 颜色模式是否为 RGB / 8 位通道？
- [ ] 背景层是否已删除（保持透明背景）？
- [ ] 左右眼白、瞳孔、睫毛是否命名规范（或未拆分但左右分布清晰）？
- [ ] 嘴巴是否绘制为完整张口图？
- [ ] 前发与后发是否拆分为独立的两个或两组图层？
- [ ] 是否清理了无用的隐藏空图层与草稿图层？

