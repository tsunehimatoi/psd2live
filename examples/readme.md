# 示例素材与输出

[项目首页](../README.md) · [操作速查](../docs/zh/guide/USER_GUIDE.md)

| 示例 | 输入 | 现有输出 | 素材说明 |
| --- | --- | --- | --- |
| tml | [tml.psd](tml/psd-input/tml.psd) | [文件目录](tml/moc3-cmo3-output/) | [作者说明](tml/readme.md) |
| ds | [ds.psd](ds/psd-input/ds.psd) | [文件目录](ds/moc3-cmo3-output/) | [来源与权利说明](ds/readme.md) |

可通过程序的“导入 PSD”打开输入，或从仓库根目录运行：

```powershell
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

现有输出是示例，不是当前版本的固定验收基准；算法、设置和版本变化会影响结果。生成新结果建议使用独立目录，便于对照。

运行时加载应从 `.model3.json` 开始，并保留其引用的 `.moc3`、纹理、物理与动作文件。`.cmo3` 用于编辑器检查；`.psd2live.json` 是诊断报告。

代码许可证不自动覆盖素材权利，请先阅读对应说明。
