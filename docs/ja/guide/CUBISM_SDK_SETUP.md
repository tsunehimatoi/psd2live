# 任意の Cubism Native プレビュー

[Docs](../../README.md) · [User guide](USER_GUIDE.md)

内蔵レンダラーと基本的な出力に公式 SDK は不要です。この手順は Windows x64 のブリッジを構築し、公式ランタイムの描画・物理を確認するためのものです。エディタの全機能や全画素の一致を保証しません。

## 必要なもの

Windows x64、CMake 3.16+、Visual Studio 2022 C++ / MSVC 143 とローカルの Cubism 5 SDK for Native。スクリプトは 5-r.5 の構成を使い、`Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib` を参照します。

## ビルドと配置

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

## 確認

モデルを開き、レンダラー表示とログを確認してください。読み込めない場合は内蔵ソフトウェア描画が使われます。モデルが表示されたことだけでは SDK の有効化を確認できません。

## 代替パスと問題解決

リポジトリの `cubism/windows-x86_64/`、環境変数 `CUBISM_SDK_PATH` / `LIVE2D_SDK_PATH`、JVM 属性 `psd2live.cubism.path` も利用できます。これらは配置済み資源へのパスで、ビルド用 SDK ルート `CUBISM_SDK_ROOT` とは異なります。

DLL と FrameworkShaders を揃えてください。VCRUNTIME / MSVCP エラーは /MD の古いビルドが原因の場合があります。現行スクリプトは /MT と静的 Core を使います。dumpbin /DEPENDENTS で確認でき、環境変数変更後は起動元プロセスを再起動してください。

配布するのはオープンソースのブリッジだけです。公式 Core・Framework・シェーダーは別途取得し、そのライセンスに従ってください。この文書は利用許諾ではありません。

[Native build](../../../native/live2d_renderer/README.md) · [Build script](../../../native/build_live2d_renderer.bat) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)
