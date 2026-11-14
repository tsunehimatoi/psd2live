## v3.8.0 · 2026-11-15

### 新功能

- **修改器菜单与冲突提示**：预设添加菜单改为分类、基础项与详情三栏渐进展开，在修改器栈与添加菜单中标出硬冲突与软冲突。
- **部位插件嵌套设置与行为预览**：检查器支持展开折叠部位的子功能配置并实时预览行为，重置部位时同步清理继承属性。

### 改进

- **预设与术语统一**：统一「风格化」等算法术语与预设命名，调整默认预设配置（默认关闭位移，区分裙摆与挤压等可选模块）。
- **源 PSD 绑定与导出一致性**：将网格拆分纳入 `mesh.split` 修改器插件并保留 `.cmo3` 导出与读回的拆分网格引用；完善单眼、分层嘴部及兽耳/尾巴/翅膀的源 PSD 绑定与导出。
- **预览响应与缓存性能**：局部构建计划与有界缓存减少重复重建开销，确保编辑后的预览首帧与保存状态准确同步。

<details>
<summary>English</summary>

## v3.8.0 · 2026-11-15

### New

- **Modifier menu & conflict indicators**: the preset add menu is reorganized into a progressive 3-column layout (category, base, details), marking hard and soft modifier conflicts in both the stack and add menu.
- **Nested part settings & behavior preview**: collapsed part plugins expose nested sub-feature controls with live behavior preview in the inspector, and resetting a part clears inherited properties cleanly.

### Improved

- **Preset & terminology alignment**: unified "Stylized" algorithm terminology and preset names, with refined default preset options (displacement off by default, skirt and squash separated cleanly).
- **Source PSD binding & export parity**: integrated mesh splitting as the `mesh.split` modifier plugin while preserving split mesh references on `.cmo3` round-trip, and improved source-frame bindings for single eyes, layered mouths, and creature attachments.
- **Preview responsiveness & caching**: scoped build plans and bounded caches reduce redundant rebuilds and keep edited frames and save status synchronized.

</details>
