## v3.8.1 · 2026-11-17

### 改进与修复

- **PSD 导入与插件隔离**：增强不规则 PSD 输入的容错；单个修改器失败时保留其错误信息并隔离依赖，其他可用功能继续运行。
- **眉毛与变形精度**：修复眉毛形状、眨眼与弹性绑定组合时的兼容问题，提高大画布下的变形求逆精度。
- **眼睛透视遮罩**：刘海已有裁剪时保留原遮罩并正确叠加眼部透视；不支持组合遮罩的导出目标会报告功能损失。
- **自定义插件开发**：支持插件模板、构建、参数配置与可撤销的代码更新，修复依赖锁定及旧构建产物的重载兼容性。
- **桌面兼容性**：改善 Linux 窗口移动与文件选择器兼容性，修复透明背景会话失败后的恢复。

<details>
<summary>English</summary>

## v3.8.1 · 2026-11-17

### Improvements and fixes

- **PSD import and plugin isolation**: handle irregular PSD input more robustly; failed modifiers retain error details and isolate dependent features while other features continue working.
- **Brows and deformation precision**: fix composition of brow shape, blink and spring bindings, and improve inverse deformation precision on large canvases.
- **Eye see-through masks**: preserve existing hair clipping while adding eye windows; export targets without composite masks report the feature loss.
- **Custom plugin development**: add plugin scaffolding, builds, parameter controls and undoable code updates; fix dependency locks and reloading older build artifacts.
- **Desktop compatibility**: improve Linux window movement and file selection, and recover from failed transparent-background sessions.

</details>
