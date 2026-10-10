# Cubism CI 与发行工作流

[文档目录](../../README.md) · [English](../../en/guide/CUBISM_CI_RELEASE.md) · [Cubism Native 预览](CUBISM_SDK_SETUP.md)

本页说明仓库中两个 GitHub Actions 工作流：公开测试（不含 SDK）与可选的 Cubism 预览打包。不替代 Live2D 许可，也不授权再分发专有组件。

## 两个工作流

| 工作流 | 文件 | 作用 |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | `push` / `pull_request` 到 `master`（及 `main`）时，在 Ubuntu 与 Windows 的 x64 和 arm64 上跑 `./gradlew test`。不下载 Cubism SDK，不设置 `PSD2LIVE_INCLUDE_CUBISM`。 |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | 手动 `workflow_dispatch` 或推送 `v*` 标签时，私有拉取 SDK、编译原生桥、打 Windows/Linux 含 Cubism 的安装包（Linux 含 x64 与 arm64），另打不含 Cubism 的 Windows ARM64 包，并可创建 GitHub Release。 |

macOS 打包暂缓，本工作流不构建。

## 公开仓库与 SDK 泄漏（必读）

**在公开仓库上，GitHub Release 的附件默认对所有人可见。** 不要把 `CubismSdkForNative-*.zip` 挂在本公开应用仓库的 Release 上。

推荐做法：

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

前提：私有 SDK 仓库已上传 zip，本仓库已配置上表的变量、密钥与 `release-cubism` 环境。下文以 `<version>` 表示目标版本号，例如 `3.3.1`。

1. **先提升版本号**：工作流不会修改仓库内容。在 `build.gradle.kts` 中把 `version` 与 `packageVersion` 改为目标版本，同步界面中的版本字符串；在 `docs/zh/CHANGELOG.md` 添加本版条目，并把本版说明写入 `.github/release-notes.md`（中文在前，英文放在折叠的 `<details><summary>English</summary>` 中；不写下载区，发布时由 `.github/scripts/release_notes.py` 按实际附件生成分类的下载徽章）；提交并推送。
2. 打开 Actions → **Release Cubism** → **Run workflow**，`version` 填 `<version>`（不带 `v`）。
3. 按需勾选 `create_github_release`（默认开启；关闭时只生成构建产物）。
4. 通过环境保护审批，等待 Windows 与 Linux 构建完成。
5. 开启发布时，工作流会创建或更新标签 `v<version>` 的**正式**（非预发布）GitHub Release，附件为：
   - `PSD2Live-<version>-windows-x86_64-portable.zip`
   - `PSD2Live-<version>.exe`
   - 以上两项另各有内置 ffmpeg 的版本：`PSD2Live-<version>-windows-x86_64-portable-ffmpeg.zip`、`PSD2Live-<version>-ffmpeg.exe`
   - `PSD2Live-<version>-windows-arm64-portable.zip`、`PSD2Live-<version>-arm64.exe`：Windows ARM64，不含 Cubism（Live2D 未提供该平台的 Cubism Core），无 ffmpeg 版本
   - `PSD2Live-<version>-linux-amd64.deb`、`PSD2Live-<version>-linux-arm64.deb`（arm64 用 SDK 中实验性的 Cubism Core）

也可以直接推送标签 `v<version>` 触发同一工作流。两种入口选其一，避免重复运行完整的构建矩阵。

## 产物与平台限制

- Linux 预览需要 X11/GLX（含 XWayland / `xvfb-run`）。纯 Wayland、musl/Alpine 不支持。详见 [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md)。
- ARM64 包尚未实机测试：只要附件里有 arm64 包，`release_notes.py` 就在发布说明中注明未经实机测试及已知限制（Linux arm64 的 Cubism Core 为实验性版本、只支持 OpenGL 3.1 的 GPU 上画布可能无法绘制）。实机验证后删去这条说明。
- v1 不做脆弱的 GUI 冒烟；Linux 侧以 `.deb` 存在且非空为准。
- Windows 包打两遍：第二遍加 `-Ppsd2live.ffmpegDir=<目录>`，把固定版本的 Gyan.dev ffmpeg essentials 构建（GPLv3，下载后校验 SHA-256，并检查视频与动图导出用到的编码器）连同其 `LICENSE.txt`、`README.txt` 放进应用的 `resources/ffmpeg/`。升级 ffmpeg 时同时修改工作流中的 `FFMPEG_URL` 与 `FFMPEG_SHA256`。Linux 包不内置 ffmpeg。
- Windows 安装包由 `packageExe` 用 Inno Setup 6 从应用镜像构建，脚本为 `packaging/windows/psd2live.iss`；`ISCC.exe` 依次取 `-Ppsd2live.iscc`、环境变量 `ISCC`、Inno Setup 6 的默认安装目录，工作流用 Chocolatey 安装 Inno Setup。文件就地复制，不像 MSI 那样先把旧文件移到 `Config.Msi`。默认为所有用户安装（需管理员），也可在启动时的对话框或用 `/CURRENTUSER` 只为当前用户安装；升级时装回已安装的目录并沿用当时的安装方式，先删除上一版的 `app`、`runtime` 目录再复制，安装目录中用户自己的文件保留。带与不带 ffmpeg 的包共用同一 AppId，互相替换。安装前若有 PSD2Live 从安装目录运行，会提示关闭后重试（静默安装直接中止）。3.1.x 及更早版本是 jpackage 生成的 MSI：安装程序按其升级码找到它们，默认装到它们的目录，并在复制文件前先清掉它们记录的安装目录（3.0.0 及更早版本卸载时会清空该目录）并删掉它们安装的文件，再用 `msiexec /x` 静默卸载，这时 MSI 只做反注册，不必再把文件移进 `Config.Msi`；卸载日志写在 Setup 日志旁（`PSD2Live MSI removal.log`）；只为当前用户安装而旧版为所有用户安装时，会请求管理员权限。卸载时询问是否同时删除用户数据（默认否；运行应用的 `--clear-user-data`，以运行卸载程序的账户执行；命令行卸载可传 `/CLEARUSERDATA=1`）。每次安装与卸载都会在 `%TEMP%` 写日志（`Setup Log *.txt`）。
- 含 Cubism 的包仅供许可允许范围内的用途；**不要**把专有二进制再分发到公开渠道。

## 许可提醒

仓库桥接代码为 GPL。Live2D Cubism Core / Framework / 着色器为 Live2D 专有软件，须自行取得并遵守其许可。见 [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md)。

[本地 SDK 配置](CUBISM_SDK_SETUP.md) · [native 脚本](../../../native/README.md)
