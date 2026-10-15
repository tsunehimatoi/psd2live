# ComfyUI 重绘拆件工具

用 ComfyUI 上的生成模型把一张原画按部件拆开。这里只负责出图，裁特写、对齐、剥离合成、摆放、分类、生成 Rig 和检查都在 PSD2Live 里完成，通过 MCP（[`mcp_proxy.py`](../../mcp_proxy.py) 的 `Client`）调用。

- `comfy.py`：只用标准库的 ComfyUI 客户端（上传、提交、等待、下载）。
- `workflows.py`：各模型的工作流构建；模型文件名在 `MODELS` 中，按服务器实际文件修改。
- 服务器地址与令牌只从环境变量读取：`PSD2LIVE_COMFY_URL`，需要时 `PSD2LIVE_COMFY_TOKEN`。不要写进任何文件。

```bash
python -m unittest discover -s tools/comfy -p "test_*.py"
```

## 流程

1. PSD2Live：`project_create` → `material_import` 原画 → `layout_set_reference` → `material_crop`（`canvas_rect`）按部件截特写，头部、眼睛、嘴各一张 → `material_export_png`。
2. ComfyUI：特写放大（`upscale`，再用 `sdxl_inpaint` 整图蒙版 + `union="tile"`、denoise 0.3–0.45 锐化，保留原画的线条与配色），得到高清底图。
3. PSD2Live：`material_import` 高清底图 → `material_set_placement`（`from` 特写）。高清图摆在特写的矩形里，多出的像素就是更高的纹理密度。
4. 从最前面的部件开始，逐层剥离：
   - ComfyUI：在当前底图上去掉这个部件（`klein`），尺寸与输入相同，输出与输入像素对齐；需要时用 `sam3` 出部件蒙版；
   - PSD2Live：`material_import` 生成结果和蒙版，`material_set_placement` 同上，然后 `material_peel`（`id` 当前底图，`revealed` 生成结果，`region` 蒙版）。得到部件和它下面的新底图，部件之外保留原像素，叠回去就是原图；
   - 下一个部件在新底图上继续。
5. `layer_place` 各部件 → `layer_tag` → `view_render_model`（`reference` 差值看对齐、`density` 看纹理密度）→ `rig_generate` → `view_render_poses` 检查转头、眨眼时露出的部分。

## 模型分工

| 用途 | 构建函数 | 说明 |
|---|---|---|
| 高清底图 | `upscale` + `sdxl_inpaint(union="tile")` | 保留原画风格；Klein 重绘会把线条变成硬黑描边、颜色发白 |
| 去掉部件、露出下面 | `klein` | 与输入像素对齐，约 25 秒；会轻微改动整张图，所以只取被揭开区域（`material_peel` 处理） |
| 去掉部件、换装 | `kontext`（可带 LoRA） | Flux.1 Kontext，自然语言指令；尺寸保持 16 的倍数 |
| 新图 | `sdxl_txt2img` | 用标签生成参考图，再放大与 tile 锐化 |
| 去掉部件（不推荐） | `qwen_edit` | 慢，实测效果不如 Klein，会偏移一两个像素并改动别处 |
| 按蒙版补画 | `sdxl_inpaint`（`loras` 可叠加 LoRA） | 补虹膜被挡的部分、发帘内侧等；Klein 不会重画平涂色块 |
| 部件蒙版 | `sam3` | 短英文名词；成对部件用 `separate` 或点选 |
| 半透明剪影 | `matte` | 发丝边缘 |
| 前后顺序 | `depth` | 在人物范围内读相对深度 |

`klein(base=True)` 能用 cfg 和负面词，但慢约 8 倍，实测效果不比蒸馏版好。

## 提示词

- **Flux 系（Klein、Kontext）写自然语言指令**：说要去掉什么、下面露出什么，再加一句保持其余不变。

  > Remove the front bangs and every hair strand hanging in front of the forehead and face. Show the bare forehead skin and the natural hairline underneath, with the eyebrows fully visible. Keep the eyes, face, side hair, braid, hair clip, colors, lighting and art style exactly the same, same framing.

- **SD 系（WAI、NoobAI）写 danbooru 标签**：质量词放后面，负面词列出不想要的东西。NoobAI v-pred 的检查点名含 `Vpred` 时自动设置采样。

  > 正面：`no humans, close-up, eye focus, aqua eyes, detailed eyes, gradient eyes, anime coloring, masterpiece, best quality`
  > 负面：`eyelashes, highlight, skin, lowres, worst quality, blurry`

## 部件经验

- **被挡的部分按画师习惯补**：前发下是额头皮肤直到发际线；眼白、虹膜都是完整形状；后发被挡处是发帘内侧（朝向脖子、偏暗），不是后脑。
- **虹膜不能直接抠**：上半部被睫毛和眼睑挡住。形状由人定（按露出的下缘拟合椭圆），看得见的像素保留，被挡与高光处用 `sdxl_inpaint` 补。
- **高光单独成层**：Klein 去高光很稳，`material_peel` 直接得到高光层。
- **小部件放大**：头部 2 倍、眼和嘴 6–8 倍裁切后再生成。
