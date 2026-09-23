# 任意の Cubism Native プレビュー

[Docs](../../README.md) · [User guide](USER_GUIDE.md)

内蔵レンダラーと基本的な出力に公式 SDK は不要です。この手順はブリッジを構築し、公式ランタイムの描画・物理を確認するためのものです。エディタの全機能や全画素の一致を保証しません。

## 必要なもの

### Windows x64
- CMake 3.16+
- Visual Studio 2022 C++ / MSVC 143
- ローカルの Cubism 5 SDK for Native (5-r.5 の構成)
- 必要：`Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`

### Linux x86_64
- CMake 3.16+
- GCC または Clang（C++14 サポート）
- OpenGL / GLX 開発パッケージ（例: Debian/Ubuntu の `libgl1-mesa-dev` と `libglx-dev` または `libglx-mesa-dev`、Fedora の `mesa-libGL-devel`）
- X11 開発ライブラリ（`libx11-dev` / `libX11-devel`）
- 実行時に有効な X11 `DISPLAY`（デスクトップ、またはヘッドレスでは `xvfb-run`）
- ローカルの Cubism 5 SDK for Native (5-r.5 の構成)
- 必要：`Core/lib/linux/x86_64/libLive2DCubismCore.a`

## ビルドと配置

### Windows

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

リポジトリのルートで実行します。`-Deploy` は DLL とシェーダーを下記の Git 対象外ディレクトリへコピーします。`-Clean` は古いビルドキャッシュを除いて再構築します。

```text
src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/
```

### Linux

```bash
export CUBISM_SDK_ROOT=/path/to/CubismSdkForNative-5-r.5
./native/build_live2d_renderer.sh --clean --deploy
```

リポジトリのルートで実行します。`--deploy` は共有ライブラリとシェーダーを下記の Git 対象外ディレクトリへコピーします。`--clean` は古いビルドキャッシュを除いて再構築します。

```text
src/main/resources/cubism/linux-x86_64/
├── liblive2d_renderer.so
└── FrameworkShaders/
```

## 確認

モデルを開き、レンダラー表示とログを確認してください。読み込めない場合は内蔵ソフトウェア描画が使われます。モデルが表示されたことだけでは SDK の有効化を確認できません。

## 代替パスと問題解決

リポジトリの `cubism/windows-x86_64/`（または `cubism/linux-x86_64/`）、環境変数 `CUBISM_SDK_PATH` / `LIVE2D_SDK_PATH`、JVM 属性 `psd2live.cubism.path` も利用できます。これらは配置済み資源へのパスで、ビルド用 SDK ルート `CUBISM_SDK_ROOT` とは異なります。

ネイティブライブラリと FrameworkShaders を揃えてください。

### Windows
VCRUNTIME / MSVCP エラーは /MD の古いビルドが原因の場合があります。現行スクリプトは /MT と静的 Core を使います。`dumpbin /DEPENDENTS` で確認できます。

### Linux
`ldd liblive2d_renderer.so` で依存を確認します。期待されるシステムライブラリ：`libGL.so`、`libGLX.so`（または Mesa GLX）、`libX11.so`、`libpthread.so`、`libdl.so`。

オフスクリーンプレビューは GLX コンテキストを開くため、有効な X11 `DISPLAY` が必要です。ヘッドレス環境では Xvfb を入れ、例として `xvfb-run -a ./gradlew run`（または `xvfb-run -a java -jar …`）で起動してください。`DISPLAY` が無いとネイティブ初期化に失敗し、内蔵ソフトウェア描画にフォールバックします。

環境変数変更後は起動元プロセスを再起動してください。

配布するのはオープンソースのブリッジだけです。公式 Core・Framework・シェーダーは別途取得し、そのライセンスに従ってください。この文書は利用許諾ではありません。

[Native build](../../../native/live2d_renderer/README.md) · [ビルドスクリプト](../../../native/) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)
