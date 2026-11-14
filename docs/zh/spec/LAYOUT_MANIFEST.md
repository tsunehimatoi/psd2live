# Layout 清单

[文档目录](../../README.md) · [使用指南](../guide/LAYOUT.md) · [PSD 素材与命名](PSD_LAYER_SPEC.md)

Layout 清单是 PSD2Live 读取分层素材的交换格式：一个 JSON 文件，写明一张画布由哪些图片组成——每张图片的路径与像素尺寸、在画布上的矩形、上下顺序、所在的组、语义标签、差分、可见性、不透明度，以及是否只作参考。任何工具都可以按本页写出清单；PSD2Live 用它新建未生成的 Layout 工程，或把它导入为素材。

与 PSD 不同，图片的像素数不必等于它在画布上的大小：小部件可以带比画布更多的像素，PSD2Live 按[逐层尺寸](DOCUMENT_LAYER.md#逐层尺寸)保留它们，纹理因此更密。

## 文件与压缩包

- 清单是 UTF-8 编码的 JSON 对象，扩展名 `.json`，不超过 16 MiB。图片路径相对清单所在的文件夹。
- 也可以把清单和图片打成 `.zip`：清单放在压缩包根目录，或放在压缩包唯一的顶层文件夹里（直接压缩一个文件夹时常见）。PSD2Live 在那一级找到声明了 `format` 的那个 JSON，图片路径相对它；同一级有两份清单时拒绝读取。macOS 附加的 `__MACOSX/` 与 `._*` 不算内容。
- 图片可以是 PSD2Live 能解码的任一格式（PNG、WebP、JPEG、BMP、TIFF），建议用非预乘 alpha 的 PNG。单张不超过 16 MP，单个图片文件不超过 1 GiB；图片合计不设上限，与 PSD 一样只受解码后占用的内存限制（逐张读取、解码，原始字节不同时驻留）。最多 4096 个图层。同一张图片可以被多个图层引用。

## 结构

```json
{
  "format": "psd2live-layout",
  "version": 1,
  "name": "character",
  "canvas": { "width": 3258, "height": 4344 },
  "groups": [
    { "path": "head", "visible": true, "opacity": 1 }
  ],
  "layers": [
    {
      "name": "source",
      "image": { "path": "source.png", "width": 3258, "height": 4344 },
      "rect": { "left": 0, "top": 0, "width": 3258, "height": 4344 },
      "reference": true,
      "opacity": 0.5
    },
    {
      "name": "back hair",
      "image": { "path": "parts/back_hair.png", "width": 484, "height": 601 },
      "rect": { "left": 804.3, "top": 207.0, "width": 1642.6, "height": 2039.6 },
      "tags": ["hair", "back"]
    },
    {
      "id": "iris_l",
      "name": "iris L",
      "image": { "path": "parts/iris_l.png", "width": 126, "height": 147 },
      "rect": { "left": 1724.24, "top": 745.36, "width": 101.65, "height": 118.63 },
      "group": "head/eyes",
      "tags": ["eye", "iris", "left"]
    },
    {
      "name": "mouth open",
      "image": { "path": "parts/mouth_open.png", "width": 183, "height": 78 },
      "rect": { "left": 1555.6, "top": 935.8, "width": 147.6, "height": 62.9 },
      "group": "head",
      "tags": ["mouth", "open"],
      "variant": { "type": "toggle", "parameter": "ParamMouthOpen" },
      "opacity": 1,
      "visible": true
    }
  ]
}
```

### 顶层

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `format` | 是 | 固定为 `psd2live-layout` |
| `version` | 是 | 整数，当前为 `1`。PSD2Live 拒绝读取比自己新的版本 |
| `canvas` | 是 | `{width, height}`：画布尺寸，整数画布单位，单边 1–8192、合计不超过 16 MP。画布单位就是导出到 Cubism 后模型画布的像素 |
| `layers` | 是 | 图层数组，1–4096 项，顺序见[上下顺序](#上下顺序) |
| `groups` | 否 | 组的属性：`path`（必填）、`visible`（默认 `true`）、`opacity`（0–1，默认 `1`）。路径不能重复 |
| `name` | 否 | 清单的名称，用作素材文件夹名；省略时用文件名 |

### 图层

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 图层名，不超过 128 个可打印字符 |
| `image` | 是 | `{path, width, height}`：图片路径（相对路径，用 `/` 分隔，不含 `..`、`.` 段、反斜杠、盘符，不能跳出清单所在的文件夹或压缩包那一级）与图片的像素尺寸；尺寸必须与图片一致 |
| `rect` | 是 | `{left, top, width, height}`：图片铺满的画布矩形，浮点画布单位，宽高为正，可以超出画布。像素尺寸与矩形之比就是纹理密度：`126 × 147` 的图片铺在 `101.65 × 118.63` 的矩形上，每画布单位约 1.24 个纹理像素 |
| `id` | 否 | 图层 ID，`[A-Za-z0-9_.:#-]{1,96}`，清单内唯一。之后的编辑与 MCP 按它引用图层；省略时由序号和名称派生，同一份清单每次得到相同的 ID |
| `group` | 否 | 组路径，名称用 `/` 连接，如 `head/eyes`；每一级都成为源图组 |
| `tags` | 否 | 字符串数组：语义标签与用户标签，见[标签](#标签) |
| `variant` | 否 | 差分，见[差分](#差分) |
| `visible` | 否 | 默认 `true` |
| `opacity` | 否 | 0–1，默认 `1` |
| `reference` | 否 | 默认 `false`。`true` 为[参考图层](#参考图层) |

未知字段被忽略；同一版本里新增的字段都是可选的，改变已有字段含义时版本号加一。

### 上下顺序

`layers` 从后往前排列：后列出的图层画在先列出的图层之上。这与 PSD2Live 源图的图层顺序相同（画家顺序，也是 PSD2Live 读 PSD 得到的顺序），也就是 Cubism 描画顺序递增的方向：第 i 个图层的描画顺序不低于第 i−1 个。

PSD2Live 的图层表按这个顺序列出图层，生成时按它决定基础绘制顺序；嘴部等生成器另有规则，描画顺序标尺上的设置优先。

### 标签

- `tags` 中的部位词与 `left` / `right` 决定部件与侧别，例如 `["eye", "iris", "left"]` 是左瞳孔、`["hair", "front"]` 是前发；词表与组合见[语义标签](PSD_LAYER_SPEC.md#语义标签)。其余的词是用户标签，只用于整理与搜索，不影响生成。
- 含部位词时，部件与侧别都取自 `tags`（没有侧别词即无侧别）。不含部位词或省略 `tags` 时，按图层名识别部件与侧别，规则与 PSD 图层相同（见[名称参考](PSD_LAYER_SPEC.md#名称参考)），此时 `tags` 里的侧别词仍然决定侧别。
- 左右指角色自身的左右：角色左侧通常在画面右边。
- 同一图层不能同时有 `left` 与 `right`。

### 参考图层

`reference` 为 `true` 的图层只作参考：例如拆件前的原图，用来对照摆放、描画。它和其他图层一样有顺序、组与不透明度，显示在画布上，但生成器不读它，Rig 与所有导出都不含它，不需要标签。打开后可以随时把它恢复为模型图层，或把其他未生成的图层设为参考（见素材与 Layout 实现）。

### 差分

`variant` 为 `{type, parameter, switch_id}`，与 MCP `layer_set_variant` 相同：

| `type` | 含义 | `parameter` | `switch_id` |
| --- | --- | --- | --- |
| `none` | 无差分（默认） | 不给 | 不给或 `0` |
| `toggle` | 由参数显示或隐藏 | 必填 | 不给或 `0` |
| `switch` | 参数的第 `switch_id` 个选择 | 必填 | 非负整数 |

## PSD2Live 如何读取

- **打开为工程**：「开始」页或打开对话框选择清单或 zip、把它拖进窗口，或 MCP `project_import_layout`。新建未生成的 Layout 工程并进入 Layout 工作区：画布为 `canvas`，图层按清单摆放，图片保留原像素铺在矩形上；标签和差分写成图层分类，用户标签成为图层标签，`reference` 图层成为参考图层；每个图层同时登记为素材（放在以 `name` 命名的文件夹里）并与之关联。之后照常调整摆放与上下顺序，再生成。工程不保留清单与图片文件，像素存进工程历史。
- **导入为素材**：素材面板导入或 MCP `material_import`。得到一个以 `name` 命名的文件夹，组成为子文件夹；每个条目的默认摆放就是它的 `rect`，像素保持原分辨率，带上 `tags`；`reference` 图层的条目标为默认作为参考放置。差分属于图层，不随素材。放置整个文件夹时保持清单的上下顺序。扫描文件夹时跳过其中的 JSON 与 zip，清单的图片因此只按普通图片导入一次。
- 读取在任何错误处停止并说明位置（如 `layers[3]: parts/face.png is 808x935 pixels, the manifest says 800x935`），不会部分导入。

## 验证

`LayoutManifestScenario` 覆盖：清单打开为未生成工程，图片按清单的像素与矩形保留（3 倍像素的部件保持密度、分数矩形），上下顺序、组与组不透明度、标签分类与用户标签、差分、不透明度，素材登记与关联，参考图层不进入 Rig、生成后仍不进入、恢复后等待并进入下一次生成；调整上下顺序后生成、生成后的图层拒绝调整、保存重开；根目录与顶层文件夹两种 zip 读出相同内容，导入为素材时带默认摆放与标签，扫描文件夹时跳过清单；越界路径、尺寸不符、更新的版本、其他格式与一个压缩包里两份清单被拒绝；放置 PSD 或清单的整个素材文件夹保持原来的上下顺序；参考素材按标记放置，放置时可另行指定。
