# PSD2Live

[中文](README.md) · [English](README_en.md) · [リリースをダウンロード](https://github.com/tsunehimatoi/psd2live/releases/latest) · [ドキュメント](docs/README.md)

**レイヤー付き PSD から Live2D モデルを生成し、同じ画面で編集・プレビュー・書き出し。**

レイヤー名からパーツを判定し、メッシュ、デフォーマ、顔のパラメータ、基本モーションと物理設定を生成します。キャンバスで形を調整し、テクスチャを描き足し、差分や素材を追加できます。MCP 対応 Agent との連携も可能です。

![階層ツリー、メッシュ編集、モデル設定を備えた編集画面](docs/imgs/view.png)

## はじめに

Windows 10/11 x64 向けの ZIP / EXE / MSI には Java ランタイムが含まれます。ZIP は展開して起動できます。

同じリリースページから Linux amd64 向け Deb も入手できます。Cubism Native プレビューを含み、X11/GLX が必要です。ネイティブプレビューは XWayland に対応しますが、XWayland のない Wayland、aarch64、musl / Alpine には対応していません。詳しくは [SDK の設定](docs/ja/guide/CUBISM_SDK_SETUP.md) を参照してください。Linux でソースから実行する場合は JDK 21 が必要です。

1. **ファイル → PSD をインポート**（初期設定 `Ctrl+Shift+O`）で素材を読み込みます。
2. プレビューとレイヤーパネルでパーツ分類・左右を確認します。
3. 必要な編集を行い、`Ctrl+S` で `.psd2live` 工程を保存します。
4. `Ctrl+G` で書き出し設定を開き、`.cmo3` または `.moc3` ファイル一式を出力します。

**初めて使う場合は「ヘルプ → チュートリアル」へ。** 実際の操作箇所を強調する対話形式の全 14 講座があります。[文字版ガイド](docs/ja/guide/USER_GUIDE.md)は同じ順番の簡潔な操作メモです。

## 主な機能

| 分野 | 内容 |
| --- | --- |
| 自動生成 | 多言語のパーツ判定、左右分離、適応メッシュ、頭・体・目・口・視線の設定 |
| キャンバス | 選択 / 変形 / 編集 / 描画、変形ブラシ、切断・細分化、Warp / Rotation、Glue、実験的な変形パス |
| 素材 | 透過画像の配置、表示切替・択一差分、テクスチャ描画、任意の 2× / 4× 高解像度化 |
| プレビュー | パラメータと XY 操作、待機・まばたき・うなずき・首振り、視線追従と物理 |
| 工程 | 単一ファイル保存、分岐履歴、元に戻す / やり直し、タブ、パネル配置、テーマとキー設定 |
| Agent | 認証付きローカル MCP による観察、素材・形状・パス・パラメータ・物理・履歴の操作 |

![レイヤー、ツール、インスペクター、パラメータ、アニメーション、物理パネル](docs/imgs/tools.png)

自動生成の品質は原画とレイヤー構成に依存します。白目・瞳・上まつ毛、前髪・後ろ髪を分け、口は開いた素材を用意してください。[PSD の準備](docs/ja/spec/PSD_LAYER_SPEC.md)を参照してください。

## 保存と書き出し

- `.psd2live`：元素材、設定、編集と履歴を保存する本アプリの工程。
- `.cmo3`：Cubism で確認・調整する編集用工程。
- `.moc3`・`.model3.json`・テクスチャ等：まとめて配布する実行用モデル。
- `.psd2live.json`：診断レポート。工程の代わりにはなりません。

内蔵レンダラーは公式 SDK なしで動作します。[Native SDK プレビュー](docs/ja/guide/CUBISM_SDK_SETUP.md)は任意です。すべての Cubism 機能や操作結果の一致を保証するものではありません。[実装範囲](docs/zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)（中国語）を参照してください。

## Agent 接続

**ツール → MCP → 接続・インストール**からホスト用設定をコピーし、アプリを起動したまま接続します。Streamable HTTP を優先し、Stdio のみの場合は `mcp_proxy.py` を使用します。

公開ツールは 11 個です。[MCP 仕様](docs/zh/agent/MCP_AUTHORING.md)、[実測結果](STATUS.md)、[ロードマップ](ROADMAP.md)は中国語で管理しています。新規画像を生成する作業にはホスト側の画像生成機能が必要です。

## 開発と参加

ソースからの実行には JDK 21 が必要です。

```powershell
.\gradlew.bat run
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
.\gradlew.bat test
```

Linux / macOS では `./gradlew` と内蔵レンダラーを使用します。[開発・CLI](docs/ja/guide/DEVELOPMENT.md)、[文書一覧](docs/README.md)、[サンプル](examples/readme.md)も参照してください。

不具合報告にはバージョン、OS、再現手順とログを添えてください。Agent の評価にはホスト、モデル、修正回数と消費量も記録してください。

## ライセンス

コードは [GPL-3.0](LICENSE) です。[第三者表記](THIRD_PARTY_NOTICES.md)と各素材の利用条件を確認してください。Live2D Inc. とは独立したプロジェクトで、公式の専有 SDK は配布しません。納品前に対象エディタと実行環境で結果を確認してください。
