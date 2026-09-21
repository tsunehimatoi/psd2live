# 可选 Cubism Native 预览

[Docs](../../README.md) · [User guide](USER_GUIDE.md)

内置渲染和基础导出无需官方 SDK。本页只配置本仓库的 Windows x64 原生预览桥接，用于检查官方运行时的渲染与物理；不承诺与编辑器所有功能或所有像素一致。

## 准备

Windows x64、CMake 3.16+、Visual Studio 2022 C++ / MSVC 143，以及本地 Cubism 5 SDK for Native。当前构建脚本按 5-r.5 目录布局查找 `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`。

## 构建并部署

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

在仓库根目录执行。`-Deploy` 复制 DLL 与着色器到下列 gitignored 目录，`-Clean` 清理旧构建缓存后重建。

```text
src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/
```

## 检查

启动应用并打开模型，检查预览的渲染器状态和日志。缺少或无法加载原生资源时使用内置软件渲染；查看具体错误，不能仅凭画面出现判断已启用 SDK。

## 其他路径与故障

也可放在仓库 `cubism/windows-x86_64/`，或用 `CUBISM_SDK_PATH`、`LIVE2D_SDK_PATH`、JVM 属性 `psd2live.cubism.path` 指向部署目录。它们是运行时路径，构建用的 `CUBISM_SDK_ROOT` 是 SDK 源码根目录。

DLL 与 FrameworkShaders 必须配套。若缺 VCRUNTIME / MSVCP，检查是否误用了 /MD 构建；当前脚本使用 /MT 与静态 Core。可用 dumpbin /DEPENDENTS 检查。修改环境变量后重启启动应用的进程。

仓库只提供开源桥接代码，不包含官方 Core、Framework 或着色器。请自行取得 SDK 并遵守其许可；本页不是许可授权。

[Native build](../../../native/live2d_renderer/README.md) · [Build script](../../../native/build_live2d_renderer.bat) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)
