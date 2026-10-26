# 開発とコマンドライン

[ドキュメント一覧](../../README.md) · [中文](../../zh/guide/DEVELOPMENT.md) · [English](../../en/guide/DEVELOPMENT.md)

ソースからの実行、コマンドラインの利用、開発への参加向けのページです。リリースパッケージはランタイムを同梱しているため、通常の利用では不要です。

## 必要環境

- JDK 21。JetBrains Runtime（JBR）21 を推奨します。リリースパッケージは JBR を同梱し、その上ではメインウィンドウがシステムのウィンドウ枠を保ったまま（最大化・スナップ・端でのサイズ変更はシステムが処理）、タイトルバーをアプリが描画します。他の JDK でもビルド・テスト・実行はでき、その場合はメインウィンドウが枠なしになり、タイトルバー全体をアプリが描画します。パッケージ作成（`createDistributable`、`packageExe` など）には JBR 21 SDK（jpackage 付き）が必要で、他の JDK で作成するには `-Ppsd2live.allowNonJbr=true` を付けます。Gradle は同梱の Wrapper で実行するため、別途インストールは不要です。
- Windows では `.\gradlew.bat`、Linux / macOS では `./gradlew` を使います。以下の例は `./gradlew` で表記します。
- 公式 Cubism SDK は必須ではありません。ソースビルドは内蔵レンダラーを使います。ネイティブブリッジは [Cubism ネイティブプレビュー](CUBISM_SDK_SETUP.md)を参照してください。

## 実行

```bash
./gradlew run                     # 引数なし：GUI を起動
./gradlew run --args="--help"     # CLI のヘルプ
python tools/examples/fetch.py --case tml
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows ではリポジトリ直下の `run-gui.bat` でも GUI を起動できます。引数がなければ GUI、引数があれば CLI として動作し、`--input` が必須です（`--help` を除く）。CLI は PSD から書き出しファイルを直接生成し、編集用の `.psd2live` プロジェクトは作りません。

## CLI オプション

| オプション | 既定値 | 内容 |
| --- | --- | --- |
| `--input <path>` | 必須 | 入力するレイヤー付き PSD |
| `--output <path>` | PSD と同じ場所の `psd2live-output` | 出力先 |
| `--lang <zh\|en\|ja\|ko>` | システム言語 | ログの言語 |
| `--clear-user-data` | — | 単独で使用：現在のユーザーの設定、ワークスペースの保存領域、`~/.psd2live` 内のランタイムとキャッシュを削除して終了。エディター実行中は何も削除せず 1 を返します。Windows のアンインストーラーでデータ削除を選んだときに実行されます |
| `--atlas <size>` | 4096 | テクスチャアトラスのサイズ |
| `--mesh-spacing <px>` | 64 | メッシュ間隔 |
| `--mesh-pixels` | オフ | メッシュの長さを、長辺 2048 px に縮めたドキュメントではなく元のピクセルで測る |
| `--mesh-wrap <単位>` | 0 | 包み込みトポロジー：この幅（メッシュ単位）より狭い輪郭の隙間を閉じ、細い突起を一つの外形にまとめる |
| `--head-strength <value>` | 1.0 | 頭の変形の強さ |
| `--body-strength <value>` | 1.0 | 体の変形の強さ |
| `--mesh-only` | オフ | メッシュのみ生成 |
| `--no-deformers` | オフ | デフォーマを生成しない |
| `--no-see-through` | オフ | 眉も目も前髪から透かさない（既定ではどちらも透かすため、以前の版より出力に眉の複製、前髪の目の窓とその複製が増える） |
| `--no-see-through-eyes` | オフ | 目を前髪から透かさない（既定になった `--see-through-eyes` も引き続き受け付ける） |
| `--see-through-opacity <0..1>` | 0.5 | 眉と目が前髪から透ける度合い |
| `--no-motions` | オフ | モーションを出力しない |
| `--no-physics` | オフ | 物理を生成しない |
| `--no-cmo3` | オフ | CMO3 を出力しない |
| `--no-moc3` | オフ | MOC3 を出力しない |
| `--no-json` | オフ | 診断 JSON を出力しない |
| `--upscale <1\|2\|4>` | 1 | テクスチャ高解像度化の倍率。1 で無効 |
| `--upscale-python <path>` | Windows は `python`、他は `python3` | nunif の依存関係を入れた Python |
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

### ウィンドウなしの MCP サーバー

`./gradlew run --args="mcp [--port N] [--token T] [--store DIR] [--profile core|full]"` はウィンドウを開かずに MCP エンドポイントだけを起動し、エージェントが単独で作業できるようにします。ワークスペースはアプリケーション層のホストが直接持ち、ViewModel や Compose の状態は作りません（[ウィンドウなしホスト](../../zh/spec/HEADLESS_HOST.md)、中国語）。起動すると `endpoint`、`token`、`profile`、`store` と `capabilities`（`view_sample_motion=yes|no`、ビルドに Cubism Core が含まれるか）を表示し、プロセスが終わるまで動きます。スクリプトからは `python mcp_proxy.py call <操作> [JSON] --edit` か Python の `mcp_proxy.Client` で呼び出し、`PSD2LIVE_MCP_ENDPOINT` と `PSD2LIVE_MCP_TOKEN` をそこに向けます。ワークスペースの保存先は既定でユーザーディレクトリの `psd2live-mcp` で、エディターとは分かれています（履歴とインスタンスロックを共有しません）。`--token` は 32–256 文字の URL セーフ Base64 で、省略するとランダムに生成されます。

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
./gradlew test --tests "io.github.psd2live.scenario.CatalogContractsTest"   # 単一のテストクラス
./gradlew createDistributable                    # ランタイム付きのアプリディレクトリ
./gradlew packageDistributionForCurrentOS        # 現在のプラットフォーム向けインストーラー
```

- どちらもビルドしたプラットフォームのネイティブライブラリのみを含み、アプリのリソースに `LICENSE`、`THIRD_PARTY_NOTICES.md`、`licenses/` を同梱します。
- 公式 SDK のリソース（`src/main/resources/cubism/`）は、`-Ppsd2live.includeCubism=true` または `PSD2LIVE_INCLUDE_CUBISM=true` を指定したときだけ含まれます。SDK を含むパッケージは公開配布できません。[CI とリリース](../../en/guide/CUBISM_CI_RELEASE.md)（英語）を参照してください。
- Linux では `./native/package_linux.sh` で、システムの JDK 21 を使うローカル起動パッケージを作成できます（`dist/linux-<タイムスタンプ>/` に出力）。詳しくは [native/README.md](../../../native/README.md) を参照してください。
- 独立した lint タスクはありません。コードスタイルは `kotlin.code.style=official` です。
- Rust ランタイムは `runtime/` で `cargo test`、`cargo build --release` によりビルドします。[ランタイム（中国語）](../../zh/spec/RUNTIME.md) を参照してください。使える cargo がない場合、Linux の `./gradlew run` とパッケージ作成は、同じソースから CI（`runtime-prebuilt.yml`）がビルドしたライブラリをダウンロードします。`runtime/src`、`Cargo.toml`、`Cargo.lock` のハッシュ（`./gradlew printRuntimeHash`）で照合し、sha256 を検証します。見つからない、またはオフラインの場合は内蔵の評価器を使います。`-Ppsd2live.prebuiltRuntime=false` でダウンロードを無効にできます。`runtime/` を繰り返し変更して実行するときは `-Ppsd2live.fastRuntime=true` で LTO を無効にしてビルドを大幅に短縮できます（ライブラリは遅くなるため、パッケージ作成には使わないでください）。
- テストスイートには高速な単体テストと契約テストだけを残し、`./gradlew test` 全体は 1 分以内に終わるようにします。サンプル PSD（tml、ds）でパイプライン全体を実行するテスト、各ステップをコールドリプレイと比較するテスト、画面全体を描画するテストは追加せず、下記の開発ツールとして書いて必要なときに手動で実行します。

## 外部サンプルと受け入れ確認

素材、プロジェクト、表示用の書き出しは [psd2live-examples](https://github.com/tsunehimatoi/psd2live-examples) で管理します。個別の指定がなければ CC0 1.0 です。Python 3.11+ で `python tools/examples/fetch.py --case tml` を実行し、全サンプルは `--all` を使います。保存先は従来の `examples/<id>/`、キャッシュは `build/example-assets/` です。

`tools/examples/fixtures.lock.json` はバージョン、URL、サイズ、アーカイブと各ファイルの SHA-256 を固定します。素材の更新時にこのファイルを明示的に更新します。取得や整合性の検証に失敗するとコマンドは失敗します。

通常の CI は小さな合成入力と内部契約テストを使い、サンプルを取得しません。`Example acceptance` ワークフローを手動実行すると、固定した `tml` と `ds` を取得し、`ExportGoldenTool` で生成、書き出し、CMO3 の読み込みを確認し、4 種の結果の書き出しとプレビューレポートを要求します。リリース前に出力を確認してください。視覚的な検証や過去の基準との比較は別途必要です。内部 MCP 回帰テストは主リポジトリに残し、外部の使用事例と接続確認はサンプル側で管理します。

## 開発ツール

`src/test/kotlin/io/github/psd2live/tools/` には、結果を目視で確認したり測定したりする開発ツールがあります。パイプラインの内部 API を使えるようにテストとして書かれており、`PSD2LIVE_TOOLS=1` を設定したときだけ実行されます。通常の `./gradlew test` では skip されます。出力先は `build/tools/` です。

```bash
python tools/examples/fetch.py --case ds
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

| ツール | 内容 | 出力 |
| --- | --- | --- |
| `DeformerDeletionVisualTool` | 合成した非対称メッシュの親デフォーマ削除前後と MOC3 書き出し・再読込後のシルエット比較 | `deformer-deletion/comparison.png` |
| `MotionSheetTool.motions` | 各プリセットモーションを時間順に並べた一覧、待機ループ、呼吸の前後と差分画像、体 Z | `motion-sheet/<サンプル>-*.png` |
| `MotionSheetTool.body` | 体 X × 体 Y、脚と上半身の拡大、前後傾、頭身、脚のポーズ。スケルトンなしと自動スケルトンの両方 | `motion-sheet/<サンプル>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | ポインタが画面上をゆっくり 12 秒周回し、頭と体がプレビューと同じゲインと速さで追従 | `motion-frames/<サンプル>-track/` |
| `MotionSheetTool.idle` | 待機 12 秒のフレーム画像 | `motion-frames/<サンプル>-idle/` |
| `ModelProfileTool.cmo3` | `.cmo3` のパラメータ、デフォーマツリー（グリッド軸と範囲）、メッシュ、帯ごとの動きのプロファイル、体パラメータでの各メッシュの移動、体 X × 体 Y のシルエット、物理グループ | `model-profile/<名前>.txt`、`.png`、`-physics.txt` |
| `ModelProfileTool.sample` | 生成モデル（スケルトンなしと自動スケルトン）の帯ごとの動きのプロファイル、体レイヤーと自動ボーン | `model-profile/<サンプル>.txt` |
| `SkeletonAutoViewTool.view` | 自動スケルトンをサンプルに重ねて描き、全ボーンを出力して関節位置と分岐を確認する。`PSD2LIVE_SAMPLES` は複数のサンプルまたは PSD パスをカンマ区切りで、`PSD2LIVE_ZOOM=左,上,右,下`（キャンバスのピクセル）で切り出し | `skeleton-auto/<サンプル>.png` |
| `SimBakeSuite` | モデルプリセットと同じ手順で後ろ髪・前髪・衣服などの物理体を作り（上端 10% 固定の後ろ髪も）、従来の方法と入力ごとの方法で焼き込み、フィットに使わなかった動き（フェイストラッキング風の追従、首かしげ、組み込みモーション、入力ごとのステップ、振り向き、ドラッグ、揺さぶり）で参照シミュレーションと書き出し結果を見た目の指標で比較 | `build/tools/sim-bake/<サンプル>/`：`summary.md`、設定ごとの JSON、先端の軌跡図 |
| `SimRootDofExperiment` | 物体が根元の平行移動と回転だけに応答するかの検証：各入力が根元をどれだけ動かし、それ以外にどれだけ形を変えるか、実際の駆動と剛体駆動・振幅・同じ根元の動きをする別の入力の違い | `build/tools/sim-root-dof/<サンプル>.md` |
| `SimBakeProfileTool` | `tml` の後ろ髪を単一スレッドと全スレッドで焼き込み、進捗ごとの所要時間と全スレッドのサンプリング | 標準出力 |
| `PendulumKernelTool` | 振り子の配列カーネル（厳密・高速）と `PhysicsEngine` のフレームあたりの時間と差 | 標準出力 |
| `CanvasPerfTool` | Xvfb 上で実際のウィンドウを開き、編集キャンバスで待機、ホバー、ホイールズーム、中ボタンのパン、変形モードで顔の全点のドラッグを順に行う。GPU 描画とソフトウェア描画で各 1 回、フレーム間隔、UI スレッドの遅延とホットスポット、段階ごとの編集キャンバスの GPU フレーム数（GPU の回で 0 ならキャンバスが GPU で描画されていない）を報告。`PSD2LIVE_CANVAS_AA` でキャンバスのアンチエイリアス、`PSD2LIVE_OVERLAY=1` でパフォーマンスオーバーレイを指定。`Robot` で実際のポインターを動かすため、専用の X サーバー（`xvfb-run -a -s "-screen 0 1920x1080x24"`、または GPU のあるマシンの別の Xorg）で実行し、使用中のデスクトップでは実行しない | `canvas-perf/report.txt`、各段階のキャプチャと `.jfr` |
| `DragonBonesFidelityTool` | `tml` と `ds`（スケルトンなしと自動スケルトン）を DragonBones に書き出し、`tools/dragonbones-check`（公式 DragonBones 5.7 ランタイムのコア、node が必要）で再生する。パラメータアニメーションを各キーフレームでエディタの評価と比較し（書き出しが報告した許容差以内）、クリップの誤差を測る | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool.generate` / `.physics` | Rust ランタイムの参照データ。`generate` はランダムなモデル（ワープ、回転、入れ子、疎なキーフォーム、ブレンドシェイプ、グルー、チャンネル、パーツ）、サンプル、`PSD2LIVE_RUNTIME_PROJECT` で指定したプロジェクトをランダムなポーズでエディタが評価した結果（スケルトンのサンプルは最も細かくベイクした `rig.fine.p2lrt` も）。`physics` はランダムな振り子グループとサンプルの物理のフレームごとの軌跡。`runtime/` の `p2lrt-conformance` で比較する | `runtime-conformance/<ケース>/`、`runtime-physics/<ケース>/` |
| `RuntimeConformanceTool.simulation` / `.simulationRig` | シミュレーションの参照軌跡。`simulation` はランダムな布（上辺を固定、剛性はランダム）を、揺れ・回転・跳ねる目標と固定点で動かしてエディタの XPBD ソルバーで解いた各フレームの結果（半数は動くカプセル衝突体つき）。`simulationRig` は `tml` の後ろ髪（上 10 分の 1 を固定）をベイクしてライブシーンごと書き出したモデルと、未ベイクの Rig 上で頭と体の動きに従うエディタのシーン。それぞれ `p2lrt-conformance --sim`、`--sim-rig` が読む | `runtime-sim/<ケース>/trace.bin`、`runtime-sim-rig/back-hair/` |
| `RuntimeSamplesTool` | プレイヤーを目視確認するための合成モデル：グラデーション上の全カラーブレンドモード、乗算色とスクリーン色、マスクと反転マスク。ソフトウェアラスタライザーの描画を参照画像として書き出す | `runtime-samples/features.p2lrt`、`features.png`、`layout.txt` |
| `WarpProbeTool` | エディタの評価器のブラックボックス探査：格子内外のワープ写像、ワープ下の回転フレーム、反転、ブレンドシェイプ、疎なキーフォーム。ランタイムの独立実装の照合用 | `warp-probe/*.tsv` |
| `GeneratorCostTool` | 各生成器（スケルトンの有無による Rig 生成、スケルトンのベイクのキャッシュ前後、物理グループ一覧、生成モーションのキャッシュ前後、Rig IR のコンパイル、揺れ 2 つの生成と再生、シミュレーションのベイクと書き戻し）の時間と、その入力のコンテンツハッシュ時間。生成キャッシュを付ける価値があるかの判断に使う。`PSD2LIVE_SAMPLE` でサンプルを指定（前髪と後ろ髪が必要） | `generator-cost/report.txt` |
| `ExportGoldenTool` | `tml` と `ds` の、スケルトンなし・自動スケルトン・作成したモーションの 3 種と、スケルトンなしの cmo3 を新規プロジェクトとして読み込んだものについて、全書き出しファイルとプレビューバンドルのダイジェスト（cmo3 は読み戻して moc3 に下げたものと物理グループ数）。リファクタリング前後の書き出しをバイト単位で比較する。`PSD2LIVE_GOLDEN_LABEL` で出力名を指定 | `export-golden/<名前>.txt`、`<名前>/<サンプル>-<種類>/` |
| `ExportGoldenComparisonTool` | 生成済みの金サンプルレポートを比較する。CAN3 は対応する CMO3 のランダムな部品 GUID 参照を部品 ID で解決して XML 全体を比較し、他の項目はダイジェストを比較する。両レポートと CAN3 / CMO3 ファイルを保持する。`PSD2LIVE_GOLDEN_BEFORE` / `PSD2LIVE_GOLDEN_AFTER` の既定は `baseline` / `optimized` | `export-golden/comparison.txt` |
| `WorkspaceLifecyclePerfTool` | 20,000 ノードの合成履歴と、各 4 キャンバスの 20 ワークスペースを使い、共有履歴スナップショットと表示更新時の保存用コンテンツ識別値の再利用を即時再構築と比較する。時間とスレッドの割り当て量を測定する任意実行ツールで、実際の描画フレームレートを測るものではない | `workspace-lifecycle/performance.txt` |
| `ExportPerfTool` | サンプル PSD（`PSD2LIVE_SAMPLE`）を整数倍に最近傍拡大し（`PSD2LIVE_EXPORT_SCALES`、既定 `1,2`）、書き出し先ごと（`PSD2LIVE_EXPORT_TARGETS`、既定は動画以外すべて）に時間を計り、書き出した全ファイルのダイジェスト（cmo3 は画像エントリと、読み戻して moc3 に下げたもの）を記録する。書き出し高速化の前後で時間とバイトを比較する。ラスター書き出しは拡大 1 段につき 1024 px、5 fps。`PSD2LIVE_GOLDEN_LABEL` で出力名を指定し、大きな倍率では `-Ppsd2live.testHeap=8g` などでテストのヒープを増やす | `export-perf/<名前>.txt`、`<名前>.digest.txt` |
| `ArtPrimitiveV2VisualTool` | `tml` で脚を左右の連結成分ごとに、左目のまつ毛と口をポリゴンで分割し、それぞれバージョン 1 と 2 のレコードとして書く。分割前、v1、v2、v1/v2 の差分（×4）を目の開き・半目・閉じ、口の閉じ・開き・笑顔のポーズで並べ、継ぎ目、ずれ、欠けたピクセルを確認する | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | 自動スケルトン Rig 上の、シード付きランダムなジオメトリ編集 24 件の完全なジオメトリ安全性レポート（`coverage` を除く）。検査器の変更前後で分類をバイト単位で比較する。`PSD2LIVE_GOLDEN_LABEL` で出力名を指定 | `safety-golden/<名前>.txt` |
| `BundleProfileTool` | moc3 プレビューバンドルの段階別時間（IR コンパイル、IR からの復元、静止メッシュのキャンバス空間への変換、physics3/motion3、moc の変換と書き出し、cdi3）とジオメトリ安全性検査の時間。`PSD2LIVE_SAMPLER=1` でスタックサンプラーのホットスポットも出力 | 標準出力のみ |
| `CubismParityTool` | サンプル（`PSD2LIVE_SAMPLE`）のプレビューバンドルを同梱の Cubism ランタイム（`live2d_renderer`、オフスクリーン）で描き、エディタ自身の描画と並べる。全身・顔・口のアップを、静止・振り向き・口の開き・母音・目閉じなどのポーズで比べる。`PSD2LIVE_PARITY_RECIPE=legacy` でモジュール導入前の生成を使う。Windows のみ | `cubism-parity/<サンプル>[-legacy]-<ビュー>.png` |
| `CubismCoreCheckTool` | `tml` と `ds` のプレビューバンドルの moc3 を公式 Cubism Core の整合性検査（`csmHasMocConsistency`）にかける。`PSD2LIVE_TEST_CUBISM_CORE` で Core のライブラリを指定しないと skip される。小さな新規モデルの同じ検査は `:umamo` の `MocDefaultColorsTest` にある | 標準出力のみ |
| `TextureWorkspaceTool` | `tml` にいくつかの密度とロックを設定してメッシュの形で一度配置し、アトラスのページとテクスチャパネル（中国語と英語、単一・複数・未選択、ヒートマップのオン／オフ、密度スライダーのプレビュー）、および編集キャンバスのアトラスと元画像のピクセルの比較を描画 | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | `tml` のアトラスページとテクスチャパネルのフレームコストを、ウィンドウなし・ソフトウェア描画で計測：`ImageComposeScene` に待機、ホバー、角（密度）のドラッグ、ホイールズーム、タイルのドラッグ、ワイヤーフレームなしのホバーのポインター入力を送り、各描画の時間（中央値、p90、最大）を記録し、角のドラッグ中・編集セッション・静止したページ・拡大の 4 フレームを保存。GPU 描画はウィンドウの Skia コンテキストが必要なので `AtlasWindowPerfTool` と `PreviewWindowTool` で計測する | `atlas-frame-perf/report.txt`、`{corner-drag,session,frame,zoomed}.png` |
| `AtlasWindowPerfTool` | 実際のエディタウィンドウをテクスチャワークスペースで開き（`PSD2LIVE_SAMPLE` に `.psd` か `.psd2live` を指定可。コピーを使うこと）、ポインター入力を AWT イベントとして送る（実カーソルは動かさない）：ビューモデル経由の移動と密度変更、タイルのドラッグ、角のドラッグ、互いを待たない 6 回の連続ドラッグ。フレーム間隔、UI 遅延、各ビジーフラグが下りた時刻、UI スレッドのホットスポット、ドラッグプレビューの描画回数、ビューごとの GPU フレーム数を記録。キャプチャは画面ではなくウィンドウ自身の Skia フレームから取る | `atlas-window-perf/report.txt`、`*.jfr`、`*-after.png` |
| `PreviewFpsTool` | 実際のエディタウィンドウでプレビューを再生し（`PSD2LIVE_WORKSPACE` でワークスペースプリセット、`PSD2LIVE_BACKENDS` でランタイムを指定）、フレームレートとフレーム間隔の分位、ウィンドウのフレーム周期に占める Compose の時間、UI スレッドを占めるイベントとドキュメント状態の変化頻度を報告する。`PSD2LIVE_VSYNC=0` で表示の更新を待たず実コストを測り、`PSD2LIVE_PANELS=0` でサイドパネルを隠し、`PSD2LIVE_TRACE=1` で各コンポーザブルの毎秒のコンポーズ回数、`PSD2LIVE_JFR=1` で記録と UI スレッドのホットスポット、`PSD2LIVE_SVG=1` で最後のフレームの全描画を SVG に書き出して数え、`PSD2LIVE_INVALIDATIONS=1` でウィンドウコンポジタに全体を描き直させる要求の出所を、`PSD2LIVE_CAPTURE=1` で一時停止後のウィンドウを GPU から読み出す（`PSD2LIVE_COMPOSITOR=0` でコンポジタを切って比較）。`PSD2LIVE_SCRUB=EDIT`（または `PREVIEW`）では再生の代わりにそのモードのキャンバスでパネルと同じようにパラメータのスライダーをドラッグし（`PSD2LIVE_SCRUB_PARAMETER` でパラメータ、`PSD2LIVE_SCRUB_HZ` で毎秒のサンプル数、既定 240）、サンプルごとの UI スレッドの時間を報告する。`PSD2LIVE_GESTURE=hover`（または `deform`）ではエディタ自身の入口から編集キャンバスの顔メッシュ上をホバーする（または Deform モードで全頂点をドラッグする）。システムのポインタは動かさない。コンポジタのフレーム数と全体の描き直し回数も報告する | `preview-fps/report.txt`、`{cubism,p2lrt}.{jfr,svg}`、`*-paused.png` |
| `PreviewWindowTool` | Skia を OpenGL にして実際のエディタウィンドウを開き、編集キャンバスと両方のプレビューランタイムがウィンドウの Skia コンテキストで描画されること、再生 2 秒で届くフレーム数を確認し、各ビューのテクスチャを GPU から読み出す（画面はキャプチャしない）。2 分止まるとスレッドを書き出して終了 | `preview-window/report.txt`、`{edit,cubism,p2lrt}.png` |
| `TexturePerfTool` | テクスチャコマンド（タイルの固定、密度の変更、自動配置、予算の変更）のコミット時間。`runtime` はアプリケーションのコマンドを通して再構築を別に計測し、`TEXTURE_PERF_SCENARIO` は `plain`、`skeleton`（自動スケルトン）、`deleted`（自動スケルトンで 1 層をソフト削除）から選ぶ。`TEXTURE_PERF_JFR=1` でそれらの回を `texture-perf/runtime.jfr` に記録。`desktop` はビューモデルとデスクトップアダプタを通し、Swing スレッドの停止、UI のキャプチャ、ページ PNG を記録。環境変数は Gradle タスクの入力ではないので、シナリオを替えるときは `--rerun` を付ける | `texture-perf/runtime-<シナリオ>.txt`、`desktop.txt` |
| `ExportDialogTool` | 「インポート」と「形式を指定して書き出し」サブメニューを開いた「ファイル」メニュー、各ターゲットの書き出しダイアログ、Live2D と PSD の書き出しダイアログ、「書き出し完了」ダイアログ（中国語と英語、ダークテーマ。完了ダイアログはライトテーマも）。メニューの分類、ラベルとレイアウトの確認用 | `export-dialog/<言語>-<ターゲットまたはメニュー>.png` |
| `ModalDialogTool.renderDialogs` / `.depthSplit` | 共通のモーダル枠に載せたダイアログ（中国語）。タイトル行、本文、フッターがそろっているかの確認用：`renderDialogs` は設定、ヘルプ、テクスチャ高解像度化、描画順ダイアログ（ダークテーマ）、`depthSplit` は前後分割ダイアログ（ダーク、ライト、大きな文字） | `modal-dialog/<ダイアログ>.png`、`depth-split-{dark,light,large-text}.png` |
| `CanvasLookSheetTool.meshLook` / `.toolIcons` | キャンバスが描くものの一覧：`meshLook` は編集可能なジオメトリ（`MeshLook`）の各状態（構造、選択、ホバー、ブラシの影響範囲）を暗い・中間・明るい・彩度の高い下地の上に、`toolIcons` はすべてのキャンバスツールのアイコンとプレビューレールのトグルを両テーマで | `canvas-look/mesh-look.png`、`tool-icons-{dark,light}.png` |
| `CommitPerfTool.profile` / `.desktop` | 1 回の編集コミットにかかる時間。`profile` はアプリケーション層のコマンド境界を通し、段階別（リビジョン、設定のデコード、再構築、ジオメトリ検査）に分けて計測。`desktop` はデスクトップのビューモデルとアダプタを通してメッシュ頂点編集とブラシのストロークを続けてコミットし、コミット時間と UI スレッドの最長停止を報告。`JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` と併用してサンプリング可能 | `commit-perf/report.txt`、`desktop.txt` |
| `CommitPerfTool.baseline` | コミット経路の段階別ベースライン。自動スケルトン、揺れ 2 つ、ベイク済みシミュレーション 1 つを持つプロジェクトで、完全再構築（プロセス内に保存済みの作成 Rig を使わず、基礎 Rig を生成してジャーナルを再生する）の各段階（解析、テクスチャアトラスのパッキングと PNG エンコード、基礎 Rig、スケルトンキャッシュのヒット/ミス、ジャーナル再生、揺れ/シミュレーションの書き戻し、上書き、IR、moc3 バンドル、`validateBundle`、リビジョンハッシュ、ランタイムファイルの書き出し）と、ジオメトリのコミット、小さなレイヤーへの描画、画像の差し替え（同形状/メッシュ再構築）、無関係なトポロジー編集後のスケルトンキャッシュ、ジャーナル 50/200 件追加時のコミット時間と増加を計測。各コミット後に履歴を保存する。ネイティブプレビューの再読み込み（GL コンテキストが必要）は計測しない | `commit-perf/baseline.json`、`baseline.md` |
| `CommitPerfTool.rigStages` | `baseline` の準備と生成系のコミット（レイヤー分類、レイヤーメッシュ）だけを行い、各コミットでの Rig 構築の段階を記録してから結果を完全に再構築する。`PSD2LIVE_RIG_MODES` を `staged` か `unstaged` にすると段階キャッシュの片方のモードだけを計測（既定は両方） | `commit-perf/rig-stages.json`、`rig-stages.md` |
| `SkeletonCommitTool.profile` / `.equivalence` | スケルトンのコミット。`profile` は `PSD2LIVE_SCALE` 倍（既定 2）に拡大したサンプルで、先に `PSD2LIVE_GEOMETRY_EDITS` 回（既定 24）のジオメトリコミットを行い、コマンド境界を通してボーンの作成・バインドと関節の移動（最大のメッシュをスキンする肢の関節を含む）を行い、コミット時間と Rig 構築の段階別時間を報告。`equivalence` はスケルトン・ジオメトリ・頂点グループのコミット、元に戻す/やり直し/ブランチ切り替えの後、ランタイムモデルを、キャッシュを空にし保存済みの作成 Rig を使わない冷再構築・再生と比較 | `skeleton-commit/report.txt`、`equivalence.txt` |
| `SkeletonBakeDiffTool` | スケルトンベイクの前後比較。拡大したサンプル（`PSD2LIVE_SCALE`、既定 2）で各スキンメッシュがキーフォームグリッドの各セルとブレンドシェイプの各キーで置かれるキャンバス座標を記録し、各肢を極端なポーズでレンダリング。`PSD2LIVE_LABEL` で結果に名前を付け、`PSD2LIVE_REFERENCE` を指定するとその結果と比較し、メッシュごとの最大頂点差（ピクセル）とレンダリング差を報告 | `skeleton-bake/<label>.bin`、`<label>-limbs.png`、`<label>-vs-<reference>.png` |
| `OpenPerfTool.profile` | アプリと同じ経路（`ProjectRepository.open` の後、新しい `WorkspacePreviewBuilder` がヘッドを構築）でプロジェクトを開く時間。自動スケルトン、揺れ 2 つ、ベイク済みシミュレーション 1 つを持つプロジェクトで、アプリが今書く形（各リビジョンの作成 Rig とヘッドキャッシュ）、ヘッドキャッシュのみ（保存 Rig なしの生成と再生）、どちらもなしの 3 種のアーカイブを保存し、空の作成 Rig キャッシュとスケルトンキャッシュでそれぞれ 3 回開いて、展開/シードとヘッドの構築を計測し、3 種の構築モデルが同一であることを確認 | `open-perf/report.json`、`report.md` |
| `SavePerfTool.profile` | 現実的な規模の生成プロジェクト（`PSD2LIVE_SAVE_LAYERS`、`PSD2LIVE_SAVE_SIZE`、`PSD2LIVE_SAVE_REVISIONS`）の保存時間。同じキャプチャを 3 回、開き直した後に 2 回保存し、1 回開く | `save-perf/report.txt` |
| `ExportTargetsScenario`、`GenerateAndRigScenario`、`PaintAndReplaceScenario`、`SplitThenRegenerateScenario`、`Cmo3ImportScenario`、`MotionScenario` | 合成キャラクターでの長いエンドツーエンドのシナリオ。テストスイートのシナリオ（`scenario/`）と同じく各ステップでコールドリプレイ、元に戻す、品質を確認します：全エクスポート先への書き出し、インポートから書き出しまでの最初のセッション、スケルトン下でのペイントと画像の差し替え、分割後の再生成、cmo3 のインポート・編集と cmo3 への書き戻し、組み込みモーションの編集と書き出し。パイプライン全体を長く実行するためスイートには含めません。生成・エクスポート・保存を変更したあとに実行します | テスト結果のみ |
| `RigSourcePerfTool.profile` | 生成入力を持つ大きな文書（サンプルを `PSD2LIVE_SCALE` 倍、既定 3 倍に拡大）での再構築のうち画素数に比例する段階。3 層ごとに数ピクセル切り詰め（固定矩形まで補われる）、5 層ごとに 2 倍密度とし、コールド構築・ウォーム再構築・描画後の再構築の段階別時間と、アトラスページのコールド合成とプレビュー PNG エンコードを計測 | `rig-source-perf/report.txt` |
| `MeshTraceTool.compare` / `.wrap` | `compare` は合成レイヤー（1024² テクスチャ上のまつ毛付きの目、細い髪束、キャンバス解像度のレイヤー、6000 px と 2048 px ドキュメントのレイヤー）をキャンバストレースとテクスチャトレースでメッシュ化して並べて描画（灰色がテクスチャ alpha、青がメッシュ、赤がメッシュ外の不透明テクスチャピクセル）し、頂点数、時間、`detail`、メッシュ外のピクセル数とメッシュ面積を出力。`wrap` はまつ毛、細い髪束、手をテクスチャトレースで包み込み 0〜32 でメッシュ化し、頂点数、輪郭ループ数、時間、メッシュ外のピクセル数を出力 | `mesh-trace/<ケース>.png`、`report.txt`；`mesh-wrap/<ケース>.png`、`report.txt` |
| `ReadmeShotsTool` | 実際のエディタウィンドウを開き、スタートページで「フル」プリセットを選んで `PSD2LIVE_SAMPLE` を読み込み、「メッシュごとにレイヤーを分割」の提案をすべて受け入れて読み込み、シミュレーションのベイクを待って自動スケルトンを確定し、各ワークスペースとキャンバスモードのウィンドウ自身のフレーム（画面キャプチャではない）を保存、プロジェクトを `project.psd2live` に保存します。`PSD2LIVE_SHOT_REUSE=1` で保存済みプロジェクトを開き直し、`PSD2LIVE_SHOT_W` / `_H` でウィンドウサイズを指定。トップページのスクリーンショット用 | `readme-shots/<サンプル>/*.png`、`project.psd2live` |
| `ReadmeShowcaseTool` | トップページのテスト画像：`meshStress`、`meshParams`、`meshWrap` は合成レイヤーのメッシュ（穴と細いブリッジ、縁の層数 × 充填、ラップ幅）、`rigHead`、`rigFace`、`rigBody` は 2 サンプルの頭部・顔・スケルトンのプリセットポーズ、`rigKnee` は `PSD2LIVE_KNEE_SAMPLE`（プロジェクトまたは PSD）の膝の曲げ、`bodyOrbit` は `PSD2LIVE_ORBIT_PROJECTS` の各プロジェクトに体 X / Y の動き（上下、左右、楕円）を一時的に加え、物理あり・なしで PNG フレームを書き出します（`PSD2LIVE_ORBIT` で振幅、`PSD2LIVE_ORBIT_PERIOD` で 1 周の秒数） | `readme-plates/*.png`、`readme-orbit/<プロジェクト>-{on,off}/` |

| 環境変数 | 効果 |
| --- | --- |
| `PSD2LIVE_SAMPLE` | サンプル名（`tml`、`ds`）または PSD のパス。既定は `tml`。`CommitPerfTool.desktop` は `.psd2live` プロジェクトも受け付ける |
| `PSD2LIVE_CMO3` | `ModelProfileTool.cmo3` の入力。`.cmo3` ファイルまたはそのディレクトリ |
| `PSD2LIVE_PROBES` | プロファイルで調べるパラメータ。`id=値,...`。既定は体 X・Y・Z の両端 |
| `PSD2LIVE_SHEET_PARAM` | シルエットを体 X × 体 Y ではなくこのパラメータに沿って並べる |
| `PSD2LIVE_BONES` | `MotionSheetTool.body` で自動スケルトンのボーン位置を補正。`id=頭x,頭y,尾x,尾y;...`（キャンバスピクセル） |
| `PSD2LIVE_BIND_LEGS` | `1` で脚と靴のメッシュを最初の太ももボーンにバインド |
| `PSD2LIVE_ZOOM` | 脚の拡大範囲。`左,上,右,下` をキャンバスに対する比率で指定 |
| `PSD2LIVE_VERBOSE` | `1` で `motions` が各カーブも出力 |
| `PSD2LIVE_BAKE_CONFIGS` | `SimBakeSuite` の設定。`モード数:キー数,...`。既定は `preset`（部位ごとの設定） |
| `PSD2LIVE_SIM_PARTS` | `SimBakeSuite` / `SimRootDofExperiment` の部位。カンマ区切り、既定はすべて |
| `PSD2LIVE_BAKE_METHODS` | `SimBakeSuite` の焼き込み方法。`legacy,dof` |
| `PSD2LIVE_SIM_INPUTS` | `SimBakeSuite` で残す入力 |
| `PSD2LIVE_SIM_CSV` | `SimBakeSuite` が先端の軌跡・モード値・入力を CSV に書く動き |
| `PSD2LIVE_RUNTIME_PROJECT` | `RuntimeConformanceTool.generate` が追加で書き出すプロジェクト（`.psd2live` または PSD）。ケース名は `project` |
| `PSD2LIVE_GEOMETRY_EDITS` | `SkeletonCommitTool.profile` がスケルトンのコミットの前に行うジオメトリコミットの回数。既定は 24 |
| `PSD2LIVE_RIG_MODES` | `CommitPerfTool.baseline` / `.rigStages` の生成系コミットを `staged` か `unstaged` だけにする。既定は `both` |
| `PSD2LIVE_TEST_CUBISM_CORE` | 公式 Cubism Core ライブラリのパス。`CubismCoreCheckTool` と `:umamo` の `MocDefaultColorsTest` が使い、未設定ならどちらも skip |

## コード構成

ソースは複数の Gradle モジュールに分かれ、依存は下向きだけで、ビルドがそれを保証します。Umamo から移植したエンジン `org.umamo.*` は `:umamo`（`umamo/src/main/kotlin/`。製品コードに依存しない）にあり、中立 Rig IR `:format-model`、書き出しフレームワーク `:format-compile` とラスター書き出し `:targets:raster`、ランタイムモデル `:targets:runtime` とランタイムバインディング `:format-eval` は MIT で、GPL のモジュールに依存しません。`:targets:cubism`（IR 変換、moc3、cmo3）と `:targets:psd` はエンジンを使い、製品層 `io.github.psd2live.*` はルートプロジェクト（`src/main/kotlin/`）にあります。書き出しモジュールは[中立 Rig IR と書き出しターゲット（中国語）](../../zh/spec/EXPORT_TARGETS.md)を参照してください。主なパッケージは次のとおりです。

| パッケージ | 役割 |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`、キーフォーム補間と評価 |
| `org.umamo.format` | PSD、CMO3、MOC3、画像形式の読み書き |
| `org.umamo.interop` | `PuppetModel` と CMO3 / MOC3 の相互変換 |
| `org.umamo.render` | LWJGL / OpenGL プレビュー |
| `org.umamo.edit` | モデルに対する不変の編集プリミティブ |
| `io.github.psd2live.core` | 生成パイプライン（`PSD2LivePipeline`、`LayerClassifier`、`AdaptiveMeshGenerator`、`RigBuilder`、`MotionGenerator`、`PhysicsGenerator`）と再生可能な編集 |
| `io.github.psd2live.project` | 中立なワークスペース文書（`WorkspaceDocument`）、履歴ストア（`WorkspaceStore`）と `.psd2live` アーカイブ。`application`、Compose、MCP に依存しない |
| `io.github.psd2live.application` | 中立なランタイム（`WorkspaceRuntime`）、コマンド登録とスキーマ、プレビューセッション、プロセス内のバックグラウンドジョブ。UI と MCP が共用 |
| `io.github.psd2live.history` | 分岐する元に戻す / やり直し |
| `io.github.psd2live.agent` | ローカル MCP サーバーと公開ツールの定義 |
| `io.github.psd2live.render` | 編集キャンバスと p2lrt プレビューの GPU 描画（`CanvasGpu`、`GlCanvasRenderer`、`P2lrtGlRenderer`、`SkiaGpu`） |
| `io.github.psd2live.ui` | Compose UI：`state`（ViewModel、ショートカット登録）、`views`（ワークスペースとパネル）、`components`（ダイアログと部品）、`tutorial`。キャンバス編集と描画は `ui` 直下 |
| `io.github.psd2live.i18n` | UI 文言。リソースは `src/main/resources/i18n/` |

生成と書き出しのロジックは `core` / `project` / `application` に置き、Compose のコードには書かないでください。

スタート・ヘルプ・設定はアプリのページ（`ui/state/AppPages.kt` の `AppPage`）で、プロジェクトには含まれません。各ページはエディターのドック（`DockWorkspaceView` の `DockSession` と `DockTree`）を再利用し、各ページは目次パネルと内容パネル（スタートページは詳細パネルも）からなり、パネル ID にはページの接頭辞（`start.*`、`help.*`、`settings.*`）が付き、`PageChrome.kt` の目次行と本文コンテナを共用します。既定の配置は `pageDockLayout`、配置と閉じたパネルはローカル設定（`AppSettings.pageArrangement`）に保存されます。パネルはドラッグ・フロート・再ドッキングされるため、パネル間で共有する状態は ViewModel か `StartPageModel` に置き、1 つのパネルのコンポジションには置きません。新機能の文章は `src/main/resources/whatsnew/<言語>.md` にあり、4 言語でリリースと項目が一致している必要があります（`WhatsNewTest`）。バージョン番号はビルドが `psd2live/version.properties` に書き込み、コードは `AppVersion` から読みます。

## 基本ルール：再構築と再生

`PuppetModel` は永続化されません。プロジェクトが保存するのは、元画像、レイヤー分類、設定と、シリアライズ可能な編集記録（`RigEditOverlay`）です。開くたび、また変更のたびに次の処理が行われます。

1. `RigBuilder` が元画像から基本のリグを再生成する
2. 作成段階（`RigEditOverlay.replayAuthored`）：ジャーナルの最後の `rig_checkpoint` レコードが保存した Rig から始め、なければ基本のリグから始めて旧形式の静的フィールド（パラメータの削除 / 作成、Warp、構造、キーフォーム）を先に再生し、その後の `authoringJournal` を記録順に再生する
3. 仕上げ（`RigEditOverlay.finish`）：生成器の依存グラフ（`core/DocumentGenerators.kt`）の順に揺れ、シミュレーションなどの生成器を実行し、生成結果の上書き（`generated_override`）を三方向マージし、生成パラメータを参照するパネル編集を再生する

作成 Rig が保存されているリビジョン（このプロセスで構築したもの、またはアーカイブの `rig/` にあるもの）はそこから構築し、基本のリグの生成もジャーナルの再生も行いません。詳しくは[固化 Rig（中国語）](../../zh/spec/MATERIALIZED_RIG.md)と[文書層（中国語）](../../zh/spec/DOCUMENT_LAYER.md)を参照してください。

そのため新しい編集機能では次の点を守ってください。

- 変更は `RigEditOverlay` のシリアライズ可能なフィールドに記録し（できれば編集ジャーナルの JSON コマンドとして）、ワークスペース状態のコーデックで保存・復元します。`PuppetModel` だけを変更すると再構築で失われます。
- UI と MCP は同じ編集コマンドを使います。`org.umamo.edit` の低レベルメソッドは、そのまま公開インターフェースになるわけではありません。
- 受け入れ確認の流れ：ドメインデータ → 履歴の再生 → 保存と再読み込み → 対象 Cubism バージョンの処理 → 書き出しと読み戻し → 目視確認。詳しくは[ランタイムと書き出しの境界](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)（中国語）を参照してください。

UI 文言を追加するときは、`Messages.properties`、`Messages_zh_CN.properties`、`Messages_ja.properties`、`Messages_ko.properties` の四つに同時に追加し、キー数をそろえてください（`CatalogContractsTest` が検査します）。

## 書き出し結果の確認

- `.model3.json` が参照するすべてのファイルを一緒に納品します。
- ログパネルと診断 JSON の警告を確認します。
- 編集を続けるには `.psd2live` プロジェクトを保存します。`.psd2live.json` はレポートにすぎません。

関連：[Cubism ネイティブプレビュー](CUBISM_SDK_SETUP.md) · [`build.gradle.kts`](../../../build.gradle.kts)
