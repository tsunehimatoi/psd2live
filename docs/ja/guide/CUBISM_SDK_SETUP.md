<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/CUBISM_SDK_SETUP.md) · [English](../../en/guide/CUBISM_SDK_SETUP.md) · [한국어](../../ko/guide/CUBISM_SDK_SETUP.md) · **日本語**

# 任意の Cubism Native プレビュー

[ドキュメント目次](../README.md) · [CI とリリース](CUBISM_CI_RELEASE.md)

内蔵のレンダリングと基本的な書き出しに、公式 SDK は不要です。このページでは、このリポジトリのネイティブプレビューブリッジだけを設定し、公式ランタイムの描画と物理演算を確認します。エディターのすべての機能、またはすべてのピクセルと一致することは保証しません。

## 準備

### Windows x64
- CMake 3.16+
- Visual Studio 2022 C++ / MSVC 143
- ローカルの Cubism 5 SDK for Native（5-r.5 のディレクトリ構成）
- 必要：`Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`

### Linux x86_64 / arm64
- 対応プラットフォーム：X11/GLX のある Linux x86_64 と arm64。XWayland と `xvfb-run` を含みます。arm64 は、SDK 内の実験的な Cubism Core を使います
- 非対応：XWayland のない純粋な Wayland、または musl ベースの Alpine などのシステム
- CMake 3.16+
- GCC または Clang。C++14 に対応していること
- OpenGL / GLX の開発パッケージ。例：Debian/Ubuntu では `libgl1-mesa-dev` と `libglx-dev`（または `libglx-mesa-dev`）。Fedora では `mesa-libGL-devel`
- X11 の開発ライブラリ（`libx11-dev` / `libX11-devel`）
- 実行時には、使える X11 の `DISPLAY` が必要です（デスクトップセッション。グラフィックス環境がない場合は `xvfb-run`）
- ローカルの Cubism 5 SDK for Native（5-r.5 のディレクトリ構成）
- 必要：`Core/lib/linux/x86_64/libLive2DCubismCore.a`（arm64 は `Core/lib/experimental/linux/arm64/libLive2DCubismCore.a`）

## ビルドと配置

### Windows

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

リポジトリのルートで実行します。`-Deploy` は DLL とシェーダーを、次の gitignore されたディレクトリへコピーします。`-Clean` は、古いビルドキャッシュを消してから再ビルドします。

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

リポジトリのルートで実行します。`--deploy` は共有ライブラリとシェーダーを、次の gitignore されたディレクトリへコピーします。`--clean` は、古いビルドキャッシュを消してから再ビルドします。

```text
src/main/resources/cubism/linux-x86_64/   （arm64 为 linux-arm64/）
├── liblive2d_renderer.so
└── FrameworkShaders/
```

## 確認

アプリを起動してモデルを開き、プレビューのレンダラー状態とログを確認します。ネイティブのリソースがない、または読み込めないときは、プレビューは PSD2Live ランタイム（p2lrt）に切り替わり、ログに Cubism を使えない理由が残ります。ランタイムライブラリも使えないときだけ、編集キャンバスの描画へ戻ります。具体的なエラーを確認してください。画面が出たことだけでは、SDK が有効になったとは判断できません。

## その他のパスと障害

リポジトリの `cubism/windows-x86_64/`（または `cubism/linux-x86_64/`）に置くこともできます。`CUBISM_SDK_PATH`、`LIVE2D_SDK_PATH`、JVM プロパティ `psd2live.cubism.path` で、配置ディレクトリを指すこともできます。これらは実行時のパスです。ビルドに使う `CUBISM_SDK_ROOT` は、SDK ソースのルートディレクトリです。

ネイティブライブラリと FrameworkShaders は、セットである必要があります。

### Windows
VCRUNTIME / MSVCP がないときは、/MD ビルドを誤って使っていないか確認してください。現在のスクリプトは /MT と静的 Core を使います。`dumpbin /DEPENDENTS` で確認できます。

### Linux
`ldd liblive2d_renderer.so` で依存関係を確認します。想定するシステムライブラリは、`libGL.so`、`libGLX.so`（または Mesa GLX）、`libX11.so`、`libpthread.so`、`libdl.so` です。

オフスクリーンプレビューは GLX コンテキストを作るため、有効な X11 の `DISPLAY` が必要です。グラフィックス環境がないときは Xvfb をインストールし、たとえば `xvfb-run -a ./gradlew run`（または `xvfb-run -a java -jar …`）を使います。`DISPLAY` がないとネイティブの初期化が失敗し、プレビューは PSD2Live ランタイムに切り替わります（ランタイムライブラリも使えないときだけ、編集キャンバスの描画へ戻ります）。

環境変数を変更したあとは、アプリを起動しているプロセスを再起動してください。

このリポジトリが提供するのはオープンソースのブリッジコードだけで、公式の Core、Framework、シェーダーは含みません。SDK はご自身で入手し、そのライセンスに従ってください。このページは、ライセンスの許諾ではありません。

自動ビルドと、Cubism を含むリリースの手順は [CUBISM_CI_RELEASE](CUBISM_CI_RELEASE.md) を参照してください（公開リポジトリでは、SDK の zip を公開 Release に添付しないでください）。

[ネイティブビルド](../../../native/live2d_renderer/README.md) · [ビルドスクリプト](../../../native/) · [サードパーティ表記](../../../THIRD_PARTY_NOTICES.md)
