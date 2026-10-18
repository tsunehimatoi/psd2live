# 实战拆分：从立绘到可直播的 Live2D 模型

规划器只给大致遮罩；拆成哪些层、每层的 hint 由哪些规划区域组成、提示词怎么写，按图人工写在拆分表（`specs/<图>.json`）里。每层由拆分 LoRA 从原图特写整张生成，去背景后按生成尺寸铺回画布，不贴、不切、不合成任何像素。结果与教训见 [PAPER](../PAPER.md#8-实战拆分实测)。

工作目录（环境变量 `L2D_LIVE`，默认当前目录）下放 `art/`（立绘，带透明背景）、`plan/`（`scene/split_image.py --planner5 --plan-only` 的输出）、`specs/` 与 `split/`。ComfyUI 地址见 `comfy/env.py`。

| 步骤 | 脚本 | 说明 |
|---|---|---|
| 新立绘 | `gen_art.py`、`matte_art.py` | Klein 4B 文生图，再用 BiRefNet 去背景供规划器使用 |
| 规划 | `../scene/split_image.py IMG plan/NAME --planner5 CKPT --plan-only` | 区域编号见 `plan.json` |
| 张嘴素材 | `open_mouth.py` | 原图闭嘴时，在头部特写上让 Klein 改成张嘴，作为嘴部层的来源图 |
| 生成候选 | `live_split.py specs/X.json split/X [层...]` | 每层每个 LoRA 按种子出候选（matting 权重去背景），按与原图的颜色误差、hint 覆盖、溢出、灰底残留排序 |
| 重新去背景 | `rematte.py split/X` | 用 BiRefNet 通用权重再抠一遍（`<候选>_g.png`）：matting 权重会把 LoRA 在灰底上画出的柔和阴影保留成半透明，浅色部件上会出现污块 |
| 挑选 | `review.py split/X out.png [层...]` | 每层：特写、hint、排序前 6 个候选 |
| 组装 | `assemble.py specs/X.json split/X picks/X.json OUT` | 写出 PSD2Live Layout 清单，原图作参考层 |
| 验收 | `LayoutRigCheckTool` | `PSD2LIVE_TOOLS=1 PSD2LIVE_LAYOUT=OUT/layout.json [PSD2LIVE_EXPORT=1] ./gradlew test --tests '*LayoutRigCheckTool'`：眨眼、视线、张嘴、转头、转身、呼吸，可导出 moc3 |

拆分表字段见 `live_split.py` 文件头；`picks/` 是每张图最终选中的候选（`重试层/候选` 取同一层用另一提示词重跑的结果，`-` 表示不要这一层）。
