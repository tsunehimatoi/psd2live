导入图片重做：一次提交带上网格，移动、缩放、旋转与其他对象一样流畅，旋转过的图层也能直接绘画；删除图层改为真删除；导出 CMO3 时另存 Cubism Animator 工程（.can3）；改生成设置、拆分与长编辑历史更快更稳；导出的模型与预览逐字节一致。

> 编辑过的工程会以新的修订格式保存，旧版本程序无法打开。

## 主要更新

### 新增

- **导入图片重做**：导入的图片一次提交就带着自己的网格，直接用变换工具移动、缩放、旋转，拖动时不再逐帧重建整个模型（`tml` 示例上每次提交约 40–60 毫秒）；撤销即取消导入，挂到任意变形器下后仍可移动，像素从不重新采样。
- **图层整体变换**：任何图层都可整体移动、缩放、旋转（MCP `layer_transform`），不触发再生成；旋转、缩放过的图层可直接绘画，笔刷大小按图层自身像素换算，屏幕上大小不变，光标圈与笔触一致。
- **删除图层改为真删除**：像素、网格及引用它们的遮罩、Glue、路径和权重一起移除，撤销即恢复。MCP `layer_soft_delete` 改为 `layer_delete`。
- **网格重建与覆盖提示**：网格面板显示顶点数与边长，可“按当前像素重建”（MCP `layer_mesh_rebuild`）；像素落在网格外时提示不会显示的比例。图层面板悬停显示原图、纹理块与画布尺寸。
- **导出 Cubism Animator 工程（.can3）**：导出 CMO3 时，动作另存为同名 `.can3`，每个动作一个场景，可在 Cubism Editor 的 Animator 中继续编辑。
- **预览生成结果更新**：“工具 → 预览生成结果更新”先报告更新会改变什么，不改动工程；MCP `workspace_preview_regeneration` 可试运行会重新生成 Rig 的编辑。
- **MCP**：`project_export_target` 按单个目标导出；`simulation_put` 新增 `obstacles`，可让头发与布料避开手臂、腿、躯干。

### 改进

- **生成与编辑合并更稳**：改脸部、站姿、骨架、分类等生成设置或拆分图层时，新的生成结果与已有编辑直接合并并固定保存；拆分出的部件继续跟随生成，原层上的关键形、路径、权重与胶水带到部件上。同时改生成设置又改写较早编辑的提交会被拒绝并提示分开提交。
- **长编辑历史不再变慢**：模型定期固定保存，提交、撤销、重做与拖动只在最近的固定点之后重算；旧工程第一次编辑时固化一次，此后不再按旧记录重放。
- **打开与保存工程显示进度**：状态栏显示当前步骤和百分比。
- **模拟更快、起步更稳**：参考模拟、实时模拟与烘焙快约三分之一；重置后的轻微晃动从约 3–6 px 减到 1–1.5 px。模拟结果因此有变化，已有烘焙会显示为过期，重新烘焙即可。

### 移除

- `asset_import_png` 的 `spatial_reference_id`；“工具 → 升级拆分记录”与 MCP `source_upgrade_split_records`（不再需要）。

### 修复

- **导出与预览一致**：导出 Live2D 模型直接写出已提交的模型，不再从源图重新生成，导出的 moc3 与预览逐字节相同。
- **层级拖放改父级**：网格拖到另一个变形器下时留在画布原位，不再飞出画布、缩成一点或消失。
- **导入图片**：高分辨率图片不再报“does not fit an atlas page”；可在层级面板拖到其他父级下；头部倾斜的角色上导入到头部变形器不再偏移。
- **拆分后修改图层分类**不再报“Art primitive part was not generated”或“Mesh creation artwork is missing”。
- **纹理集**：在图层上绘画超出原网格后，嵌套的相邻图块不再互相覆盖。
- **烘焙摆动**：在编辑过的模型上删除摆动并保留为关键形不再报错。

<details>
<summary>English</summary>

Image import is rebuilt: an import commits with its own mesh and moves, scales and rotates as smoothly as any other object, and rotated layers can be painted directly. Deleting a layer now really deletes it. Exporting CMO3 also writes a Cubism Animator project (.can3). Generation setting changes, splits and long edit histories are faster and steadier, and the exported model matches the preview byte for byte.

> Edited projects are saved in a new revision format that earlier versions cannot open.

## Highlights

### New

- **Image import rebuilt**: an imported image commits with its own mesh and is moved, scaled and rotated with the transform tools, without rebuilding the whole model each frame (about 40–60 ms per commit on the `tml` sample); undo cancels the import, it can still be moved once hung under any deformer, and its pixels are never resampled.
- **Whole-layer transforms**: any layer can be moved, scaled or rotated as a whole (MCP `layer_transform`) without regenerating; rotated or scaled layers can be painted directly, with brush sizes converted to the layer's own pixels so they look the same on screen and the cursor ring matches the stroke.
- **Deleting a layer deletes it**: its pixels and meshes leave the project with the masks, glue, paths and weights that refer to them; undo restores it. MCP `layer_soft_delete` becomes `layer_delete`.
- **Mesh rebuild and coverage hints**: the mesh panel shows vertex count and edge length and can rebuild from the current pixels (MCP `layer_mesh_rebuild`); it warns how much of the art falls outside the mesh. Hovering a layer shows its source, texture tile and canvas sizes.
- **Cubism Animator project export (.can3)**: exporting CMO3 also writes the motions to a `.can3` of the same name, one scene per motion, to keep editing in Cubism Editor's Animator.
- **Preview a generation update**: Tools → Preview generation update reports what an update would change without touching the project; MCP `workspace_preview_regeneration` dry-runs edits that regenerate the rig.
- **MCP**: `project_export_target` exports one target; `simulation_put` adds `obstacles` that keep hair and cloth out of arms, legs and the torso.

### Improvements

- **Generation and edits merge steadily**: changing face, stance, skeleton or classification settings, or splitting a layer, merges the new generation with your edits and stores the result; split pieces keep following generation and inherit the keyforms, paths, weights and glue of the source layer. A commit that changes generation settings and rewrites earlier edits at once is refused with a hint to split it.
- **Long histories stay fast**: the model is pinned periodically, so commits, undo, redo and drags recompute only past the latest pin; older projects are materialized once on their first edit.
- **Open and save show progress** in the status bar.
- **Faster, steadier simulation**: reference, live and baked simulation are about a third faster; the twitch after a reset drops from about 3–6 px to 1–1.5 px. Existing bakes show as stale; bake again.

### Removed

- `spatial_reference_id` of `asset_import_png`; Tools → Upgrade split records and MCP `source_upgrade_split_records` (no longer needed).

### Fixes

- **Export matches the preview**: Export Live2D model writes the committed model instead of regenerating it, so the exported moc3 equals the preview byte for byte.
- **Reparenting in the hierarchy**: a mesh dragged under another deformer stays where it shows on the canvas instead of flying off, shrinking to a dot or vanishing.
- **Imported images**: large images no longer fail with "does not fit an atlas page", can be dragged under other parents in the hierarchy, and no longer shift when imported under the head deformers of a tilted head.
- **Changing a layer's classification after a split** no longer fails with "Art primitive part was not generated" or "Mesh creation artwork is missing".
- **Texture atlas**: painting past a layer's mesh no longer makes nested neighbouring tiles overwrite each other.
- **Swing bakes**: deleting a swing and keeping it as keyforms on an edited model no longer fails.

</details>
