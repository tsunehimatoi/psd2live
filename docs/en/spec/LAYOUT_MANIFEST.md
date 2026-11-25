<!-- lang: zh en ko ja -->
> [中文](../../zh/spec/LAYOUT_MANIFEST.md) · **English** · [한국어](../../ko/spec/LAYOUT_MANIFEST.md) · [日本語](../../ja/spec/LAYOUT_MANIFEST.md)

# Layout manifest

[Documentation](../README.md) · [Usage guide](../guide/LAYOUT.md) · [PSD artwork and naming](PSD_LAYER_SPEC.md)

A layout manifest is the interchange format PSD2Live uses to read layered artwork: one JSON file that states which images make up a canvas — each image's path and pixel size, its rectangle on the canvas, stacking order, group, semantic tags, variant, visibility, opacity, and whether it is reference only. Any tool can write a manifest by this page. PSD2Live uses it to create an ungenerated layout project, or imports it as artwork.

Unlike a PSD, an image's pixel count does not have to equal its size on the canvas. A small part can carry more pixels than the canvas. PSD2Live keeps them by [per-layer size](DOCUMENT_LAYER.md#per-layer-size), so the texture is denser.

## Files and zip archives

- The manifest is a UTF-8 JSON object, extension `.json`, at most 16 MiB. Image paths are relative to the folder that contains the manifest.
- The manifest and the images can also be packed into a `.zip`: the manifest sits at the archive root, or inside the archive's only top-level folder (common when a folder is zipped directly). PSD2Live finds, at that level, the JSON that declares `format`. Image paths are relative to it. Two manifests at the same level are refused. The macOS extras `__MACOSX/` and `._*` are not content.
- An image can be any format PSD2Live can decode (PNG, WebP, JPEG, BMP, TIFF). A non-premultiplied PNG is recommended. A single image is at most 16 MP, and a single image file is at most 1 GiB. There is no cap on the images together. As with a PSD, the limit is the memory the decoded images occupy (each image is read and decoded on its own; the raw bytes do not all stay resident at once). At most 4096 layers. The same image can be referenced by several layers.

## Structure

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

### Top level

| Field | Required | Description |
| --- | --- | --- |
| `format` | yes | Fixed as `psd2live-layout` |
| `version` | yes | An integer, currently `1`. PSD2Live refuses a version newer than itself |
| `canvas` | yes | `{width, height}`: canvas size, integer canvas units, each side 1–8192, and the total at most 16 MP. A canvas unit is a pixel of the model canvas after export to Cubism |
| `layers` | yes | The layer array, 1–4096 items. Order is described in [Stacking order](#stacking-order) |
| `groups` | no | Group properties: `path` (required), `visible` (default `true`), `opacity` (0–1, default `1`). Paths cannot repeat |
| `name` | no | The manifest's name, used as the artwork folder name. When omitted, the file name is used |

### Layers

| Field | Required | Description |
| --- | --- | --- |
| `name` | yes | The layer name, at most 128 printable characters |
| `image` | yes | `{path, width, height}`: the image path (relative, separated by `/`, with no `..` or `.` segment, no backslash, and no drive letter, and it cannot escape the manifest's folder or that level of the archive) and the image's pixel size. The size must match the image |
| `rect` | yes | `{left, top, width, height}`: the canvas rectangle the image fills, in floating-point canvas units, with positive width and height. It may extend outside the canvas. The ratio of pixel size to the rectangle is the texture density: a `126 × 147` image laid on a `101.65 × 118.63` rectangle is about 1.24 texture pixels per canvas unit |
| `id` | no | The layer ID, `[A-Za-z0-9_.:#-]{1,96}`, unique in the manifest. Later edits and MCP refer to the layer by it. When omitted, it is derived from the index and the name, and the same manifest yields the same ID every time |
| `group` | no | The group path, names joined by `/`, such as `head/eyes`. Each level becomes a source-image group |
| `tags` | no | An array of strings: semantic tags and user tags. See [Tags](#tags) |
| `variant` | no | The variant. See [Variants](#variants) |
| `visible` | no | Default `true` |
| `opacity` | no | 0–1, default `1` |
| `reference` | no | Default `false`. `true` is a [reference layer](#reference-layers) |

Unknown fields are ignored. Fields added within the same version are all optional. When the meaning of an existing field changes, the version number increases by one.

### Stacking order

`layers` is ordered from back to front: a layer listed later is drawn above a layer listed earlier. This is the same order as PSD2Live source-image layers (painter's order, which is also the order PSD2Live gets from reading a PSD), and it is the direction in which Cubism draw order increases: the draw order of layer i is not lower than that of layer i−1.

PSD2Live's layer table lists layers in this order, and generation uses it for the base draw order. Generators such as the mouth have their own rules. A setting on the draw-order ruler takes priority.

### Tags

- Part words and `left` / `right` in `tags` decide the part and the side. For example `["eye", "iris", "left"]` is the left iris, and `["hair", "front"]` is the front hair. The vocabulary and the combinations are in [Semantic tags](PSD_LAYER_SPEC.md#semantic-tags). The other words are user tags. They are only for organizing and search, and they do not affect generation.
- When a part word is present, both the part and the side come from `tags` (no side word means no side). When there is no part word, or `tags` is omitted, the part and the side are recognized from the layer name, by the same rules as a PSD layer (see [Name reference](PSD_LAYER_SPEC.md#name-reference)). Side words still in `tags` decide the side in that case.
- Left and right are the character's own left and right: the character's left is usually on the right of the picture.
- One layer cannot have both `left` and `right`.

### Reference layers

A layer with `reference` set to `true` is reference only: for example the original image before parts were separated, used to compare placement and drawing. It has order, a group, and opacity like any other layer, and it is shown on the canvas, but generators do not read it. The rig and every export omit it. It does not need tags. After opening, it can be restored to a model layer at any time, or another ungenerated layer can be set as reference (see the artwork and layout implementation).

### Variants

`variant` is `{type, parameter, switch_id}`, the same as MCP `layer_set_variant`:

| `type` | Meaning | `parameter` | `switch_id` |
| --- | --- | --- | --- |
| `none` | No variant (the default) | omitted | omitted or `0` |
| `toggle` | Shown or hidden by a parameter | required | omitted or `0` |
| `switch` | The `switch_id`-th choice of the parameter | required | a non-negative integer |

## How PSD2Live reads it

- **Open as a project**: On the Start page or in the open dialog, choose the manifest or the zip, drop it into the window, or use MCP `project_import_layout`. This creates an ungenerated layout project and enters the layout workspace: the canvas is `canvas`, layers are placed as the manifest says, and images keep their original pixels laid on the rectangles. Tags and variants are written as layer classification, user tags become layer tags, and a `reference` layer becomes a reference layer. Each layer is also registered as artwork (in a folder named `name`) and linked to it. Placement and stacking order are then adjusted as usual, and generation comes after that. The project does not keep the manifest or the image files. Pixels are stored in the project history.
- **Import as artwork**: Import from the artwork panel, or MCP `material_import`. This produces a folder named `name`, with groups as subfolders. Each item's default placement is its `rect`, pixels stay at their original resolution, and they carry `tags`. An item from a `reference` layer is marked to be placed as reference by default. A variant belongs to the layer and does not travel with the artwork. Placing the whole folder keeps the manifest's stacking order. Scanning a folder skips JSON and zip inside it, so the manifest's images are imported once as ordinary images.
- Reading stops at any error and states the location (for example `layers[3]: parts/face.png is 808x935 pixels, the manifest says 800x935`). There is no partial import.

## Verification

`LayoutManifestScenario` covers: opening a manifest as an ungenerated project, with images kept at the manifest's pixels and rectangles (a part with 3× pixels keeps its density, including fractional rectangles); stacking order, groups and group opacity, tag classification and user tags, variants, opacity, artwork registration and links; a reference layer stays out of the rig, still stays out after generation, and after restore waits and enters the next generation; generating after a stacking-order change, a generated layer refusing a further change, and save and reopen; a root zip and a top-folder zip reading as the same content; import as artwork carrying the default placement and tags, and a folder scan skipping the manifest; an out-of-bounds path, a size mismatch, a newer version, another format, and two manifests in one archive being refused; placing a whole artwork folder of a PSD or a manifest keeping the original stacking order; reference artwork placed by its mark, with a different choice available at placement time.
