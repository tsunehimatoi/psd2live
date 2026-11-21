## v3.8.4 · 2026-11-21

### 改进与修复

- **重新加载 PSD**：新增「模型 > 重新加载 PSD」，在修改源图后直接更新工程内的图层贴图，同时保留已有绑定、工程文件与撤销历史；必要时自动重建网格并迁移变形、权重与路径绑定。([#97](https://github.com/tsunehimatoi/psd2live/pull/97))
- **完整像素网格覆盖**：新增可选网格设置，覆盖所有非透明像素（alpha > 0），避免羽化边缘、微弱发光和细发丝在网格边界被裁切。([#96](https://github.com/tsunehimatoi/psd2live/pull/96))
- **变形与参数编辑**：曲面变形器支持同时开启顶点与贝塞尔两个编辑层级，无需切换即可配合微调；拖动控制点、手柄或变换框时按住 `Shift` 可锁定主轴移动；编辑中间姿态时自动吸附到最近关键形并提示，同时支持修改器混合形覆盖编辑与组件关键形高亮。([#81](https://github.com/tsunehimatoi/psd2live/pull/81), [#94](https://github.com/tsunehimatoi/psd2live/pull/94), [#95](https://github.com/tsunehimatoi/psd2live/pull/95))
- **历史与图层修复**：撤销、重做或切换历史记录时保留当前参数姿态，不再重置为默认值；修复拆分图层删除后仍残留在可见图层分析中的问题。([#92](https://github.com/tsunehimatoi/psd2live/pull/92), [#93](https://github.com/tsunehimatoi/psd2live/pull/93))
- **界面整理**：统一开始页与可折叠的帮助/设置侧栏，优化矢量图标绘制与下拉菜单自适应宽度，并将变形工具选项收拢为更紧凑的下拉菜单。

<details>
<summary>English</summary>

## v3.8.4 · 2026-11-21

### Improvements and fixes

- **Reload PSD** — Use **Model > Reload PSD** to refresh layer artwork from an updated PSD while keeping the existing rig, project file, and undo history. Meshes that would clip new pixels are rebuilt automatically with their forms, weights, and paths preserved. ([#97](https://github.com/tsunehimatoi/psd2live/pull/97))
- **Full alpha pixel mesh coverage** — Added an optional mesh setting to cover every pixel with alpha > 0, keeping soft antialiased edges, faint glows, and fine hair strands intact at the mesh border. ([#96](https://github.com/tsunehimatoi/psd2live/pull/96))
- **Deform and parameter editing** — Warp deformers can now enable vertex and Bezier editing levels together, and holding `Shift` while dragging points, handles, or the transform box constrains movement to the primary axis. Editing intermediate poses snaps parameters to their nearest keyforms with a toast hint, alongside modifier blend-shape override support and component keyform highlighting. ([#81](https://github.com/tsunehimatoi/psd2live/pull/81), [#94](https://github.com/tsunehimatoi/psd2live/pull/94), [#95](https://github.com/tsunehimatoi/psd2live/pull/95))
- **History and layer fixes** — Undo, redo, and history checkouts now preserve the current parameter pose instead of resetting to defaults. Fixed deleted pieces of a split layer remaining in the visible layer analysis. ([#92](https://github.com/tsunehimatoi/psd2live/pull/92), [#93](https://github.com/tsunehimatoi/psd2live/pull/93))
- **UI polish** — Unified the start hub with a collapsible rail for help and settings, refined vector icon rendering and auto-sized dropdown menus, and compacted deform tool options into dropdowns.

</details>
