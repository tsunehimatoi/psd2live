## v3.8.5 · 2026-11-26

### 改进与修复

- **前后分层**。修复分层后修改器报错的问题。前层跟随后层变形，分层前手工移动过的顶点和已粘贴复制件的贴图也会保留。
- **ID 转换与网格拆分**。转换网格 ID 或拆分源图层时保留修改器绑定。复制件改 ID 后仍保留绘制顺序，转换参数 ID 后参考图仍固定在原来的姿势。
- **CMO3 导出**。为导出对象补全有效的关键形和元数据，修复部分工程导出后缺失这些数据的问题。
- **工作区与文档**。左侧工作区栏可折叠，布局可在设置中调整。文档恢复中文、英文、日文和韩文切换。

Windows x64 和 Linux 安装包已重新构建，恢复 Cubism 预览。Windows ARM64 和 macOS 使用内置预览渲染器。

<details>
<summary>English</summary>

## v3.8.5 · 2026-11-26

### Improvements and fixes

- **Depth splitting.** Fixed modifier errors after creating front and back layers. The front layer follows the back layer, and splitting preserves manually moved vertices and the textures of pasted copies.
- **ID conversion and mesh splitting.** Modifier bindings follow renamed meshes and split source layers. Pasted meshes keep their draw order when renamed, and reference images stay pinned to the same pose when parameter IDs change.
- **CMO3 export.** Exported objects carry valid keyforms and metadata, fixing missing data in some exported projects.
- **Workspace and documentation.** The left workspace rail can collapse, with its layout configurable in Settings. The documentation language switcher is available in Chinese, English, Japanese, and Korean.

The rebuilt Windows x64 and Linux packages restore Cubism preview. Windows ARM64 and macOS use the built-in preview renderer.

</details>
