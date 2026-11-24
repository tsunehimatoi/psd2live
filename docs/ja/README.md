<!-- lang: zh en ko ja -->
> [中文](../README.md) · [English](../en/README.md) · [한국어](../ko/README.md) · **日本語**

# ドキュメント目次

[プロジェクトホーム](../../README.ja.md) · [更新履歴](CHANGELOG.md) · [ロードマップ](ROADMAP.md) · [能力の実測](STATUS.md)

操作を覚えるときは、まずプログラム内ヘルプの **チュートリアル**（`F1`）を使ってください。入門コースは 19 課、Cubism 経験者コースは 14 課です。次のドキュメントは、操作を見直すときと、中核の形式やインターフェースを調べるときに使います。過去の設計稿と過程の記録はアーカイブ済みです（リポジトリのコミット履歴、またはローカルの `local/archive/` を参照）。

## ディレクトリ構成

`en/`、`ja/`、`ko/` と `zh/` のファイルは一対一で対応します。以下では中国語だけを展開します。残りの 3 言語は、同じパス構成です。

```text
docs/
├─ README.md            # 中文文档总索引（本文件）
├─ branding/            # 应用图标资源
├─ imgs/                # README 与图文教程截图（WebP）
├─ zh/                  # 中文（源文）
│  ├─ CHANGELOG.md      # 版本更新日志
│  ├─ ROADMAP.md        # 后续规划
│  ├─ STATUS.md         # 真实任务能力实测记录
│  ├─ guide/            # 使用指南、开发与构建文档
│  ├─ spec/             # 格式规范与核心架构契约
│  └─ agent/            # MCP 接口与 Agent 验收说明
├─ en/                  # English
├─ ja/                  # 日本語
└─ ko/                  # 한국어
```

## 利用ガイド

| ドキュメント | 内容 |
| --- | --- |
| [更新履歴](CHANGELOG.md) | 各バージョンで、使う人が気づく変化 |
| [操作早見表](guide/USER_GUIDE.md) | プログラム内チュートリアルに対応する文章版、よく使うショートカット、ワークスペースとテクスチャワークスペース |
| [キャンバス編集](guide/CANVAS_EDITOR.md) | モードメニュー、変形、メッシュトポロジ、Glue、ウェイト、描画セッション |
| [素材と Layout](guide/LAYOUT.md) | 素材ライブラリ、先に配置してから生成、再生成、意味タグ |
| [前後分割の図解チュートリアル](guide/DEPTH_SPLIT.md) | 衿と首、髪と肩の装飾の前後の重なり。複製、接着、消しゴムによる縁の修正 |
| [ボーンとポーズ](guide/SKELETON.md) | スケルトンの推定、リグ、IK、プリセットモーション、ベイクされる構造 |
| [スイング生成](guide/SWING.md) | 左右 / 上下のスイング、キャンバスのハンドル、振り子とベイク |
| [物理演算](guide/PHYSICS.md) | 物理グループの出所、振り子キャンバス、入出力、プリセットと書き出し |
| [シミュレーションとベイク](guide/SIMULATION.md) | 布 / 髪の 2D シミュレーション、頂点ウェイトグループ、グルーの役割。パラメータ、キーフォーム、振り子へベイク |
| [シミュレーション図解チュートリアル](guide/SIMULATION_TUTORIAL.md) | 髪と衣装のプリセットから始め、固定点ウェイトを調整し、ベイクし直して書き出し結果を確認する |
| [プリセットとモディファイア](guide/MODIFIERS.md) | プリセットとモディファイアの使い方 |
| [変形パス（実験的）](guide/DEFORM_PATHS.md) | パスの作成、バインド、書き出し方 |
| [テクスチャ高解像度化](guide/TEXTURE_UPSCALE.md) | ローカルの nunif の設定、パラメータ、効果の確認 |
| [アルゴリズムプラグイン](guide/ALGORITHM_PLUGINS.md) | アルゴリズムプラグインの開発と組み込み |

## ビルドとリリース

| ドキュメント | 内容 |
| --- | --- |
| [開発とコマンドライン](guide/DEVELOPMENT.md) | ソースからの実行、CLI 引数、テストとパッケージング、コード構成 |
| [Cubism Native プレビュー](guide/CUBISM_SDK_SETUP.md) | 任意の公式 SDK プレビューブリッジのビルドと配置 |
| [CI とリリース](guide/CUBISM_CI_RELEASE.md) | GitHub Actions のテストと、SDK を含むリリース手順 |

## 形式と中核の契約

| ドキュメント | 内容 |
| --- | --- |
| [PSD 素材と命名](spec/PSD_LAYER_SPEC.md) | レイヤー分けの要件、レイヤー名の表、左右と差分 |
| [デフォーマとパラメータ](spec/DEFORMER_AND_PARAMETER_SPEC.md) | 自動生成される構造、座標の規約、既定パラメータ |
| [プロジェクト形式 v2](spec/PROJECT_FORMAT.md) | `.psd2live` アーカイブのレイアウト、v1 からの移行、保存と検証 |
| [Layout マニフェスト](spec/LAYOUT_MANIFEST.md) | レイヤー素材の JSON / zip 交換形式 |
| [書き出し先](spec/EXPORT_TARGETS.md) | 中立リグ IR、各書き出し形式と損失レポート、モジュールのライセンス |
| [ランタイム](spec/RUNTIME.md) | Rust ランタイム、`.p2lrt` 形式、ウェブプレーヤーと Godot ノード |
| [`.p2lrt` 2.0 形式](spec/P2LRT_V2.md) | チャンクコンテナ、コア層と拡張層、進化の規則 |
| [ドキュメント層](spec/DOCUMENT_LAYER.md) | ジェネレーターの依存グラフ、生成結果の上書き、キャッシュ |
| [リグプリセットのアーキテクチャ](spec/RIG_PRESETS.md) | 部位、基礎アルゴリズム、モジュールとレシピ |
| [極簡ワークスペースとモバイル（設計中）](spec/LITE_WORKSPACE.md) | 前提知識のない人向けの簡素な画面、前後分割のタッチガイド、MCP の接続、モバイルの段階的な計画 |

## Agent（MCP）

| ドキュメント | 内容 |
| --- | --- |
| [MCP の使い方とインターフェース](agent/MCP_AUTHORING.md) | 接続方法、公開操作、原子的な一括適用と試行実行、呼び出し例 |
| [Agent の設計と受け入れ](agent/AGENT_DESIGN.md) | 役割の境界、ツール設計の原則、タスクの受け入れ手順 |

## その他

- [サンプル素材と出力](../../examples/readme.md)
- [ネイティブブリッジのビルドスクリプト](../../native/README.md)
- [サードパーティコンポーネントの表記](../../THIRD_PARTY_NOTICES.md)

## メンテナンスの取り決め

1. README には、位置づけ、導入、能力の境界、入口だけを書きます。手順の操作は、プログラム内の対話型チュートリアルを正とします。
2. 中国語が原文です。英語、日本語、韓国語は、同期した訳文です。ドキュメントは `docs/en`、`docs/ja`、`docs/ko` にあり、プロジェクトのホームはリポジトリ直下の `README.en.md`、`README.ja.md`、`README.ko.md` です。中国語を改めたときは、この 3 つも合わせて改めます。各ページ先頭の言語切り替えは、同一パスの 4 言語を指します。
3. 断片的な過程の設計稿やレビュー稿を、`docs/` に新たに追加しません（過程のメモは `local/` に置きます）。
4. 記述はソースと一致させます。チュートリアルの順序は `InteractiveTutorial.kt`、ショートカットは `ShortcutRegistry.kt`、CLI は `Main.kt`、MCP ツールはアプリケーション層の操作定義と `AgentOperationTools.kt` に合わせます。
