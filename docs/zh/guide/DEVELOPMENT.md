# 开发与命令行

[文档目录](../../README.md) · [操作速查](USER_GUIDE.md)

源码运行需要 JDK 21；使用仓库自带 Gradle Wrapper，无需单独安装 Gradle。Windows 发布包包含运行时，和源码构建环境不同。

```powershell
.\gradlew.bat run
.\gradlew.bat run --args="--help"
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Linux / macOS: `./gradlew`.

不带参数启动 GUI；带参数进入 CLI，必须提供 `--input`（`--help` 除外）。CLI 从 PSD 生成输出，不会打开桌面工程继续编辑。

## UI 模块化

桌面端集中在 `src/main/kotlin/io/github/psd2live/ui/`：`state` 管 ViewModel 与界面状态，`views` 放工作区与各面板，`components` 放对话框与共用控件，`theme` / `tutorial` / `utils` 分别管主题、交互教程与桌面辅助；画布编辑与绘画留在 `ui` 根包。改界面优先落在对应子包，生成与导出仍走 `core` / `project`，不要把流水线写进 Compose。

## CLI

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `--input <path>` | 必填 | 输入分层 PSD |
| `--output <path>` | PSD 同目录的 psd2live-output | 导出目录 |
| `--lang <zh\|en\|ja>` | 系统语言 | 日志语言 |
| `--atlas <size>` | 4096 | 贴图尺寸 |
| `--mesh-spacing <px>` | 64 | CLI 网格间距 |
| `--head-strength <value>` | 1.0 | 头部幅度 |
| `--body-strength <value>` | 1.0 | 身体幅度 |
| `--mesh-only` | 关闭 | 仅网格模式 |
| `--no-deformers` | 关闭 | 禁用变形器生成 |
| `--no-motions` | 关闭 | 不输出动作 |
| `--no-physics` | 关闭 | 禁用物理生成 |
| `--no-cmo3` | 关闭 | 不输出 CMO3 |
| `--no-moc3` | 关闭 | 不输出 MOC3 |
| `--no-json` | 关闭 | 不输出诊断 JSON |
| `--upscale <1\|2\|4>` | 1 | 1 关闭高清化 |
| `--upscale-python <path>` | python | Python 可执行文件 |
| `--nunif-dir <path>` | 空 | nunif 源码目录 |
| `--upscale-model <path>` | 空 | 权重目录 |
| `--upscale-tile <64..512>` | 256 | 输入分块 |
| `--upscale-noise <-1..3>` | 1 | -1 无降噪 |
| `--no-upscale-neural-alpha` | 关闭 | 关闭神经 Alpha |

表中默认值来自 `Main.kt`。GUI 的初始网格间距来自 `PipelineConfig`，当前为 40，不能与 CLI 的 64 混用。至少保留 CMO3、MOC3 或诊断 JSON 中一种输出。`--upscale-neural-alpha` 是兼容开关；神经 Alpha 已默认开启，关闭请用 `--no-upscale-neural-alpha`。

## 构建与验证

```powershell
.\gradlew.bat test
.\gradlew.bat distZip
.\gradlew.bat createDistributable
.\gradlew.bat packageDistributionForCurrentOS
```

运行与修改范围相关的测试。`distZip` 只按 `distributions.main` 收集文档等已配置内容，不能当作完整便携应用。`createDistributable` 生成带运行时的应用目录；`packageDistributionForCurrentOS` 生成当前平台已配置的安装格式。原生桥接需另行构建，专有 SDK 资源不应提交或随项目发布。

## 输出检查

检查 model3.json 引用的所有文件均随模型交付，并查看诊断与导出警告。保存工程使用桌面端的 .psd2live；CLI 的 .psd2live.json 仅是报告。

[Main.kt](../../../src/main/kotlin/io/github/psd2live/Main.kt) · [PipelineConfig](../../../src/main/kotlin/io/github/psd2live/core/Model.kt) · [Gradle](../../../build.gradle.kts) · [SDK](CUBISM_SDK_SETUP.md) · [Texture upscale / 高清化](../../zh/guide/TEXTURE_UPSCALE.md)
