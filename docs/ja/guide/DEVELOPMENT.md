# 開発とコマンドライン

[ドキュメント一覧](../../README.md) · [中文](../../zh/guide/DEVELOPMENT.md) · [English](../../en/guide/DEVELOPMENT.md)

ソースからの実行、コマンドラインの利用、開発への参加向けのページです。リリースパッケージはランタイムを同梱しているため、通常の利用では不要です。

## 必要環境

- JDK 21。Gradle は同梱の Wrapper で実行するため、別途インストールは不要です。
- Windows では `.\gradlew.bat`、Linux / macOS では `./gradlew` を使います。以下の例は `./gradlew` で表記します。
- 公式 Cubism SDK は必須ではありません。ソースビルドは内蔵レンダラーを使います。ネイティブブリッジは [Cubism ネイティブプレビュー](CUBISM_SDK_SETUP.md)を参照してください。

## 実行

```bash
./gradlew run                     # 引数なし：GUI を起動
./gradlew run --args="--help"     # CLI のヘルプ
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows ではリポジトリ直下の `run-gui.bat` でも GUI を起動できます。引数がなければ GUI、引数があれば CLI として動作し、`--input` が必須です（`--help` を除く）。CLI は PSD から書き出しファイルを直接生成し、編集用の `.psd2live` プロジェクトは作りません。

## CLI オプション

| オプション | 既定値 | 内容 |
| --- | --- | --- |
| `--input <path>` | 必須 | 入力するレイヤー付き PSD |
| `--output <path>` | PSD と同じ場所の `psd2live-output` | 出力先 |
| `--lang <zh\|en\|ja>` | システム言語 | ログの言語 |
| `--atlas <size>` | 4096 | テクスチャアトラスのサイズ |
| `--mesh-spacing <px>` | 64 | メッシュ間隔 |
| `--mesh-pixels` | オフ | メッシュの長さを、長辺 2048 px に縮めたドキュメントではなく元のピクセルで測る |
| `--head-strength <value>` | 1.0 | 頭の変形の強さ |
| `--body-strength <value>` | 1.0 | 体の変形の強さ |
| `--mesh-only` | オフ | メッシュのみ生成 |
| `--no-deformers` | オフ | デフォーマを生成しない |
| `--no-motions` | オフ | モーションを出力しない |
| `--no-physics` | オフ | 物理を生成しない |
| `--no-cmo3` | オフ | CMO3 を出力しない |
| `--no-moc3` | オフ | MOC3 を出力しない |
| `--no-json` | オフ | 診断 JSON を出力しない |
| `--upscale <1\|2\|4>` | 1 | テクスチャ高解像度化の倍率。1 で無効 |
| `--upscale-python <path>` | `python` | nunif の依存関係を入れた Python |
| `--nunif-dir <path>` | 空 | nunif のソースディレクトリ |
| `--upscale-model <path>` | 空 | 重みファイルのディレクトリ |
| `--upscale-tile <64..512>` | 256 | 推論のタイルサイズ |
| `--upscale-noise <-1..3>` | 1 | ノイズ除去レベル。-1 で無効 |
| `--no-upscale-neural-alpha` | オフ | アルファをバイリニアで拡大 |

補足：

- 既定値は [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt) に従います。GUI のメッシュ間隔の初期値は `PipelineConfig` の 40 で、CLI の 64 とは異なります。
- CMO3、MOC3、診断 JSON のうち少なくとも一つは出力してください。
- ニューラルアルファは既定で有効です。`--upscale-neural-alpha` は旧コマンドとの互換用です。
- 高解像度化の準備は[テクスチャ高解像度化](../../zh/guide/TEXTURE_UPSCALE.md)（中国語）を参照してください。

## 他の形式への書き出し

`export` コマンドは `.psd2live` プロジェクト（現在の履歴状態）または PSD（既定の設定で生成）を中立 IR 経由で任意のターゲットに書き出し、損失レポートを書き出します：

```bash
./gradlew run --args="targets"                                            # 書き出しターゲットの一覧
./gradlew run --args="export model.psd2live --target gif --set clip=Nod --set size=512"
./gradlew run --args="export model.psd2live --target psd-pose --set pose=ParamAngleX=20 --output out/pose"
```

- ファイルは `--output`（既定は入力の隣の `<名前>-<ターゲット>`）に書き出され、損失レポートは `<名前>.<ターゲット>.report.json` です。`--name` で基本名を指定します。
- `--set key=value` は繰り返し指定できます。各ターゲットの設定キーは[中立リグ IR と書き出しターゲット（中文）](../../zh/spec/EXPORT_TARGETS.md)を参照してください。
- 終了コード：0 は成功、1 は書き出しの失敗、2 は引数の誤り。

## テストとパッケージ作成

```bash
./gradlew test                                   # 全テスト（CI は Ubuntu と Windows で実行）
./gradlew test --tests "io.github.psd2live.core.SwingDeformerTest"   # 単一のテストクラス
./gradlew createDistributable                    # ランタイム付きのアプリディレクトリ
./gradlew packageDistributionForCurrentOS        # 現在のプラットフォーム向けインストーラー
```

- どちらもビルドしたプラットフォームのネイティブライブラリのみを含み、アプリのリソースに `LICENSE`、`THIRD_PARTY_NOTICES.md`、`licenses/` を同梱します。
- 公式 SDK のリソース（`src/main/resources/cubism/`）は、`-Ppsd2live.includeCubism=true` または `PSD2LIVE_INCLUDE_CUBISM=true` を指定したときだけ含まれます。SDK を含むパッケージは公開配布できません。[CI とリリース](../../en/guide/CUBISM_CI_RELEASE.md)（英語）を参照してください。
- Linux では `./native/package_linux.sh` で、システムの JDK 21 を使うローカル起動パッケージを作成できます（`dist/linux-<タイムスタンプ>/` に出力）。詳しくは [native/README.md](../../../native/README.md) を参照してください。
- 独立した lint タスクはありません。コードスタイルは `kotlin.code.style=official` です。
- Rust ランタイムは `runtime/` で `cargo test`、`cargo build --release` によりビルドします。[ランタイム（中国語）](../../zh/spec/RUNTIME.md) を参照してください。
- テストスイートには高速な単体テストと契約テストだけを残し、`./gradlew test` 全体は 1 分以内に終わるようにします。サンプル PSD（tml、ds）でパイプライン全体を実行するテスト、各ステップをコールドリプレイと比較するテスト、画面全体を描画するテストは追加せず、下記の開発ツールとして書いて必要なときに手動で実行します。

## 開発ツール

`src/test/kotlin/io/github/psd2live/tools/` には、結果を目視で確認したり測定したりする開発ツールがあります。パイプラインの内部 API を使えるようにテストとして書かれており、`PSD2LIVE_TOOLS=1` を設定したときだけ実行されます。通常の `./gradlew test` では skip されます。出力先は `build/tools/` です。

```bash
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

| ツール | 内容 | 出力 |
| --- | --- | --- |
| `MotionSheetTool.motions` | 各プリセットモーションを時間順に並べた一覧、待機ループ、呼吸の前後と差分画像、体 Z | `motion-sheet/<サンプル>-*.png` |
| `MotionSheetTool.body` | 体 X × 体 Y、脚と上半身の拡大、前後傾、頭身、脚のポーズ。スケルトンなしと自動スケルトンの両方 | `motion-sheet/<サンプル>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | ポインタが画面上をゆっくり 12 秒周回し、頭と体がプレビューと同じゲインと速さで追従 | `motion-frames/<サンプル>-track/` |
| `MotionSheetTool.idle` | 待機 12 秒のフレーム画像 | `motion-frames/<サンプル>-idle/` |
| `ModelProfileTool.cmo3` | `.cmo3` のパラメータ、デフォーマツリー（グリッド軸と範囲）、メッシュ、帯ごとの動きのプロファイル、体パラメータでの各メッシュの移動、体 X × 体 Y のシルエット、物理グループ | `model-profile/<名前>.txt`、`.png`、`-physics.txt` |
| `ModelProfileTool.sample` | 生成モデル（スケルトンなしと自動スケルトン）の帯ごとの動きのプロファイル、体レイヤーと自動ボーン | `model-profile/<サンプル>.txt` |
| `DragonBonesFidelityTool` | `tml` と `ds`（スケルトンなしと自動スケルトン）を DragonBones に書き出し、`tools/dragonbones-check`（公式 DragonBones 5.7 ランタイムのコア、node が必要）で再生する。パラメータアニメーションを各キーフレームでエディタの評価と比較し（書き出しが報告した許容差以内）、クリップの誤差を測る | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool` | Rust ランタイムの参照データ：ランダムなモデル（ワープ、回転、入れ子、疎なキーフォーム、ブレンドシェイプ、グルー、チャンネル、パーツ）、サンプル、ローカルのプロジェクトをランダムなポーズでエディタが評価した結果と、ランダムな振り子グループとサンプルの物理のフレームごとの軌跡。`runtime/` の `p2lrt-conformance` で比較する | `runtime-conformance/<ケース>/`、`runtime-physics/<ケース>/` |
| `WarpProbeTool` | エディタの評価器のブラックボックス探査：格子内外のワープ写像、ワープ下の回転フレーム、反転、ブレンドシェイプ、疎なキーフォーム。ランタイムの独立実装の照合用 | `warp-probe/*.tsv` |
| `SwingCostTool` | tml での 2 つのスイングの生成時間、その入力の単純なハッシュ時間、編集全体の再生時間。生成器に生成キャッシュを付ける価値があるかの判断に使う | `swing-cost/report.txt` |
| `GeneratorCostTool` | 各生成器（スケルトンの有無による Rig 生成、スケルトンのベイクのキャッシュ前後、物理グループ一覧、生成モーションのキャッシュ前後、Rig IR のコンパイル、シミュレーションのベイクと書き戻し）の時間と、その入力のコンテンツハッシュ時間。`PSD2LIVE_SAMPLE` でサンプルを指定 | `generator-cost/report.txt` |
| `ExportGoldenTool` | `tml` と `ds` の、スケルトンなし・自動スケルトン・作成したモーションの 3 種での全書き出しファイルのダイジェスト（cmo3 は読み戻して moc3 に下げたもの）。リファクタリング前後の書き出しをバイト単位で比較する。`PSD2LIVE_GOLDEN_LABEL` で出力名を指定 | `export-golden/<名前>.txt` |
| `SafetyGoldenTool` | 自動スケルトン Rig 上の、シード付きランダムなジオメトリ編集 24 件の完全なジオメトリ安全性レポート（`coverage` を除く）。検査器の変更前後で分類をバイト単位で比較する。`PSD2LIVE_GOLDEN_LABEL` で出力名を指定 | `safety-golden/<名前>.txt` |
| `BundleProfileTool` | moc3 プレビューバンドルの段階別時間（IR コンパイル、IR からの復元、静止メッシュのキャンバス空間への変換、physics3/motion3、moc の変換と書き出し、cdi3）とジオメトリ安全性検査の時間。`PSD2LIVE_SAMPLER=1` でスタックサンプラーのホットスポットも出力 | 標準出力のみ |
| `TextureWorkspaceTool` | `tml` にいくつかの密度、ロック、固定を設定し、アトラスのページとテクスチャパネル（中国語と英語、単一・複数・未選択、ヒートマップのオン／オフ、密度スライダーのプレビュー）、および編集キャンバスのアトラスと元画像のピクセルの比較を描画 | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | `tml` のアトラスページとテクスチャパネルのフレームコストをヘッドレスで計測：`ImageComposeScene` に待機、ホバー、角（密度）のドラッグ、ホイールズーム、タイルのドラッグ、ワイヤーフレームなしのホバーのポインター入力を送り、各描画の時間（中央値、p90、最大）を記録。まずソフトウェア、OpenGL が使えれば次に GPU で計測し、モードごとに角のドラッグ中・静止したページ・拡大のフレームを保存 | `atlas-frame-perf/report.txt`、`software-*.png`、`gpu-*.png` |
| `AtlasWindowPerfTool` | 実際のエディタウィンドウをテクスチャワークスペースで開き（`PSD2LIVE_SAMPLE` に `.psd` か `.psd2live` を指定可。コピーを使うこと）、ポインター入力を AWT イベントとして送る（実カーソルは動かさない）：ビューモデル経由の移動と密度変更、タイルのドラッグ、角のドラッグ、互いを待たない 6 回の連続ドラッグ。フレーム間隔、UI 遅延、各ビジーフラグが下りた時刻、UI スレッドのホットスポット、ドラッグプレビューの描画回数、ビューごとの GPU フレーム数を記録。キャプチャは画面ではなくウィンドウ自身の Skia フレームから取る | `atlas-window-perf/report.txt`、`*.jfr`、`*-after.png` |
| `ExportDialogTool` | 「インポート」と「形式を指定して書き出し」サブメニューを開いた「ファイル」メニュー、各ターゲットの書き出しダイアログ、Live2D と PSD の書き出しダイアログ、「書き出し完了」ダイアログ（中国語と英語、ダークテーマ。完了ダイアログはライトテーマも）。メニューの分類、ラベルとレイアウトの確認用 | `export-dialog/<言語>-<ターゲットまたはメニュー>.png` |
| `ModalDialogTool` | 共通のモーダル枠に載せた設定、ヘルプ、テクスチャ高解像度化、描画順、メッシュ再構築ダイアログ（中国語、ダークテーマ）。タイトル行、本文、フッターがそろっているかの確認用 | `modal-dialog/<ダイアログ>.png` |
| `SimBakeBenchmark` | `tml` の後ろ髪をいくつかの設定で焼き込み、フィットに使わなかった動きでシミュレーションと書き出し結果を比較 | 標準出力 |
| `CommitPerfTool.profile` / `.desktop` | 1 回の編集コミットにかかる時間。`profile` はアプリケーション層のコマンド境界を通し、段階別（リビジョン、設定のデコード、再構築、ジオメトリ検査）に分けて計測。`desktop` はデスクトップのビューモデルとアダプタを通してメッシュ頂点編集とブラシのストロークを続けてコミットし、コミット時間と UI スレッドの最長停止を報告。`JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` と併用してサンプリング可能 | `commit-perf/report.txt`、`desktop.txt` |
| `CommitPerfTool.baseline` | コミット経路の段階別ベースライン。自動スケルトン、揺れ 2 つ、ベイク済みシミュレーション 1 つを持つプロジェクトで、完全再構築の各段階（解析、テクスチャアトラスのパッキングと PNG エンコード、基礎 Rig、スケルトンキャッシュのヒット/ミス、ジャーナル再生、揺れ/シミュレーションの書き戻し、上書き、IR、moc3 バンドル、`validateBundle`、リビジョンハッシュ、ランタイムファイルの書き出し）と、ジオメトリのコミット、小さなレイヤーへの描画、画像の差し替え（同形状/メッシュ再構築）、無関係なトポロジー編集後のスケルトンキャッシュ、ジャーナル 50/200 件追加時のコミット時間と増加を計測。各コミット後に履歴を保存する。ネイティブプレビューの再読み込み（GL コンテキストが必要）は計測しない | `commit-perf/baseline.json`、`baseline.md` |
| `OpenPerfTool.profile` | プロジェクトを開く時間。自動スケルトン、揺れ 2 つ、ベイク済みシミュレーション 1 つを持つプロジェクトをヘッドキャッシュあり/なしで保存し、空のスケルトンキャッシュでそれぞれ 3 回開いて、展開/シードとヘッドの再構築を計測し、両者の再構築モデルが同一であることを確認 | `open-perf/report.json`、`report.md` |
| `SavePerfTool.profile` | 現実的な規模の生成プロジェクト（`PSD2LIVE_SAVE_LAYERS`、`PSD2LIVE_SAVE_SIZE`、`PSD2LIVE_SAVE_REVISIONS`）の保存時間。同じキャプチャを 3 回、開き直した後に 2 回保存し、1 回開く | `save-perf/report.txt` |
| `Cmo3HiresTool` | キャンバスより高密度のレイヤーを `.cmo3` に書く方法の調査。tml の目のレイヤー 1 枚を 4 倍に拡大し（中央 3 分の 1 に 1 テクセルの市松模様）、ベースライン、レイヤーはキャンバス解像度でアトラスのみ高解像度、高解像度レイヤーにモデル画像のスケールアフィン（レイヤー矩形はラスターサイズまたはキャンバスサイズ）、レイヤー画像全体を 4 倍にしたものをそれぞれ書き出し、リーダーで配置を読み戻す。ファイルは Cubism Editor で手動確認する | `cmo3-hires/*.cmo3`、`report.txt`、`README.txt` |

| 環境変数 | 効果 |
| --- | --- |
| `PSD2LIVE_SAMPLE` | サンプル名（`tml`、`ds`）または PSD のパス。既定は `tml`。`CommitPerfTool.desktop` は `.psd2live` プロジェクトも受け付ける |
| `PSD2LIVE_CMO3` | `ModelProfileTool.cmo3` の入力。`.cmo3` ファイルまたはそのディレクトリ |
| `PSD2LIVE_HIRES_TILE` | `Cmo3HiresTool` が拡大するレイヤー名。既定は最小の目のレイヤー |
| `PSD2LIVE_PROBES` | プロファイルで調べるパラメータ。`id=値,...`。既定は体 X・Y・Z の両端 |
| `PSD2LIVE_SHEET_PARAM` | シルエットを体 X × 体 Y ではなくこのパラメータに沿って並べる |
| `PSD2LIVE_BONES` | `MotionSheetTool.body` で自動スケルトンのボーン位置を補正。`id=頭x,頭y,尾x,尾y;...`（キャンバスピクセル） |
| `PSD2LIVE_BIND_LEGS` | `1` で脚と靴のメッシュを最初の太ももボーンにバインド |
| `PSD2LIVE_ZOOM` | 脚の拡大範囲。`左,上,右,下` をキャンバスに対する比率で指定 |
| `PSD2LIVE_VERBOSE` | `1` で `motions` が各カーブも出力 |
| `PSD2LIVE_BAKE_CONFIGS` | `SimBakeBenchmark` の設定。`モード数:キー数,...`。既定は `2:5,2:7,1:5` |

## コード構成

ソースは `src/main/kotlin/` 以下の二つのトップレベルパッケージに分かれています。

| パッケージ | 役割 |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`、キーフォーム補間と評価 |
| `org.umamo.format` | PSD、CMO3、MOC3、画像形式の読み書き |
| `org.umamo.interop` | `PuppetModel` と CMO3 / MOC3 の相互変換 |
| `org.umamo.render` | LWJGL / OpenGL プレビュー |
| `org.umamo.edit` | モデルに対する不変の編集プリミティブ |
| `io.github.psd2live.core` | 生成パイプライン（`PSD2LivePipeline`、`LayerClassifier`、`AdaptiveMeshGenerator`、`RigBuilder`、`MotionGenerator`、`PhysicsGenerator`）と再生可能な編集 |
| `io.github.psd2live.project` | `.psd2live` アーカイブ、セッション、ワークスペース状態のシリアライズ |
| `io.github.psd2live.history` | 分岐する元に戻す / やり直し |
| `io.github.psd2live.agent` | ローカル MCP サーバーと公開ツールの定義 |
| `io.github.psd2live.ui` | Compose UI：`state`（ViewModel、ショートカット登録）、`views`（ワークスペースとパネル）、`components`（ダイアログと部品）、`tutorial`。キャンバス編集と描画は `ui` 直下 |
| `io.github.psd2live.i18n` | UI 文言。リソースは `src/main/resources/i18n/` |

生成と書き出しのロジックは `core` / `project` に置き、Compose のコードには書かないでください。

## 基本ルール：再構築と再生

`PuppetModel` は永続化されません。プロジェクトが保存するのは、元画像、レイヤー分類、設定と、シリアライズ可能な編集記録（`RigEditOverlay`）です。開くたび、また変更のたびに次の処理が行われます。

1. `RigBuilder` が元画像から基本のリグを再生成する
2. `RigEditOverlay.applyTo` が編集を決まった順序で再生する：パラメータの削除 / 作成 → Warp と構造 → キーフォーム → 記録順の編集ジャーナル → 最後に揺れを生成

そのため新しい編集機能では次の点を守ってください。

- 変更は `RigEditOverlay` のシリアライズ可能なフィールドに記録し（できれば編集ジャーナルの JSON コマンドとして）、ワークスペース状態のコーデックで保存・復元します。`PuppetModel` だけを変更すると再構築で失われます。
- UI と MCP は同じ編集コマンドを使います。`org.umamo.edit` の低レベルメソッドは、そのまま公開インターフェースになるわけではありません。
- 受け入れ確認の流れ：ドメインデータ → 履歴の再生 → 保存と再読み込み → 対象 Cubism バージョンの処理 → 書き出しと読み戻し → 目視確認。詳しくは[ランタイムと書き出しの境界](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)（中国語）を参照してください。

UI 文言を追加するときは、`Messages.properties`、`Messages_zh_CN.properties`、`Messages_ja.properties` の三つに同時に追加し、キー数をそろえてください。

## 書き出し結果の確認

- `.model3.json` が参照するすべてのファイルを一緒に納品します。
- ログパネルと診断 JSON の警告を確認します。
- 編集を続けるには `.psd2live` プロジェクトを保存します。`.psd2live.json` はレポートにすぎません。

関連：[Cubism ネイティブプレビュー](CUBISM_SDK_SETUP.md) · [`build.gradle.kts`](../../../build.gradle.kts)
