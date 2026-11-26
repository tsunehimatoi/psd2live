<!-- lang: zh en ko ja -->
> **中文** · [English](../../en/guide/CUBISM_CI_RELEASE.md) · [한국어](../../ko/guide/CUBISM_CI_RELEASE.md) · [日本語](../../ja/guide/CUBISM_CI_RELEASE.md)

# Cubism CI 与发行工作流

[文档目录](../../README.md) · [Cubism Native 预览](CUBISM_SDK_SETUP.md)

本页说明仓库中两个 GitHub Actions 工作流：公开测试（不含 SDK）与可选的 Cubism 预览打包。不替代 Live2D 许可，也不授权再分发专有组件。

## 两个工作流

| 工作流 | 文件 | 作用 |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | `push` / `pull_request` 到 `master`（及 `main`）时，在 Ubuntu 与 Windows 的 x64 和 arm64 上跑 `./gradlew test`。不下载 Cubism SDK，不设置 `PSD2LIVE_INCLUDE_CUBISM`。 |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | 手动 `workflow_dispatch` 或推送 `v*` 标签时，打包 Windows、Linux 和 macOS 的 x64 与 ARM64 版本，并可创建 GitHub Release。公开仓库的产物使用内置预览。私有仓库的 Windows x64 和 Linux 构建可拉取 SDK 并编译原生桥。 |

## 公开仓库与 SDK 泄漏（必读）

**在公开仓库上，GitHub Release 的附件默认对所有人可见。** 不要把 `CubismSdkForNative-*.zip` 挂在本公开应用仓库的 Release 上。

公开仓库自动跳过 SDK 下载与原生桥打包。需要在许可允许范围内制作含 Cubism 的私有产物时，使用私有仓库运行工作流，并配置下列设置。

1. **私有兄弟仓库**存放 SDK zip，打 prerelease / 标签（例如 `internal-sdk/cubism-5-r.5`），附件名默认 `CubismSdkForNative-5-r.5.zip`。
2. 在本仓库设置变量 `CUBISM_SDK_SOURCE_REPO` 指向该私有仓库（`owner/name`）。
3. 设置密钥 `SDK_FETCH_TOKEN`：能读取该私有仓库 Release 的 PAT（或 fine-grained token）。未设置时回退为 `GITHUB_TOKEN`，通常**无法**读其它私有仓库。

备选：把 zip 放在其它私有存储，再另行改工作流拉取逻辑；当前实现以 `gh release download` 为准。

## 变量与密钥

| 名称 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `CUBISM_SDK_SOURCE_REPO` | Repository variable | 当前仓库 `github.repository` | SDK Release 所在仓库。**公开应用仓库必须改为私有仓库。** |
| `CUBISM_SDK_RELEASE_TAG` | Repository variable | `internal-sdk/cubism-5-r.5` | 私有 SDK Release / 标签名 |
| `CUBISM_SDK_ASSET_NAME` | Repository variable | `CubismSdkForNative-5-r.5.zip` | 附件文件名 |
| `SDK_FETCH_TOKEN` | Secret | （空则用 `GITHUB_TOKEN`） | 拉取私有 SDK 仓库时使用 |

## Environment 保护

作业 `build-windows`、`build-linux`、`release` 使用 GitHub Environment 名称 **`release-cubism`**。请在仓库 Settings → Environments 中创建该环境，并加上必需审阅者 / 部署分支限制，避免任意协作者直接打出含 SDK 的包。

## 发布一个版本

发布前配置 `release-cubism` 环境。私有 Cubism 构建还需要上表所列的 SDK 来源和访问密钥。下文以 `<version>` 表示目标版本号，例如 `3.8.5`。

1. **先提升版本号**。在 `build.gradle.kts` 中改 `version`，安装包与 `psd2live/version.properties` 会使用这个值。同步更新四份 `Messages*.properties` 中 `cli.usage` 的版本号和四份 `src/main/resources/whatsnew/<语言>.md` 的最新版本。`VersionConsistencyTest` 核对这些值。在 `docs/zh/CHANGELOG.md` 添加本版条目，并更新 `.github/release-notes.md`，中文在前，英文放在折叠的 `<details><summary>English</summary>` 内。发布时 `.github/scripts/release_notes.py` 会按附件生成下载区。提交并推送。
2. 打开 Actions → **Release Cubism** → **Run workflow**，`version` 填 `<version>`（不带 `v`）。
3. 按需勾选 `create_github_release`（默认开启；关闭时只生成构建产物）；预览版勾选 `prerelease`，发布为预发布版本，不标记为最新。
4. 若 `release-cubism` 环境配置了必需审阅者，在此通过审批。工作流等待所有平台构建完成。
5. 开启发布后，工作流会为标签 `v<version>` 创建或更新 GitHub Release。发布类型由 `prerelease` 控制，附件如下。
   - `PSD2Live-<version>-windows-x64-setup.exe`、`PSD2Live-<version>-windows-x64-portable.zip`
   - 以上两项另各有内置 ffmpeg 的版本：`PSD2Live-<version>-windows-x64-setup-ffmpeg.exe`、`PSD2Live-<version>-windows-x64-portable-ffmpeg.zip`
   - `PSD2Live-<version>-windows-arm64-setup.exe`、`PSD2Live-<version>-windows-arm64-portable.zip`：Windows ARM64，不含 Cubism（Live2D 未提供该平台的 Cubism Core），无 ffmpeg 版本
   - `PSD2Live-<version>-linux-x64.deb`、`PSD2Live-<version>-linux-arm64.deb`，以及同内容的 `PSD2Live-<version>-linux-x64.tar.gz`、`PSD2Live-<version>-linux-arm64.tar.gz`
   - `PSD2Live-<version>-macos-x86_64.dmg`、`PSD2Live-<version>-macos-arm64.dmg` 与 `SHA256SUMS.txt`

   3.4.0 及更早的版本沿用旧命名（如 `PSD2Live-<version>.exe`、`-windows-x86_64-portable.zip`、`-linux-amd64.deb`）。

也可以直接推送标签 `v<version>` 触发同一工作流。两种入口选其一，避免重复运行完整的构建矩阵。

## 产物与平台限制

- Linux 预览需要 X11/GLX（含 XWayland / `xvfb-run`）。纯 Wayland、musl/Alpine 不支持。详见 [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md)。
- ARM64 包尚未实机测试。附件有 ARM64 包时，`release_notes.py` 会在下载区列出这个限制。私有构建所用的 Linux ARM64 Cubism Core 属于 SDK 的实验版本。
- v1 不做脆弱的 GUI 冒烟；Linux 侧以 `.deb` 存在且非空为准。
- Windows 包打两遍：第二遍加 `-Ppsd2live.ffmpegDir=<目录>`，把固定版本的 Gyan.dev ffmpeg essentials 构建（GPLv3，下载后校验 SHA-256，并检查视频与动图导出用到的编码器）连同其 `LICENSE.txt`、`README.txt` 放进应用的 `resources/ffmpeg/`。升级 ffmpeg 时同时修改工作流中的 `FFMPEG_URL` 与 `FFMPEG_SHA256`。Linux 包不内置 ffmpeg。
- Windows 安装包由 `packageExe` 用 Inno Setup 6 从应用镜像构建，脚本为 `packaging/windows/psd2live.iss`；`ISCC.exe` 依次取 `-Ppsd2live.iscc`、环境变量 `ISCC`、Inno Setup 6 的默认安装目录，工作流用 Chocolatey 安装 Inno Setup。文件就地复制，不像 MSI 那样先把旧文件移到 `Config.Msi`。默认为所有用户安装（需管理员），也可在启动时的对话框或用 `/CURRENTUSER` 只为当前用户安装；升级时装回已安装的目录并沿用当时的安装方式，先删除上一版的 `app`、`runtime` 目录再复制，安装目录中用户自己的文件保留。带与不带 ffmpeg 的包共用同一 AppId，互相替换。安装前若有 PSD2Live 从安装目录运行，会提示关闭后重试（静默安装直接中止）。3.1.x 及更早版本是 jpackage 生成的 MSI：安装程序按其升级码找到它们，默认装到它们的目录，并在复制文件前先清掉它们记录的安装目录（3.0.0 及更早版本卸载时会清空该目录）并删掉它们安装的文件，再用 `msiexec /x` 静默卸载，这时 MSI 只做反注册，不必再把文件移进 `Config.Msi`；卸载日志写在 Setup 日志旁（`PSD2Live MSI removal.log`）；只为当前用户安装而旧版为所有用户安装时，会请求管理员权限。卸载时询问是否同时删除用户数据（默认否；运行应用的 `--clear-user-data`，以运行卸载程序的账户执行；命令行卸载可传 `/CLEARUSERDATA=1`）。每次安装与卸载都会在 `%TEMP%` 写日志（`Setup Log *.txt`）。
- 含 Cubism 的包仅供许可允许范围内的用途；**不要**把专有二进制再分发到公开渠道。

## 许可提醒

仓库桥接代码为 GPL。Live2D Cubism Core / Framework / 着色器为 Live2D 专有软件，须自行取得并遵守其许可。见 [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md)。

[本地 SDK 配置](CUBISM_SDK_SETUP.md) · [native 脚本](../../../native/README.md)
