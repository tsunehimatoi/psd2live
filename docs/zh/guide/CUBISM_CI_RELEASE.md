# Cubism CI 与发行工作流

[Docs](../../README.md) · [Cubism SDK 本地配置](CUBISM_SDK_SETUP.md)

本页说明仓库中两个 GitHub Actions 工作流：公开测试（不含 SDK）与可选的 Cubism 预览打包。不替代 Live2D 许可，也不授权再分发专有组件。

## 两个工作流

| 工作流 | 文件 | 作用 |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | `push` / `pull_request` 到 `master`（及 `main`）时，在 Ubuntu 与 Windows 上跑 `./gradlew test`。不下载 Cubism SDK，不设置 `PSD2LIVE_INCLUDE_CUBISM`。 |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | 手动 `workflow_dispatch` 或推送 `v*` 标签时，私有拉取 SDK、编译原生桥、打 Windows/Linux 含 Cubism 的安装包，并可创建 GitHub Release。 |

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

## 如何手动发 1.3.0（示例）

前提：私有 SDK 仓库已上传 zip；本仓库已配置上表变量/密钥与 `release-cubism` 环境。

1. Actions → **Release Cubism** → **Run workflow**。
2. `version` 填 `1.3.0`（不要带 `v`）。
3. `create_github_release` 按需勾选（默认 true）。
4. 通过环境保护审批后等待 Windows / Linux 构建完成。
5. 若勾选发布，会创建或更新标签 `v1.3.0` 的 **正式**（非 prerelease）GitHub Release，附件包括：
   - `PSD2Live-1.3.0-windows-x86_64-portable.zip`
   - `PSD2Live-1.3.0.exe`
   - `PSD2Live-1.3.0.msi`
   - `PSD2Live-1.3.0-linux-amd64.deb`

也可只推送标签 `v1.3.0` 触发同一工作流。请选一种入口，避免重复跑完整矩阵。

工作流**不会**修改 `build.gradle.kts` 里的 `packageVersion`；版本号仅用于产物命名与 Release 标题。发布前请先在仓库中把 `version` / `packageVersion` 与产品字符串 bump 到目标版本。

## 产物与平台限制

- Linux 预览需要 X11/GLX（含 XWayland / `xvfb-run`）。纯 Wayland、aarch64、musl/Alpine 不支持。详见 [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md)。
- v1 不做脆弱的 GUI 冒烟；Linux 侧以 `.deb` 存在且非空为准。
- 含 Cubism 的包仅供许可允许范围内的用途；**不要**把专有二进制再分发到公开渠道。

## 许可提醒

仓库桥接代码为 GPL。Live2D Cubism Core / Framework / 着色器为 Live2D 专有软件，须自行取得并遵守其许可。见 [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md)。

[本地 SDK 配置](CUBISM_SDK_SETUP.md) · [native 脚本](../../../native/README.md)
