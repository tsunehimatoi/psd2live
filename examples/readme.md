# 示例素材与输出

[项目首页](../README.md) · [操作速查](../docs/zh/guide/USER_GUIDE.md)（[English](../docs/en/guide/USER_GUIDE.md) · [한국어](../docs/ko/guide/USER_GUIDE.md) · [日本語](../docs/ja/guide/USER_GUIDE.md)） · [独立示例仓库](https://github.com/tsunehimatoi/psd2live-examples)

示例素材、工程、导出与预览由独立示例仓库维护，大文件通过版本化 Release 附件分发。主项目保留说明与固定素材清单。

| 示例 | 内容与来源 |
| --- | --- |
| tml | [案例说明](https://github.com/tsunehimatoi/psd2live-examples/blob/main/cases/tml/README.md) |
| ds | [案例说明](https://github.com/tsunehimatoi/psd2live-examples/blob/main/cases/ds/README.md) |
| star_lan | [案例说明](https://github.com/tsunehimatoi/psd2live-examples/blob/main/cases/star_lan/README.md) |

在主仓库根目录运行，需 Python 3.11 或更新版本：

```sh
python tools/examples/fetch.py --case tml
python tools/examples/fetch.py --all
```

下载脚本按 `tools/examples/fixtures.lock.json` 固定版本、下载地址、文件大小与 SHA-256，并复用 `build/example-assets/` 缓存。素材仍写入 `examples/<id>/`，现有开发工具与 CLI 路径保持可用。

可通过程序的“导入 PSD”打开输入，或从仓库根目录运行：

```powershell
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

现有输出是示例，不是当前版本的固定验收基准；算法、设置和版本变化会影响结果。生成新结果建议使用独立目录，便于对照。

运行时加载应从 `.model3.json` 开始，并保留其引用的 `.moc3`、纹理、物理与动作文件。`.cmo3` 用于编辑器检查；`.psd2live.json` 是诊断报告。

普通 CI 不下载这些素材。手动运行 `Example acceptance` 工作流可检查 `tml`、`ds` 的生成、导出、CMO3 导入与运行时预览包报告；报告不等同于视觉效果或与历史基准的比较。

现有三个示例按 CC0 1.0 Universal 发布。独立示例仓库的投稿内容未单独注明许可证时也默认 CC0 1.0；主项目的软件许可证由主项目维护。
