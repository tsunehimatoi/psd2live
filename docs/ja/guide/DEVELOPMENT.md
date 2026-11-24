<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/DEVELOPMENT.md) · [English](../../en/guide/DEVELOPMENT.md) · [한국어](../../ko/guide/DEVELOPMENT.md) · **日本語**

# 開発とコマンドライン

[ドキュメント目次](../README.md)

このページは、ソースから実行する、コマンドラインを使う、または開発に参加するユーザー向けです。配布パッケージにはランタイムが同梱され、通常の使用にこのページの内容は不要です。

## 環境

- JDK 21。Windows では JetBrains Runtime（JBR）21 を推奨します。Windows の配布パッケージはこれを使い、メインウィンドウはその上でシステムのウィンドウ枠を残します（最大化、スナップ、辺をドラッグしたサイズ変更はシステムが処理します）。タイトルバーはプログラムが描きます。ほかの JDK でもビルド、テスト、実行はできます。Linux の不透明ウィンドウは、すべての JDK でシステムのタイトルバーを残し、透明ウィンドウは引き続きプログラムのタイトルバーを使います。Windows はほかの JDK では枠なしウィンドウを使います。Windows のパッケージング（`createDistributable`、`packageExe`）には JBR 21 SDK（jpackage 付き）が必要で、ほかの JDK でパッケージするときは `-Ppsd2live.allowNonJbr=true` を足します。macOS の DMG（`packageDmg`）は Gradle を実行している JDK でビルドし、JBR は不要です。Swift の外殻がシステムのウィンドウ枠を残すためです。Gradle はリポジトリ同梱の Wrapper を使い、別にインストールする必要はありません。
- Windows は `.\gradlew.bat`、Linux / macOS は `./gradlew` を使います。以下の例は一律 `./gradlew` と書きます。
- 公式の Cubism SDK はビルドの前提ではありません。ソースビルドは既定で内蔵レンダリングを使います。ネイティブプレビューは [Cubism Native プレビュー](CUBISM_SDK_SETUP.md) を参照してください。

## 実行

```bash
./gradlew run                     # 不带参数：启动 GUI
./gradlew run --args="--help"     # 查看 CLI 帮助
python tools/examples/fetch.py --case tml
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows では、ルートディレクトリの `run-gui.bat` を直接実行することもできます。引数なしでは GUI を起動し、引数があると CLI に入ります。このとき `--input` が必須です（`--help` を除く）。CLI は PSD から書き出しファイルを直接生成し、編集を続けられる `.psd2live` プロジェクトは作りません。

## CLI パラメータ

| パラメータ | 既定値 | 働き |
| --- | --- | --- |
| `--input <path>` | 必須 | 入力するレイヤー分け PSD |
| `--output <path>` | PSD と同じディレクトリの `psd2live-output` | 書き出しディレクトリ |
| `--lang <zh\|en\|ja\|ko>` | システム言語 | ログの言語 |
| `--clear-user-data` | — | 単独で使用します。現在のユーザーの設定、ワークスペースストレージ、`~/.psd2live` 下のランタイムとキャッシュを削除して終了します。エディタの実行中は削除せず 1 を返します。Windows のアンインストールでデータを同時に削除する選択をしたときに呼ばれます |
| `--atlas <size>` | 4096 | テクスチャアトラスのサイズ |
| `--mesh-spacing <px>` | 64 | メッシュ間隔 |
| `--mesh-pixels` | オフ | 長辺を 2048 px に縮小したドキュメントピクセルではなく、ソースピクセルでメッシュ長を計算します |
| `--mesh-wrap <単位>` | 0 | 包み込みトポロジ。この値（メッシュ単位）より狭い輪郭の隙間を閉じ、細い突起は 1 つの包絡を共有します |
| `--head-strength <value>` | 1.0 | 頭の変形の振幅 |
| `--body-strength <value>` | 1.0 | 体の変形の振幅 |
| `--mesh-only` | オフ | メッシュだけを生成します |
| `--no-deformers` | オフ | デフォーマを生成しません |
| `--no-see-through` | オフ | 眉も目も前髪を透過しません（既定ではどちらも透過し、出力はこれまでより眉の複製、前髪の目の開口とその複製が増えます） |
| `--no-see-through-eyes` | オフ | 目は前髪を透過しません（現在既定でオンの `--see-through-eyes` も引き続き受け付けます） |
| `--see-through-opacity <0..1>` | 0.5 | 眉と目が前髪を透過する程度 |
| `--no-motions` | オフ | モーションを出力しません |
| `--no-physics` | オフ | 物理演算を生成しません |
| `--no-cmo3` | オフ | CMO3 を出力しません |
| `--no-moc3` | オフ | MOC3 を出力しません |
| `--no-json` | オフ | 診断 JSON を出力しません |
| `--upscale <1\|2\|4>` | 1 | テクスチャ高解像度化の倍率。1 はオフです |
| `--upscale-python <path>` | Windows は `python`、それ以外は `python3` | nunif の依存が入った Python |
| `--nunif-dir <path>` | 空 | nunif のソースディレクトリ |
| `--upscale-model <path>` | 空 | 重みディレクトリ |
| `--upscale-tile <64..512>` | 256 | 推論のタイルサイズ |
| `--upscale-noise <-1..3>` | 1 | ノイズ低減レベル。-1 はノイズ低減なしです |
| `--no-upscale-neural-alpha` | オフ | アルファの拡大を双一次に切り替えます |

説明：

- 既定値は [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt) を基準にします。GUI の初期メッシュ間隔は `PipelineConfig`（40）から来て、CLI の 64 とは違います。
- CMO3、MOC3、診断 JSON は、少なくとも 1 種類の出力を残します。
- ニューラルアルファは既定でオンです。`--upscale-neural-alpha` は、古いコマンドとの互換のためだけに残しています。
- 高解像度化の準備と例は[テクスチャ高解像度化](TEXTURE_UPSCALE.md)を参照してください。

### ウィンドウなしの MCP サービス

`./gradlew run --args="mcp [--port N] [--token T] [--store DIR] [--profile core|full]"` はウィンドウを開かず、MCP エンドポイントだけを起動し、エージェントが単独で作業できるようにします。ワークスペースはアプリケーション層のホストが担い、ViewModel と Compose の状態は作りません。起動後、`endpoint`、`token`、`profile`、`store`、`capabilities`（`view_sample_motion=yes|no`。ビルドに Cubism Core が含まれるか）を、プロセスが終わるまで出力します。スクリプトは `python tools/mcp_proxy.py call <操作> [JSON] --edit`、または Python の `mcp_proxy.Client` で呼び出し、`PSD2LIVE_MCP_ENDPOINT` と `PSD2LIVE_MCP_TOKEN` をそこへ向けます。ワークスペースストレージの既定はユーザーディレクトリの `psd2live-mcp` の下で、エディタとは分かれます（履歴とインスタンスロックは共有しません）。`--token` は 32–256 文字の URL セーフ Base64 である必要があり、省略するとランダムに生成されます。

## ほかの形式への書き出し

`export` コマンドは、`.psd2live` プロジェクト（現在の履歴状態）または PSD（既定設定で生成）から、中立 IR を経て任意の対象へ書き出し、損失レポートを書きます。

```bash
./gradlew run --args="targets"                                            # 列出导出目标
./gradlew run --args="export model.psd2live --target gif --set clip=Nod --set size=512"
./gradlew run --args="export model.psd2live --target psd-pose --set pose=ParamAngleX=20 --output out/pose"
```

- ファイルは `--output` へ書き込まれます（既定は入力の隣の `<名前>-<対象>`）。損失レポートは `<名前>.<対象>.report.json` です。`--name` は基礎名を指定します。
- `--set key=value` は繰り返せます。各対象の設定キーは[中立リグ IR と書き出しターゲット](../spec/EXPORT_TARGETS.md)を参照してください。
- 終了コード：0 は成功、1 は書き出し失敗、2 はパラメータエラーです。

## テストとパッケージング

```bash
./gradlew unitTest                               # 本地快速单元与契约测试（不含 scenario 与外部 worker/plugin 测试）
./gradlew integrationTest                        # 场景与外部 worker/plugin 集成测试
./gradlew test                                   # 各模块日常 JVM 回归
./gradlew test pluginBuildTest                   # 完整 JVM 回归，含插件实际编译、热替换、撤销和重开
./gradlew test --tests "io.github.psd2live.scenario.CatalogContractsTest"   # 单个测试类
./gradlew createDistributable                    # 带运行时的应用目录
./gradlew packageDistributionForCurrentOS        # 当前平台的安装包
./gradlew packageDmg                             # 仅 macOS：带 Swift 外壳的 DMG
```

Linux のアプリディレクトリは、マニフェストの `Class-Path` JAR で依存の順序を残し、ネイティブランチャーの引数を短くして、長いクラスパスで JVM 起動前にクラッシュする
[JDK-8380085](https://github.com/openjdk/jdk/commit/54c5e41cbaabcd21d7bbf8f5667b2e35cfe12dd7)
を避けます。JDK またはパッケージ設定を変えたあとは、次の検査を実行します。一時的なユーザー設定を使い、空白、引用符、非英字を含む移行パスを検査します。

```bash
python3 packaging/linux/smoke-test.py build/compose/binaries/main/app/PSD2Live
```

- `packageDmg` は macOS 上だけで使えます。`createDistributable` がパッケージに入れたランタイムを使い、起動プログラムを `packaging/macos` の Swift 外殻へ置き換えるので、Dock 名、ドキュメントタイプ、ウィンドウボタンはシステムのものです。メニューは画面上部のメニューバーに出ます。ウィンドウ内のメニューは残ります。そこにすべてのコマンドが並んでいるためです。DMG のアーキテクチャは、それをビルドした Mac と同じです（Apple シリコンまたは Intel）。macOS 13 以降が必要です。パッケージは公証されていません。ネットワークからダウンロードしたあとは、Finder で補助クリックして開き、`PSD2Live` を「アプリケーション」へドラッグします。
- `CI` は Linux、Windows、macOS の x64 と ARM64 でネイティブランタイムとアルゴリズムプラグインをビルドし、JVM 回帰を実行し、パッケージ内の worker を検査します。Linux は展開後の Deb から CLI を起動し、Windows はアプリイメージを使い、macOS はマウント後の DMG も検証します。入れ子の Gradle を起動する `pluginBuildTest` は、Linux x64、Windows x64、macOS ARM64 でそれぞれ 1 回実行され、ローカルの `check` にも含まれます。ネイティブランチャーのエラー処理を検証し、パッケージ内ランタイムでマウント後のアプリを起動します。共有の `.github/actions/package-macos` は、リリースワークフローにも 2 つのアーキテクチャの DMG を提供し、Cubism SDK は含みません。
- 2 種類のパッケージングは、どちらも現在のビルドプラットフォームのネイティブライブラリだけを含み、アプリのリソースディレクトリに `LICENSE`、`THIRD_PARTY_NOTICES.md`、`licenses/` を同梱します。
- 公式 SDK のリソース（`src/main/resources/cubism/`）は既定ではパッケージされず、`-Ppsd2live.includeCubism=true` を渡すか `PSD2LIVE_INCLUDE_CUBISM=true` を設定したときだけ含まれます。SDK を含むパッケージは公開配布してはならず、リリース手順は [CI とリリース](CUBISM_CI_RELEASE.md) を参照してください。
- Linux では `./native/package_linux.sh` でも、システムの JDK 21 を必要とするローカル起動パッケージを生成できます（出力は `dist/linux-<タイムスタンプ>/`）。詳しくは [native/README.md](../../../native/README.md) を参照してください。
- プロジェクトに独立した lint タスクはなく、コードスタイルは `kotlin.code.style=official` です。
- Rust ランタイムは `runtime/` で `cargo test`、`cargo build --release` によりビルドします。詳しくは[ランタイム](../spec/RUNTIME.md)を参照してください。`./gradlew run` とパッケージングは、使える cargo がないとき、Linux 上では CI（`runtime-prebuilt.yml`）が同じソースからビルドしたライブラリをダウンロードし、`runtime/src`、`Cargo.toml`、`Cargo.lock` のハッシュで照合し（`./gradlew printRuntimeHash`）、sha256 を検証します。見つからない、またはオフラインのときは内蔵評価器へフォールバックします。`-Ppsd2live.prebuiltRuntime=false` でダウンロードをオフにします。`runtime/` を変えて繰り返し実行するときは `-Ppsd2live.fastRuntime=true` を足せ、LTO をオフにしてコンパイルを大幅に短縮します。生成されるライブラリはより遅いので、パッケージングには使わないでください。
- テストスイートに残すのは高速な単体テストと契約テストだけで、全量の `./gradlew test` は 1 分以内に終わるべきです。サンプル PSD（tml、ds）上で完全なパイプラインを走らせる、コールド再生を段階的に比べる、画面全体をレンダリングするテストは加えないでください。この種の検査は次の開発ツールとして書き、必要なときに手動で実行します。

### アルゴリズムプラグインのランタイム検査

Windows でアプリイメージをビルドするときは、Windows SDK の `mt.exe` が必要です。パッケージタスクは、アプリとパッケージ内の Java ランチャーへ UTF-8 マニフェストをマージし、システムをまたぐコードページのインストールパスからランタイム DLL を読み込めるようにします。

アプリ内のアルゴリズムプラグインは `java.home/bin/java`（Windows は `java.exe`）で独立した JVM を起動するので、パッケージしたランタイムは Java ランチャーを残す必要があります。
次の検査は、パッケージ内の本物のアルゴリズム worker を使い、システムの JDK がない状態、一時的なユーザー設定、空白と非英字を含む移行パスの下で起動を検証します。

```bash
python3 packaging/smoke-test-workers.py build/compose/binaries/main/app/PSD2Live
# macOS 使用 build/PSD2Live.app；Windows 使用对应的 PSD2Live 应用目录。
```

### Linux ウィンドウの回帰検査

本物のグラフィックスセッションが必要です。JDK 21 の AWT は、Wayland セッションでは XWayland 経由で動きます。
不透明ウィンドウの移動、サイズ変更、最大化はウィンドウマネージャに渡し、デスクトップ全体のワークスペースを単一ディスプレイのワークスペースと取り違えるのを避けます。

```bash
./gradlew :test --tests '*NativeTitleBarTest' --tests '*BugListRegressionTest'
PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*LinuxWindowModesTool'
```

グラフィックスツールは、最大化したワークスペース、復元サイズ、外部ウィンドウ状態、単軸の最大化を検査します。通常のテスト実行はこれを飛ばします。
システムのタイトルバーのドラッグ、角のサイズ変更、最大化ボタンとデスクトップショートカット、異なるサイズ／スケールのディスプレイ間の移動、ディスプレイを抜いたあとの再オープン、F11 のフルスクリーン切り替え、透明ウィンドウのドラッグ復元は、人手で確認します。Windows/macOS でも、ウィンドウ操作のスモークテストを行うべきです。

独立した設定でソースビルドを実行し、インストール済みのプログラムとワークスペースを共有しないようにします（先に `JAVA_HOME` を JDK 21 へ向けます）。

```bash
./gradlew writeRunArgs
mkdir -p build/gui-check/home
"$JAVA_HOME/bin/java" \
  "-Duser.home=$PWD/build/gui-check/home" \
  "-Djava.util.prefs.userRoot=$PWD/build/gui-check/home/prefs" \
  @build/run/jvm.args
```

## 外部サンプルと受け入れ

サンプル素材、プロジェクト、展示出力は [psd2live-examples](https://github.com/tsunehimatoi/psd2live-examples) で管理され、ライセンスが別に記されていないときは既定で CC0 1.0 です。Python 3.11+ のダウンロードコマンドは `python tools/examples/fetch.py --case tml` です。`--all` は固定されたすべてのケースをダウンロードします。ファイルは引き続き `examples/<id>/` へ書き込まれ、ダウンロードキャッシュは `build/example-assets/` にあります。

本プロジェクトの `tools/examples/fixtures.lock.json` は、素材のバージョン、ダウンロード URL、圧縮パッケージ、ファイルごとの SHA-256 とサイズを固定します。素材のアップグレードはロックファイルを独立して変更することで行い、サンプルリポジトリの最新ブランチには追従しません。ダウンロードの失敗、サイズまたはハッシュの不一致はエラーになります。

通常の CI は小さな合成素材と内部の契約テストを使い、大きなサンプルはダウンロードしません。GitHub Actions の `Example acceptance` ワークフローは手動で有効にし、固定された `tml`、`ds` をダウンロードし、`ExportGoldenTool` で完全な生成、書き出し、CMO3 インポート経路を検査し、4 つのバリアントの書き出しレポートとプレビューレポートが揃っていることを要求します。レポートは生成経路の検査に使い、視覚的な受け入れや履歴ベースラインの比較に代わるものではありません。リリース前に実行し、アップロードされたレポートを確認できます。MCP の内部回帰テストは本リポジトリに残り、外部の使用例と発見の検査はサンプルリポジトリを参照してください。

<a id="开发工具"></a>

## 開発ツール

`src/test/kotlin/io/github/psd2live/tools/` にあるのは、目視確認と計測のための開発ツールです。パイプラインの内部インターフェースを呼べるようにテストとして書かれており、`PSD2LIVE_TOOLS=1` を設定したときだけ実行され、普段の `./gradlew test` は飛ばします。出力は `build/tools/` へ書かれます。開発ツールをオンにすると、テストタスクは Gradle の結果キャッシュとスキップ判定を無効にし、計測が毎回実際に実行されるようにします。

```bash
python tools/examples/fetch.py --case ds
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

先頭フレームツールの計測範囲は異なります。オフスクリーンツールは GPU 同期と読み戻しを含み、モデル生成、GL context の作成、Compose の合成、ウィンドウ提示は含みません。`renderer_cold` が指すのは新しい renderer の作成だけで、プロセスのコールドスタートではありません。ウィンドウツールは、本物のアプリ、ビューポート、Skiko の再生を経て GPU 完了の受領を得ます。現在は P2LRT に限定されます。モデル生成は完了し、JVM とウィンドウは温まっていて、ウィンドウのスワップ、システムのデスクトップ合成、ディスプレイの走査は覆いません。2 つの指標はどちらも完全な編集遅延ではなく、アルゴリズム生成の所要時間と直接つなげることもできません。

`verifyReadback`、`smoke`、`verifyWindowPixels` は機能検査に使い、性能の分位数は出力しません。`measure` は各シーン／モードで有効サンプルを少なくとも 30 個集め、ウォームアップサンプルは統計に入れません。タイムアウトまたは検証失敗はゼロ遅延としては記録せず、成功統計は出力しません。ウィンドウツールは、グラフィックス初期化の前に `PSD2LIVE_COMPOSITOR=1` または `0` を設定し、それぞれ実行して実際のモードを照合し、切り替える前にレポートを保存する必要があります。ウィンドウピクセルの読み戻しと PNG エンコードは所要時間を乱すので、その入り口は性能サンプルにせず、ウィンドウスワップやディスプレイ走査も計測しません。GPU／性能のサンプリングは独占して実行し、ビルドやほかの性能タスクと並列にしないでください。入り口のパラメータと完全な境界は、最初の正しい画面の計測境界を参照してください。

| ツール | 内容 | 出力 |
| --- | --- | --- |
| `FirstCorrectFrameTool.verifyReadback` / `.measure` | 3 段階の合成メッシュの既知ピクセル検査と、キャプチャ改訂からオフスクリーン編集キャンバスの GPU 読み戻し完了までの計測。新規作成した renderer とウォームアップ後の renderer をそれぞれ覆います | `first-correct-frame-check/report.json`、`first-correct-frame/report.json` |
| `WindowActivationTool.smoke` / `.measure` | 本物のアプリウィンドウで、P2LRT モデルのアクティベーションキャプチャからウィンドウ GPU 完了までの受領検査と計測。2 つの compositor モードをそれぞれ実行します | `window-activation-smoke/report.json`、`window-activation/report.json` |
| `WindowActivationTool.verifyWindowPixels` | 実際の Skiko ウィンドウ Surface を読み戻し、4 つの模様の改訂色、ビューポート位置、最初の GPU 受領の同一性を照合します。2 つの compositor モードをそれぞれ実行し、機能受け入れだけに使います | `window-activation-pixels/report.json`、`window-activation-pixels/window-*.png` |
| `WindowActivationTool.verifyDocumentEdits` / `.measureDocumentEdits` | 本物の公開編集リクエストが、候補ビルドと CAS を経てウィンドウの正しいフレームへ至ります。編集、ビルド、改訂、実際のピクセルを照合し、2 つの compositor モードをそれぞれ実行します | `window-document-edits/report.json`、`window-document-edits-measure/report.json` |
| `DeformerDeletionVisualTool` | 合成した非対称メッシュについて、親デフォーマ削除の前後と、MOC3 書き出しの読み戻し後のシルエット対照 | `deformer-deletion/comparison.png` |
| `MotionSheetTool.motions` | 各プリセットモーションを時間で展開したコラージュ、待機ループ、呼吸の前後と差分図、体の Z | `motion-sheet/<サンプル>-*.png` |
| `MotionSheetTool.body` | 体の X × 体の Y、脚と上半身のクローズアップ、前後傾、大小の変化、脚のポーズ。スケルトンなしと自動スケルトンのそれぞれ | `motion-sheet/<サンプル>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | ポインタが画面上を 12 秒かけてゆっくり周回し、頭と体がプレビューのゲインと速度で追従します | `motion-frames/<サンプル>-track/` |
| `MotionSheetTool.idle` | 待機 12 秒のフレームごとの画像 | `motion-frames/<サンプル>-idle/` |
| `ModelProfileTool.cmo3` | `.cmo3` のパラメータ、デフォーマツリー（メッシュ軸と範囲）、グラフィックメッシュ、区間運動のプロファイル、体のパラメータの下での各メッシュの変位、体の X × 体の Y のシルエット、および物理グループ | `model-profile/<名前>.txt`、`.png`、`-physics.txt` |
| `RigStudyTool` | 完成した Cubism モデルを調べます（`PSD2LIVE_MOC3`：`.model3.json` またはそのディレクトリ。複数モデルを含められます）。パラメータとグループおよび用途、パーツツリー、デフォーマツリーと各オブジェクトのキーフォーム軸、メッシュ密度、マスク、Glue、物理演算、表情、モーション、VTube Studio のマッピング。パラメータごとの変形量、不透明度 / 描画順 / 色の変化と半区間の非線形度。既定ポーズ、各パラメータの 5 フレーム走査（その影響範囲へ切り抜き）、頭の角度、目、口、体のメッシュ図とメッシュワイヤーフレーム。`PSD2LIVE_STUDY_ONLY` はパスで絞り込み、`PSD2LIVE_STUDY_REVIEW=1` はレビューツールのポーズだけで目の開閉、見開き、視線のシーケンスを出します（`review-eye.png`）。大きなモデルには `-Ppsd2live.testHeap=16g` が必要です | `rig-study/<ディレクトリ>/profile.txt`、`effects.txt`、`*.png` |
| `FitDetectionTool` | サンプル（`PSD2LIVE_SAMPLE`）の特徴点フィットを原画の上に重ね描きします。各目の目頭・目尻、上下のまぶたの縁、原画の閉眼線、およびフィットしたまぶたが各開きキーで取る上まぶたの縁（黄から青）。口を開けたポーズでの上唇の上縁（白）と下唇の下縁（シアン）。口のレイヤーを列挙し、フィットできず既存アルゴリズムへ戻った理由を出力します | `fit-detection/<サンプル>.png` |
| `RigReviewTool` | サンプル（`PSD2LIVE_SAMPLE`）を、新しいプロジェクトのレシピと、モジュール導入前の既存の生成方式でそれぞれ一度生成し、レビューポーズとモーション区間の下で対照します。部位ごとにポーズ板 1 枚（上下 2 行）、区間ごとに物理演算付きで並べて再生する GIF 1 枚（まばたき、ゆっくりまばたき、笑い目のまばたき、視線、発話、母音、眉、頭を回す、うなずきと首かしげ、頭を振る、体、待機）。`PSD2LIVE_REVIEW` は部位を選び（eye、brow、mouth、head、hair、body、motion。既定はすべて）、`PSD2LIVE_REVIEW_SIZE` は単一フレームの辺の長さです | `rig-review/<サンプル>/<部位>-poses.png`、`<区間>.gif` |
| `ModelProfileTool.sample` | 生成モデル（スケルトンなしと自動スケルトン）の区間運動プロファイル、体のレイヤー、自動ボーン | `model-profile/<サンプル>.txt` |
| `SkeletonAutoViewTool.view` | 自動スケルトンをサンプルの上に重ね描きし、各ボーンを出力して、関節位置と分岐を確認します。`PSD2LIVE_SAMPLES` はカンマ区切りで複数のサンプルまたは PSD パス、`PSD2LIVE_ZOOM=左,上,右,下`（キャンバスピクセル）は部分を切り出します | `skeleton-auto/<サンプル>.png` |
| `SimBakeSuite` | モデルプリセットに従って後ろ髪、前髪、衣装などの物体を生成し（上部 10% を固定した後ろ髪を追加）、旧方法と入力ごとの方法でベイクし、フィットに参加しなかったモーション（フェイストラッキングに類する追従、首かしげ、組み込みモーション、入力ごとのステップ、頭を回す、ドラッグ、揺らし）の上で参照シミュレーションと書き出しモデルの視覚指標を比べます。詳しくは[シミュレーションとベイク](SIMULATION.md) | `build/tools/sim-bake/<サンプル>/`：`summary.md`、各設定の JSON、先端の軌跡図 |
| `DirectSimulationReviewTool.exportedAnimations` | 同じ入力で、完全な XPBD、または MOC3 と物理ファイルの書き出し読み戻しを再生します。既定では本物のプリセットと 5 つのキーフォームを使います。`PSD2LIVE_DIRECT_REVIEW_TRACK=xpbd` を設定すると 60 Hz の完全なシミュレーションを記録し、読み戻し時は `PSD2LIVE_DIRECT_REVIEW_REFERENCE_OUT` を参照出力へ向けて頂点ごとに比べます。`PSD2LIVE_DIRECT_REVIEW_FIXTURE=controlled` で強い腰の回転を単独で測るか、`PSD2LIVE_DIRECT_REVIEW_KEYS=9` でキーフォーム密度を確認します。`PSD2LIVE_DIRECT_REVIEW_MIX_ANALYSIS=1` を設定すると、混合区間の軸ごとの変位の重ね合わせ、重み制限、三階フレーム差を記録します。`PSD2LIVE_DIRECT_REVIEW_MIX_ONLY=1` で混合区間だけを実行します | `build/tools/direct-simulation/` の 25 fps 画像シーケンス、フレームごとの CSV、書き出しモデルと参照軌跡 |
| `SimRootDofExperiment` | 物体が根元の平行移動と回転にだけ応答するかを検査します。各入力が根元をどれだけ動かし、形状をどれだけ余分に変えるか、本物の駆動と剛体駆動、異なる振幅、同じ根元の動きを持つ異なる入力の間の差です | `build/tools/sim-root-dof/<サンプル>.md` |
| `SimBakeProfileTool` | `tml` の後ろ髪をシングルスレッドとマルチスレッドでそれぞれ一度ベイクし、進行マークで区間の時間を計り、すべてのスレッドをサンプルします | 標準出力 |
| `PendulumKernelTool` | 振り子配列カーネル（正確モードと高速モード）と `PhysicsEngine` のフレームごとの所要時間および偏差 | 標準出力 |
| `CanvasPerfTool` | Xvfb の下で本物のウィンドウを開き、編集キャンバスに対して静止、ホバー、ホイールズーム、中ボタンのパン、変形モードで顔のすべての点をドラッグ、を順に行い、GPU レンダリングとソフトウェアレンダリングをそれぞれ 1 周し、フレーム間隔、UI スレッド遅延、UI スレッドのホットスポット、段階ごとの編集キャンバスの GPU フレーム数を報告します（GPU の 1 周が 0 なら、キャンバスは GPU を通っていません）。`PSD2LIVE_CANVAS_AA` はキャンバスのアンチエイリアスを選び、`PSD2LIVE_OVERLAY=1` は性能オーバーレイを開きます。`Robot` で本物のポインタを動かすので、専用の X サーバー上で実行する必要があります（`xvfb-run -a -s "-screen 0 1920x1080x24"`、または GPU のあるマシン上の別の Xorg）。日常使いのデスクトップでは実行しないでください | `canvas-perf/report.txt`、各段階のスクリーンショットと `.jfr` |
| `DragonBonesFidelityTool` | `tml`、`ds`（スケルトンなしと自動スケルトン）を DragonBones へ書き出し、`tools/dragonbones-check`（公式 DragonBones 5.7 ランタイムコア。node が必要）で再生します。パラメータアニメーションを各キーフレームでエディタ評価器と比べ（誤差は書き出しレポートの許容差を超えない）、区間誤差を計測します | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool.generate` / `.physics` | Rust ランタイムの参照データです。`generate` は、ランダムモデル（Warp、回転、入れ子、疎なメッシュ、ブレンド形、Glue、チャンネル、パーツ）、サンプル、`PSD2LIVE_RUNTIME_PROJECT` が指定したプロジェクトの、ランダムポーズでのエディタ評価結果です（スケルトンサンプルには、最も細かいサンプリングの `rig.fine.p2lrt` もあります）。`physics` は、ランダムな振り子グループとサンプル物理のフレームごとの軌跡です。`runtime/` の `p2lrt-conformance` で比べます | `runtime-conformance/<ケース>/`、`runtime-physics/<ケース>/` |
| `RuntimeConformanceTool.simulation` / `.simulationRig` | シミュレーションの参照軌跡です。`simulation` は、ランダムな布片（上辺を固定、剛性はランダム）が、揺れ、回転、跳ねる目標と固定点駆動の下で、エディタの XPBD ソルバを通ったフレームごとの結果で、半分は移動するカプセル衝突体を持ちます。`simulationRig` は、`tml` の後ろ髪（上部 10 分の 1 を固定）をベイクして書き出したモデルとリアルタイムシーン、および未ベイクの Rig 上でエディタシーンが頭と体のモーションに従った結果です。それぞれ `p2lrt-conformance --sim`、`--sim-rig` が読みます | `runtime-sim/<ケース>/trace.bin`、`runtime-sim-rig/back-hair/` |
| `RuntimeSamplesTool` | プレーヤーを目視確認するための合成モデルです。グラデーション上の各色のブレンドモード、乗算色とスクリーン色、マスクと反転マスクで、ソフトウェアラスタライザが参照図をレンダリングします | `runtime-samples/features.p2lrt`、`features.png`、`layout.txt` |
| `WarpProbeTool` | エディタ評価器のブラックボックス探査です。Warp の写像（格子の内外）、Warp の下の回転フレーム、反転、ブレンド形、疎なメッシュで、ランタイムの独立実装と対照します | `warp-probe/*.tsv` |
| `GeneratorCostTool` | 各ジェネレーター（Rig 生成のスケルトンあり／なし、スケルトンベイクキャッシュの前後、物理グループの目録、生成モーションキャッシュの前後、Rig IR のコンパイル、2 つのスイングの生成と再生、シミュレーションのベイクと書き戻し）の所要時間、およびその入力の内容ハッシュの所要時間です。ジェネレーターを生成キャッシュへ接続する価値があるかの判断に使います。`PSD2LIVE_SAMPLE` はサンプルを指定します（前髪と後ろ髪が必要） | `generator-cost/report.txt` |
| `ExportGoldenTool` | `tml`、`ds` のスケルトンなし、自動スケルトン、カスタムモーションの 3 バリアント、およびスケルトンなしの cmo3 を新しいプロジェクトとしてインポートしたあとのすべての書き出しファイルとプレビューパッケージの要約です（cmo3 は読み戻し後に moc3 へ降格した要約と物理グループ数）。再構成の前後の書き出しをバイトごとに比べます。`PSD2LIVE_GOLDEN_LABEL` は出力名を指定し、`PSD2LIVE_GOLDEN_RECIPE=legacy` はモジュール導入前のプロジェクト（旧レシピ）で生成し、古いプロジェクトの書き出しが変わらないことを確認します | `export-golden/<名前>.txt`、`<名前>/<サンプル>-<バリアント>/` |
| `LayoutGoldenTool` | `ExportGoldenTool` と同じ要約ですが、テスト用の合成キャラクター（全身キャラクター、口）で PSD を生成し、サンプルのダウンロードは不要です。パイプラインが 3 バリアントを書き出し、PSD をインポートしたあと書き出しセッション経由の moc3 / cmo3 書き出しです。変更前後の書き出しをバイトごとに比べます（CAN3 はランダム GUID を含むので毎回違います）。`PSD2LIVE_GOLDEN_LABEL` は出力名を指定します | `layout-golden/<名前>.txt` |
| `LayoutPanelsTool` | ウィンドウなしで素材パネル、レイヤー表のタグセル、生成バーをレンダリングします。合成 PSD のインポート、素材を 1 つ置いて生成待ちにする、ダブルクリックでフォルダを開く、項目を選択する、項目をキャンバスの着地点へドラッグして配置する（着地点はテストが登録し、着地点を中心に検査）、生成ボタンのホバー、タグメニューを開いて閉じてから再度ドラッグアンドドロップします | `layout-panels/*.png` |
| `LayoutWindowTool` | 本物のエディタウィンドウ（OpenGL）を開き、合成 PSD をインポートし、Layout ワークスペースへ切り替え、AWT のマウスイベントで素材フォルダを素材パネルからキャンバスへドラッグして着地点を検査し、生成バーのボタンをクリックして生成完了を検査します。スクリーンショットはウィンドウ自身の Skia フレームから取ります | `layout-window/report.txt`、`{layout,dragging,placed,generated}.png` |
| `ExportGoldenComparisonTool` | 生成済みの 2 つのゴールデンサンプルレポートを比べます。CAN3 は対になる CMO3 のパーツ ID でランダム GUID 参照を解決してから完全な XML を比べ、残りは要約を比べます。2 つのレポートと対応する CAN3 / CMO3 ファイルを残します。`PSD2LIVE_GOLDEN_BEFORE` / `PSD2LIVE_GOLDEN_AFTER` の既定は `baseline` / `optimized` です | `export-golden/comparison.txt` |
| `WorkspaceLifecyclePerfTool` | 20,000 ノードの履歴、20 ワークスペースに各 4 キャンバスを合成します。共有履歴スナップショット、純粋な表示保存の同一性再利用、即時再構築の所要時間とスレッド割り当てを比べます。開発ツールを有効にしたときだけで、本物のレンダリングフレームレートを表しません | `workspace-lifecycle/performance.txt` |
| `ExportPerfTool` | サンプル PSD（`PSD2LIVE_SAMPLE`）を整数倍の最近傍で拡大したあと（`PSD2LIVE_EXPORT_SCALES`、既定 `1,2`）、書き出し対象を 1 つずつ（`PSD2LIVE_EXPORT_TARGETS`、既定は動画以外のすべて）時間を計り、各書き出しファイルの要約を記録します（cmo3 は中の画像項目と、読み戻し後に moc3 へ降格した要約）。書き出し高速化の前後の所要時間とバイトを比べます。ラスター対象は拡大の各段階で 1024 ピクセル、5 fps を出力します。`PSD2LIVE_GOLDEN_LABEL` は出力名を指定し、大きな倍率には `-Ppsd2live.testHeap=8g` などでテストヒープを増やします | `export-perf/<名前>.txt`、`<名前>.digest.txt` |
| `ArtPrimitiveV2VisualTool` | `tml` 上で、脚を側の連結成分で分割、左まつげをポリゴン分割、口をポリゴン分割したものを、それぞれバージョン 1 とバージョン 2 の記録として書き、未分割、v1、v2、v1/v2 の差（×4）を、目を開ける／半閉じ／閉じる、口を閉じる／開ける／開けて笑うなどのポーズの下で対照し、継ぎ目、ずれ、欠けたピクセルを確認します | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | 自動スケルトンの Rig 上で、シード付きのランダムなジオメトリ編集 24 組の完全なジオメトリ安全レポート（`coverage` は含まない）です。チェッカー変更の前後の分類をバイトごとに比べます。`PSD2LIVE_GOLDEN_LABEL` は出力名を指定します | `safety-golden/<名前>.txt` |
| `BundleProfileTool` | moc3 プレビューパッケージの項目別所要時間（IR コンパイル、IR の戻し、静止メッシュのキャンバス空間への変換、physics3/motion3、moc の降格と書き出し、cdi3）およびジオメトリ安全検査の所要時間です。`PSD2LIVE_SAMPLER=1` はスタックサンプルのホットスポットも出力します | 標準出力のみ |
| `CubismParityTool` | サンプル（`PSD2LIVE_SAMPLE`）のプレビューパッケージを、アプリ同梱の Cubism ランタイム（`live2d_renderer`、オフスクリーン）でレンダリングし、エディタ自身のレンダリングと並べて対照します。全身、顔、口のクローズアップで、静止、頭を回す、口を開ける、口形、目を閉じるなどのポーズを含みます。`PSD2LIVE_PARITY_RECIPE=legacy` はモジュール導入前の生成方式へ切り替えます。Windows のみです | `cubism-parity/<サンプル>[-legacy]-<ビュー>.png` |
| `CubismCoreCheckTool` | `tml`、`ds` のプレビューパッケージ内の moc3 を、公式 Cubism Core の整合性検査（`csmHasMocConsistency`）へ渡します。`PSD2LIVE_TEST_CUBISM_CORE` で Core の動的ライブラリを指定する必要があり、そうでなければ飛ばします。小さな新規モデルの同じ検査は、`:umamo` の `MocDefaultColorsTest` にあります | 標準出力のみ |
| `TextureWorkspaceTool` | `tml` でいくつかの密度とロックを設定し、メッシュ形状に従って一度配置したあと、テクスチャアトラスページとテクスチャパネル（中英、単一選択／複数選択／未選択、ヒートマップのスイッチ、密度スライダーのプレビュー）、および編集キャンバスのテクスチャアトラスピクセルと元ピクセルの比較をレンダリングします | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | ウィンドウなし、ソフトウェアレンダリングで、`tml` のテクスチャアトラスページとテクスチャパネルのフレーム所要時間を計測します。`ImageComposeScene` へ、アイドル、ホバー、角のドラッグ（密度）、ホイールズーム、図塊のドラッグ、ワイヤーフレームを閉じたあとのホバーのポインタ入力を送り、各レンダリングの所要時間（中央値、p90、最大）を記録し、角のドラッグ中、編集セッション、静止ページ、拡大した近景の 4 フレームを保存します。GPU レンダリングにはウィンドウの Skia コンテキストが必要で、`AtlasWindowPerfTool` と `PreviewWindowTool` が計測します | `atlas-frame-perf/report.txt`、`{corner-drag,session,frame,zoomed}.png` |
| `AtlasWindowPerfTool` | 本物のエディタウィンドウを開いてテクスチャワークスペースへ入り（`PSD2LIVE_SAMPLE` で `.psd` または `.psd2live` を指定できます。複製を使ってください）、AWT イベントでウィンドウへポインタ入力を送ります（本物のカーソルは動かしません）。ビューモデル経由で移動と密度を確定し、図塊をドラッグし、角をドラッグし、待たない連続ドラッグを 6 回行います。フレーム間隔、UI スレッド遅延、各ビジーフラグがいつ下がるか、UI スレッドのホットスポット、ドラッグプレビューの描画回数、各ビューの GPU フレーム数を記録します。スクリーンショットはウィンドウ自身の Skia フレームから取り、画面はキャプチャしません | `atlas-window-perf/report.txt`、`*.jfr`、`*-after.png` |
| `PreviewFpsTool` | 本物のエディタウィンドウを開いてプレビューを再生し（`PSD2LIVE_WORKSPACE` はワークスペースプリセット、`PSD2LIVE_BACKENDS` はバックエンドを選びます）、フレームレートとフレーム間隔の分位、ウィンドウフレーム周期の中の Compose の所要時間、UI スレッドの各種イベントの所要時間、ドキュメント状態の変化頻度を報告します。`PSD2LIVE_VSYNC=0` は表示更新を待たずに実際のコストを測り、`PSD2LIVE_PANELS=0` はサイドバーを隠し、`PSD2LIVE_TRACE=1` は各コンポーザブル関数の毎秒の合成回数を集計し、`PSD2LIVE_JFR=1` は記録して UI スレッドのホットスポットを列挙し、`PSD2LIVE_SVG=1` は最後のフレームのすべての描画を SVG へ書き出して数え、`PSD2LIVE_INVALIDATIONS=1` はウィンドウコンポジタに画面全体を描き直させるリクエストの出どころを列挙し、`PSD2LIVE_CAPTURE=1` は一時停止後に GPU からウィンドウ画面を読み出します（`PSD2LIVE_COMPOSITOR=0` はコンポジタをオフにし、比較に使います）。`PSD2LIVE_SCRUB=EDIT`（または `PREVIEW`）は、そのモードのキャンバス上でパネル方式によりパラメータスライダーをドラッグするように変え（`PSD2LIVE_SCRUB_PARAMETER` はパラメータ、`PSD2LIVE_SCRUB_HZ` は毎秒サンプル数、既定 240）、各サンプルの UI スレッド上の所要時間を報告します。`PSD2LIVE_GESTURE=hover`（または `deform`）は、エディタ自身の入り口を経て編集キャンバスの顔メッシュ上でホバーする（または Deform モードですべての点をドラッグする）もので、システムのポインタは動かしません。コンポジタのフレーム数と画面全体の再描画回数も報告します | `preview-fps/report.txt`、`{cubism,p2lrt}.{jfr,svg}`、`*-paused.png` |
| `PreviewWindowTool` | 本物のエディタウィンドウを開き（Skia は OpenGL）、編集キャンバスと 2 つのプレビューバックエンドがウィンドウの Skia コンテキストでフレームを出すこと、2 秒再生して受け取ったフレーム数を検査し、GPU から各ビューのテクスチャを読み出します（画面キャプチャはしません）。2 分止まったときはスレッドをダンプして終了します | `preview-window/report.txt`、`{edit,cubism,p2lrt}.png` |
| `TexturePerfTool` | テクスチャコマンド（図塊の固定、密度の変更、自動配置、予算の変更）の確定所要時間です。`runtime` はアプリコマンドを経て再構築を単独で時間計測し、`TEXTURE_PERF_SCENARIO` は `plain`、`skeleton`（自動スケルトン）、または `deleted`（自動スケルトンで 1 レイヤーをソフト削除）を取り、`TEXTURE_PERF_JFR=1` はこれらの周回を `texture-perf/runtime.jfr` へ記録します。`desktop` はビューモデルとデスクトップアダプタを通り、Swing スレッドの停止、画面の取り込み、ページ PNG を記録します。環境変数は Gradle タスクの入力ではないので、シーンを変えるときは `--rerun` を足します | `texture-perf/runtime-<シーン>.txt`、`desktop.txt` |
| `ExportDialogTool` | 「ファイル」メニューが「インポート」と「書き出し」サブメニューを展開した状態、各対象の「書き出し」ダイアログ、Live2D と PSD の書き出しダイアログ、および「書き出し成功」ウィンドウ（中英。成功ウィンドウにはライトテーマもあります）です。メニューのグループ、ラベル、コントロールの配置を確認します | `export-dialog/<言語>-<対象またはメニュー>.png` |
| `ModalDialogTool.renderDialogs` / `.depthSplit` | 共通のポップアップ外枠上のポップアップ（中国語）です。タイトルバー、本文、下部の操作バーが一致するかを確認します。`renderDialogs` は設定、ヘルプ、テクスチャ高解像度化、描画順のポップアップ（ダークテーマ）、`depthSplit` は前後分割のポップアップ（ダーク、ライト、大きな文字）です | `modal-dialog/<ポップアップ>.png`、`depth-split-{dark,light,large-text}.png` |
| `CanvasLookSheetTool.meshLook` / `.toolIcons` | キャンバス描画スタイルのコラージュです。`meshLook` は編集可能なジオメトリ（`MeshLook`）が、暗、中、明、彩度の高い下絵の上で取る各種状態（構造、選択、ホバー、ブラシの影響範囲）です。`toolIcons` はすべてのキャンバスツールアイコンとプレビューサイドバーのスイッチで、濃淡 2 つのテーマです | `canvas-look/mesh-look.png`、`tool-icons-{dark,light}.png` |
| `CliPresetMigrationTool.compareRawPreset` | 同じ PSD の履歴バインドと明示的なプリセットスタックを対照します。`PSD2LIVE_SAMPLE` でサンプルを選び、`PSD2LIVE_CLI_PHYSICS=0` または `PSD2LIVE_CLI_MOTIONS=0` で対応する出力をオフにします。`PSD2LIVE_CLI_VERIFY=1` は実際の CLI 入り口から書き出して、完全な IR と読み戻し結果を照合します。さらに `PSD2LIVE_CLI_TARGET_LOADER=1` を設定すると、`export` サブコマンドの既定 PSD 読み込み経路を照合できます | `cli-preset-migration/report.txt`、`report-target-loader.txt` |
| `CliPresetMigrationTool.compareMeshOnly`、`compareWithoutDeformers` | メッシュのみと、デフォーマなしモードをそれぞれ照合します。同じサンプルと厳密な検証スイッチで、これらのオプションの独立した差を記録します | `cli-preset-migration/report-mesh-only.txt`、`report-no-deformers.txt` |
| `ModifierPerfTool.profile` | アプリケーション層を経て PSD を完全にインポートし、推奨プリセット、シミュレーションベイク、最終プレビューパッケージ、初期履歴を含みます。同じ JVM での初回と再インポートをそれぞれ記録します（毎回新しいプロジェクト）。完全な所要時間、同期ベイクの所要時間、ベイクを差し引いた所要時間、目の調整と再ベイクの回数を記録します。`PSD2LIVE_SAMPLE` は入力を選び、`PSD2LIVE_IMPORT_MODIFIER_VERSIONS=種類@バージョン` はプリセット評価、ベイク、履歴初期化の前に同梱バインドのバージョンをロックし、`PSD2LIVE_VERIFY=1` は調整後に閉じた段階キャッシュの完全な IR 再構築結果を照合し、`PSD2LIVE_VERIFY_INDEPENDENT=1` はインポート、調整、コールド再生が古いテンプレートを要求したり古いバインドを構築したりしていないかを検査し、`PSD2LIVE_BASELINE_MODIFIER=種類@バージョン` は調整後のスタックと、指定した内蔵バージョンをロックした結果を完全な IR で対照し、複数バージョンはカンマ区切りで組み合わせ効果を比べ、`PSD2LIVE_JFR=1` はサンプリングを記録します。ウィンドウ描画とネイティブプレビューの読み込みは含みません | `modifier-perf/report.txt`、`profile.jfr` |
| `CommitPerfTool.profile` / `.desktop` | 1 回のオーサード確定の所要時間です。`profile` はアプリケーション層のコマンド境界を通り、段階に分けます（改訂番号、設定のデコード、再構築、ジオメトリ検査）。`desktop` はデスクトップのビューモデルとアダプタを通り、メッシュ頂点の編集とブラシストロークを連続して確定し、確定所要時間と UI スレッドの最長停止を報告します。`JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` と組み合わせてサンプルできます | `commit-perf/report.txt`、`desktop.txt` |
| `CommitPerfTool.baseline` | 確定経路の段階ごとのベースラインです。自動スケルトン、2 つのスイング、ベイク済みシミュレーション 1 つを持つプロジェクト上で、完全な再構築（プロセス内に保存済みのオーサード Rig を読まず、基礎 Rig を生成してログを再生）の各段階（解析、テクスチャアトラスのパッキングと PNG エンコード、基礎 Rig、スケルトンキャッシュのヒット／ミス、ログ再生、スイング／シミュレーションの書き戻し、上書き、IR、moc3 パッキング、`validateBundle`、改訂番号のハッシュ、ランタイムファイルの書き出し）をそれぞれ計測し、さらにジオメトリ確定、小さなレイヤーの描画、画像の差し替え（同形／メッシュ再構築）、無関係なトポロジ編集のあとのスケルトンキャッシュヒット、ログを 50/200 件まで追加したときの確定所要時間と増加を計測します。確定のたびに履歴ストレージへ書き込みます。ネイティブプレビューの再読み込みは測りません（GL コンテキストが必要） | `commit-perf/baseline.json`、`baseline.md` |
| `CommitPerfTool.rigStages` | `baseline` の準備と生成系の確定だけを行います（レイヤー分類、レイヤーメッシュ）。各確定の中の Rig 構築の各段階を記録し、そのあと結果を完全に再構築します。`PSD2LIVE_RIG_MODES` が `staged` または `unstaged` のときは段階キャッシュの 1 モードだけを測り、既定では両方を測ります | `commit-perf/rig-stages.json`、`rig-stages.md` |
| `SkeletonCommitTool.profile` / `.equivalence` | スケルトンの確定です。`profile` は `PSD2LIVE_SCALE` 倍（既定 2）に拡大したサンプル上で、先に `PSD2LIVE_GEOMETRY_EDITS` 回（既定 24）のジオメトリ確定を行い、そのあとコマンド境界を経てボーンを新規作成、バインドし、関節を移動し（スキンの最大メッシュの四肢関節を含む）、確定所要時間と Rig 構築の各段階を報告します。`equivalence` は、スケルトン、ジオメトリ、頂点グループの確定、および取り消し／やり直し／ブランチ切り替えのあと、ランタイムモデルと、キャッシュを空にし保存済みオーサード Rig を使わないコールド再構築および再生を、段階的に比べます | `skeleton-commit/report.txt`、`equivalence.txt` |
| `SkeletonBakeDiffTool` | スケルトンベイクの前後の比較です。拡大サンプル（`PSD2LIVE_SCALE`、既定 2）上で、各スキンメッシュが各キーフォーム格子点とブレンド形キーにあるキャンバス座標を記録し、各四肢の極限ポーズをレンダリングします。`PSD2LIVE_LABEL` は今回の結果に名前を付け、`PSD2LIVE_REFERENCE` を設定するとその結果と比べ、各メッシュの最大頂点差（ピクセル）とレンダリング差を報告します | `skeleton-bake/<label>.bin`、`<label>-limbs.png`、`<label>-vs-<reference>.png` |
| `OpenPerfTool.profile` | アプリの経路でプロジェクトを開く（`ProjectRepository.open` のあと、新しい `WorkspacePreviewBuilder` が頭部を構築する）所要時間です。自動スケルトン、2 つのスイング、ベイク済みシミュレーション 1 つを持つプロジェクト上で、3 種類のアーカイブを保存します。アプリの現在の書き方（各改訂のオーサード Rig と頭部キャッシュ）、頭部キャッシュのみ（保存済み Rig がないときの生成と再生）、両方なしです。それぞれ空のオーサード Rig とスケルトンキャッシュの下で 3 回開き、展開／種まきと頭部構築を計測し、3 者が構築したモデルが同じであることを照合します | `open-perf/report.json`、`report.md` |
| `SavePerfTool.profile` | 現実的な規模の生成プロジェクトを保存する所要時間です（`PSD2LIVE_SAVE_LAYERS`、`PSD2LIVE_SAVE_SIZE`、`PSD2LIVE_SAVE_REVISIONS`）。同じキャプチャを 3 回保存し、開き直して 2 回保存し、さらに 1 回開きます | `save-perf/report.txt` |
| `ExportTargetsScenario`、`GenerateAndRigScenario`、`PaintAndReplaceScenario`、`SplitThenRegenerateScenario`、`Cmo3ImportScenario`、`MotionScenario` | 合成キャラクター上の長いエンドツーエンドのシナリオで、テストスイートのシナリオ（`scenario/`）と同じく、コールド再生、取り消し、品質を段階的に検査します。各書き出し対象への書き出し、インポートから書き出しまでの最初のセッション、スケルトン下での描画と画像差し替え、分割してから再生成、cmo3 をインポートして編集したあと cmo3 へ書き戻す、組み込みモーションの編集と書き出しです。パイプライン全体は長くかかりすぎるのでテストスイートには入りません。生成、書き出し、保存を変えたあとに実行します | テスト結果のみ |
| `RigSourcePerfTool.profile` | 大きな画像（サンプルを `PSD2LIVE_SCALE` 倍、既定 3）に生成入力があるときの再構築で、ピクセル単位の段階です。3 レイヤーごとに数ピクセル小さく切り（固定矩形まで埋める必要があり）、5 レイヤーごとに 2 倍密度です。コールド構築、ホット再構築、描画後の再構築の段階別所要時間、およびコールド合成のアトラスページとプレビュー PNG のエンコードです | `rig-source-perf/report.txt` |
| `MeshTraceTool.compare` / `.wrap` | `compare` は合成レイヤー（1024² のテクスチャ上のまつげ付きの目、髪の細い束、キャンバス解像度のレイヤー、6000 と 2048 ドキュメント内のレイヤー）を、キャンバス輪郭取りとテクスチャ輪郭取りでそれぞれメッシュ生成し、並べてレンダリングし（灰はテクスチャアルファ、青はメッシュ、赤はメッシュ外の不透明テクスチャピクセル）、頂点数、所要時間、`detail`、メッシュ外ピクセル、メッシュ面積を報告します。`wrap` はテクスチャ輪郭取りで、包み込み 0–32 の下でまつげ、髪の細い束、手のメッシュを生成し、頂点数、輪郭環の数、所要時間、メッシュ外ピクセルを報告します | `mesh-trace/<ケース>.png`、`report.txt`。`mesh-wrap/<ケース>.png`、`report.txt` |
| `StructureMeshTool` | テクスチャ構造に従って点を再配置します。サンプル PSD（`PSD2LIVE_STRUCTURE_PSD`、既定 tml）の衣服と髪のレイヤー（または `PSD2LIVE_STRUCTURE_LAYERS` が列挙するレイヤー）上で `layer_mesh_structure` の計画を実行し、古いメッシュと新しいメッシュ、および認識線（しわはオレンジ、影は青、模様は紫、採用しなかった線は薄く）を並べて描き、頂点数、採用した線、しわ帯の数、所要時間を報告します。`PSD2LIVE_STRUCTURE_SETTINGS` は設定 JSON を渡します | `structure-mesh/<メッシュ>.png`、`report.txt` |
| `ReadmeShotsTool` | 本物のエディタウィンドウを開き、スタートページで「完全」プリセットを選んでから `PSD2LIVE_SAMPLE` をインポートし、「モデル全体の独立メッシュを確認…」で確認して分割を確定し、シミュレーションのベイク完了を待ち、自動スケルトンを確定し、各ワークスペースとキャンバスの各モードのウィンドウフレームをキャプチャし（画面キャプチャはしません）、プロジェクトを `project.psd2live` として保存します。`PSD2LIVE_SHOT_REUSE=1` は保存済みプロジェクトを開き直し、`PSD2LIVE_SHOT_W` / `_H` はウィンドウサイズを設定します。トップページのスクリーンショットに使います | `readme-shots/<サンプル>/*.png`、`project.psd2live` |
| `ReadmeShowcaseTool` | トップページのテスト図です。`meshStress`、`meshParams`、`meshWrap` は合成レイヤーのメッシュ（穴と細い橋、縁の層数 × 塗りつぶしアルゴリズム、包絡の幅）です。`rigHead`、`rigFace`、`rigBody` は 2 つのサンプルの頭、顔のパーツ、スケルトンのプリセットポーズです。`rigKnee` は `PSD2LIVE_KNEE_SAMPLE`（プロジェクトまたは PSD）の膝の曲がりです。`bodyOrbit` は `PSD2LIVE_ORBIT_PROJECTS` の各プロジェクトへ、体の X / Y の上下、左右、楕円運動のモーションを一時的に 1 区間足し、物理演算オンとオフでそれぞれ PNG フレームを書き出します（`PSD2LIVE_ORBIT` は振幅、`PSD2LIVE_ORBIT_PERIOD` は 1 周の秒数） | `readme-plates/*.png`、`readme-orbit/<プロジェクト>-{on,off}/` |

`modifier legacy template` 行は、互換テンプレートの構築回数を集計します。`modifier template consumer` 行は、同じ一連の構築の実際の呼び出し元を記録し、先行モディファイアテンプレートへのリクエストを含みます。総所要時間を集計するときは、そのうち 1 組だけを計算します。

| 環境変数 | 働き |
| --- | --- |
| `PSD2LIVE_SAMPLE` | サンプル名（`tml`、`ds`）または PSD パス。既定は `tml`。`CommitPerfTool.desktop` は `.psd2live` プロジェクトも受け付けます |
| `PSD2LIVE_CMO3` | `ModelProfileTool.cmo3` の入力。`.cmo3` ファイル、またはそれが置かれたディレクトリ |
| `PSD2LIVE_PROBES` | 運動プロファイル探査のパラメータ。`id=値,...`。既定は体の X、Y、Z の端点 |
| `PSD2LIVE_SHEET_PARAM` | シルエットをこのパラメータに沿って展開するように変え、体の X × 体の Y の代わりにします |
| `PSD2LIVE_BONES` | `MotionSheetTool.body` の中で自動スケルトンのボーン位置を修正します。`id=頭x,頭y,尾x,尾y;...`（キャンバスピクセル） |
| `PSD2LIVE_BIND_LEGS` | `1` にすると、脚と靴のメッシュを最初の太もものボーンへバインドします |
| `PSD2LIVE_ZOOM` | 脚のクローズアップの範囲。`左,上,右,下`。キャンバス比率 |
| `PSD2LIVE_VERBOSE` | `1` にすると、`motions` が各カーブを追加で出力します |
| `PSD2LIVE_BAKE_CONFIGS` | `SimBakeSuite` の設定。`モード数:キー数,...`。既定は `preset`（各パーツ自身の設定） |
| `PSD2LIVE_SIM_PARTS` | `SimBakeSuite` / `SimRootDofExperiment` のパーツ。カンマ区切り。既定はすべて |
| `PSD2LIVE_BAKE_METHODS` | `SimBakeSuite` のベイク方法。`legacy,dof` |
| `PSD2LIVE_SIM_INPUTS` | `SimBakeSuite` はこれらの入力だけを残します |
| `PSD2LIVE_SIM_CSV` | `SimBakeSuite` は、これらのモーションの先端軌跡、モード値、入力を CSV として書きます |
| `PSD2LIVE_RUNTIME_PROJECT` | `RuntimeConformanceTool.generate` が追加で書き出すプロジェクト（`.psd2live` または PSD）。ケース名は `project` |
| `PSD2LIVE_GEOMETRY_EDITS` | `SkeletonCommitTool.profile` がスケルトン確定の前に行うジオメトリ確定の回数。既定は 24 |
| `PSD2LIVE_RIG_MODES` | `CommitPerfTool.baseline` / `.rigStages` の生成系確定が測るのは `staged` または `unstaged` だけ。既定は `both` |
| `PSD2LIVE_TEST_CUBISM_CORE` | 公式 Cubism Core 動的ライブラリのパス。`CubismCoreCheckTool` と `:umamo` の `MocDefaultColorsTest` に使います。未設定のときは両者とも飛ばします |

### PSD のインポートからモディファイアの全生成まで

デスクトップのスタートページは `WorkspaceHost.importPsd` で選択ファイルをキャプチャします。`WorkspaceSourceImporter` は PSD をデコードし、レイヤーと素材の関連を作り、空のモディファイアスタックの編集可能なメッシュプレビューを生成し、初期アトラスを固定してプロジェクトをインストールします。レイヤー分割と命名が終わると、`ImportPresetContinuation` は同じプロジェクトで `modifier_initialize` を一度だけ確定します。空のスタックを選ぶと、流れはインポートで止まります。MCP の `asset_import_psd` も、1 回のインポートでプリセットを指定でき、この経路はプロジェクトをインストールする前に下表の各段階を完了します。

| 段階 | 実行位置 | 下流が使う結果 |
| --- | --- | --- |
| 基礎入力の準備 | `WorkspacePresetInitialization` → `RigModifierPipeline` → `buildOrdinaryPreview` | ソース画像を解析し、アトラスと通常メッシュを作り、既存の編集を再生し、モディファイアの入力 IR をコンパイルします。 |
| 計画とバインド | `WorkspaceModifierPlan` → `RigModifierEvaluator` | 実際のメッシュから対象を選び、依存に従ってインスタンスを作ります。各インスタンスは前のインスタンスの出力を読みます。バインド結果が、使えるパラメータと、すでに駆動されている物理出力を決めます。 |
| 物理演算、モーション、シミュレーション | `WorkspacePresetInitialization` → `WorkspaceModifierSimulation.prepare` | 生成済みのパラメータに基づいて物理演算とモーションを追加します。各シミュレーションは自身より前のスタックを読み、対象、衝突メッシュ、Glue でつながったメッシュ、祖先デフォーマを追跡し、ベイク結果をドキュメントへ書き込みます。 |
| 最終評価と確定 | 直接インポートは `WorkspaceSourceImporter` がインストールします。遅延初期化は `WorkspacePreviewBuilder` が候補を再構築し、`WorkspaceRuntime` を経て確定します | 候補は入力が一致する評価接頭辞を再利用し、最終プレビューパッケージを生成します。遅延初期化のモディファイアスタック全体は、履歴ノードを 1 つだけ追加します。 |

CLI が生の PSD から直接書き出すとき、`buildSourceExport` が生成分岐を選びます。デフォーマを有効にした完全な生成は `CliPresetBindings.build` を通り、上述のプリセット計画と順序付き評価を共有し、そのあと CLI 設定に従って書き出しモデルを整理します。メッシュのみと、デフォーマをオフにする経路には、それぞれ専用の分岐があります。

`ModifierPerfTool.profile` で推奨プリセットの直接インポートを測り、`ModifierPerfTool.deferredImport` で空スタックのインポート後に完全なプリセットを実行するコマンド経路を測ります。`PSD2LIVE_IMPORT_ROUNDS` は同じ JVM 内で新しいプロジェクトを作る回数を制御し、既定は 2 です。レポートは周回ごとに所要時間、GC の時間、シミュレーションの較正、トレーニング、動的フィットなどの各段階の所要時間を列挙します。下位段階の所要時間は、所属する上位段階にすでに計上されています。遅延初期化のベースラインは、プレビューパッケージファイルとシミュレーションベイクデータの SHA-256 も `build/tools/modifier-perf/deferred.txt` へ書きます。`PSD2LIVE_VERIFY=1` を設定すると、最後に生成したプロジェクトを保存して開き直し、完全な IR、モディファイアレポート、静止と頭を回して口を開けたポーズの画面を照合し、MOC3 を読み戻します。時間計測が覆うのはアプリケーション層のコマンドだけで、検証、ウィンドウ描画、人手によるレイヤー分割の時間は、いずれも計測範囲の外です。

## コード構造

```text
psd2live/
├─ src/                               # 产品层（根 Gradle 项目）
│  ├─ main/kotlin/io/github/psd2live/
│  │  ├─ core/                        # 生成流水线、质量检查与可重放编辑
│  │  ├─ document/                    # 源图、图层变换与纹理值类型
│  │  ├─ project/                     # WorkspaceDocument、WorkspaceStore、.psd2live 归档
│  │  ├─ application/                 # WorkspaceRuntime、宿主、命令注册与 schema
│  │  ├─ history/                     # 分支式撤销 / 重做
│  │  ├─ agent/                       # 本地 MCP 服务与工具定义
│  │  ├─ render/                      # 编辑画布与 p2lrt GPU 渲染
│  │  ├─ ui/                          # Compose Desktop 界面、ViewModel、主题与交互教程
│  │  ├─ i18n/                        # 多语言加载
│  │  └─ util/                        # 通用工具
│  ├─ main/resources/                 # i18n 文案、whatsnew、manual、皮肤预设等资源
│  └─ test/kotlin/io/github/psd2live/ # 回归测试与开发工具（tools/）
├─ umamo/                             # 通用引擎（GPL）：runtime、format、interop、render、edit
├─ umamo-ir-bridge/                   # GPL 转换桥（PuppetIr）
├─ format-model/                      # 中立绑定 IR（MIT）
├─ format-compile/                    # 导出编译框架与损失报告（MIT）
├─ format-eval/                       # 运行时求值绑定（MIT）
├─ algorithm-api/                     # 独立 JVM 算法契约与清单
├─ algorithm-worker/                  # 外部算法进程宿主机
├─ algorithms/                        # 内置算法模块（binding / motion / physics / simulation / swing）
├─ targets/                           # 导出目标（cubism / raster / psd / spine / runtime / web / gltf / unity / dragonbones）
├─ plugin-sdk/                        # 插件 SDK（Python / Rust）与打包脚本
├─ plugins/                           # 内置 / 示例原生插件（native-core）
├─ runtime/                           # Rust 运行时（p2lrt）、Web / Godot 绑定与一致性测试
├─ native/                            # 可选 Cubism Native 预览桥接（C++ / CMake）
├─ packaging/                         # Windows / macOS 安装包与启动器脚本
├─ tools/                             # mcp_proxy.py、示例拉取/校验与目标检查脚本
├─ examples/                          # 示例说明与多语言插件示例
├─ docs/                              # 中文主文档与图片资源
├─ licenses/                          # 第三方许可证文本
├─ build.gradle.kts                   # Gradle 构建与打包配置
├─ settings.gradle.kts                # 22 个 Gradle 子模块定义
└─ run-gui.bat                        # Windows 快捷启动脚本
```

ソースは複数の Gradle モジュールに分かれ、モジュール間の依存は Gradle がコンパイル時に制約します。Umamo から移植したエンジン `org.umamo.*` は `:umamo`（`umamo/src/main/kotlin/`）にあり、製品コードに依存しません。中立リグ IR の `:format-model`、書き出しフレームワークの `:format-compile`、ラスター書き出しの `:targets:raster`、ランタイムモデルの `:targets:runtime`、ランタイムバインドの `:format-eval` は MIT で、どの GPL モジュールにも依存しません。GPL モジュール `:umamo-ir-bridge` は `:umamo` と `:format-model` にだけ依存し、汎用変換器 `io.github.psd2live.bridge.PuppetIr` を持ちます。アルゴリズムと書き出し対象はこれを共有し、対象の降格、コンパイル、書き出しの実装は経由しません。`:targets:cubism` は Cubism 対象の降格、コンパイル、書き出しを残し、`:targets:psd` はエンジンを使います。詳しくは[中立リグ IR と書き出しターゲット](../spec/EXPORT_TARGETS.md)を参照してください。

製品層 `io.github.psd2live.*` はルートプロジェクト（`src/main/kotlin/`）にあります。アプリケーション層 `application/SourceExport.kt` はソースファイルの書き出しユースケースと CLI プリセットを選び、`PSD2LivePipeline.run` 拡張を提供します。`core/PSD2LivePipeline.kt` は生成と書き出しの計算を残します。ソース画像、レイヤー変換、テクスチャ値の型はルートプロジェクトの `document` パッケージに属し、独立した Gradle モジュールではありません。`project` の互換エイリアスは古いパッケージ名を残します。

製品パッケージの依存は `LayerDependencyTest` が検査します。`core/project/history/document/util` は `application/agent/ui` を逆に呼ばず、`document/util/history` は `core/project` に依存しません。既存の `core/project/render` の強連結成分は残っており、そのパッケージをまたぐシンボルの例外は `src/test/resources/architecture/product-layer-exceptions.txt` に列挙されています。循環を新たに足す、例外を広げる、パッケージをまたぐワイルドカードインポートで検査を回避することはできません。

エンジンのパッケージは次のとおりです。

| パッケージ | 責務 |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`、キーフォームの補間と評価 |
| `org.umamo.format` | PSD、CMO3、MOC3、および画像形式の読み書き |
| `org.umamo.interop` | `PuppetModel` と CMO3 / MOC3 の相互変換 |
| `org.umamo.render` | LWJGL / OpenGL プレビュー |
| `org.umamo.edit` | モデルに対する不変の編集プリミティブ |
| `io.github.psd2live.core` | 生成パイプライン（`PSD2LivePipeline`、`LayerClassifier`、`AdaptiveMeshGenerator`、`RigBuilder`、`MotionGenerator`、`PhysicsGenerator`）と各種の再生可能な編集 |
| `io.github.psd2live.project` | 中立ワークスペースドキュメント（`WorkspaceDocument`）、履歴ストレージ（`WorkspaceStore`）、`.psd2live` アーカイブ。`application`、Compose、MCP には依存しません |
| `io.github.psd2live.application` | 中立ランタイム（`WorkspaceRuntime`）、コマンド登録と schema、プレビューセッション、プロセス内のバックグラウンドタスク。画面と MCP が共有します |
| `io.github.psd2live.history` | 分岐式の取り消し / やり直し |
| `io.github.psd2live.agent` | ローカル MCP サービスと公開ツールの定義 |
| `io.github.psd2live.render` | 編集キャンバスと p2lrt プレビューの GPU レンダリング（`CanvasGpu`、`GlCanvasRenderer`、`P2lrtGlRenderer`、`SkiaGpu`） |
| `io.github.psd2live.ui` | Compose の画面。`state`（ViewModel、ショートカットレジストリ）、`views`（ワークスペースとパネル）、`components`（ダイアログとコントロール）、`tutorial`（対話チュートリアル）。キャンバス編集と描画は `ui` のルートパッケージにあります |
| `io.github.psd2live.i18n` | 画面の文言。リソースは `src/main/resources/i18n/` にあります |

生成と書き出しのロジックは `core` / `project` / `application` に置き、Compose の画面コードへ書かないでください。

スタート、ヘルプ、設定はアプリのページ（`ui/state/AppPages.kt` の `AppPage`）で、プロジェクトには入りません。各ページはエディタのドッキング枠（`DockWorkspaceView` の `DockSession` と `DockTree`）を再利用し、各ページは目次パネルと内容パネルです（スタートページには詳細パネルもあります）。パネル ID にはページ接頭辞が付き（`start.*`、`help.*`、`settings.*`）、`PageChrome.kt` の目次行と本文コンテナを共有し、既定の配置は `pageDockLayout` を参照し、配置と閉じたパネルはローカルの設定に保存されます（`AppSettings.pageArrangement`）。パネルはドラッグ、フローティング、再ドッキングされ、互いに共有する状態は ViewModel または `StartPageModel` に置き、単一パネルのコンポジションには置きません。機能更新の文章は `src/main/resources/whatsnew/<言語>.md` にあり、4 言語のバージョンと項目は一対一で対応している必要があります（`WhatsNewTest`）。バージョン番号はビルドが `psd2live/version.properties` へ書き、コードは `AppVersion` を読みます。

## 中核の制約：再構築と再生

`PuppetModel` は永続状態ではありません。プロジェクトが保存するのはソース画像、レイヤー分類と設定、およびシリアライズ可能な編集記録（`RigEditOverlay`）です。プロジェクトを開くたび、または変更が起きるたびに、次のようになります。

1. `RigBuilder` がソース画像から基礎 Rig を再生成します。
2. オーサード段階（`RigEditOverlay.replayAuthored`）：ログの最後の `rig_checkpoint` 記録が保存した Rig から始めます。ないときは基礎 Rig から始め、先に旧形式の静的フィールド（パラメータの削除 / 作成、Warp、構造、キーフォーム）を再生し、そのあと実際の順序で、そのあとの `authoringJournal` を再生します。
3. 締めくくり（`RigEditOverlay.finish`）：ジェネレーター依存グラフ（`core/DocumentGenerators.kt`）に従ってスイング、シミュレーションなどのジェネレーターを実行し、生成結果の上書き（`generated_override`）を三方マージし、生成パラメータを参照するパネル編集を再生します。

オーサード Rig が保存されている改訂（このプロセスが構築したもの、またはアーカイブ `rig/` に保存されたもの）は、それから直接構築し、基礎 Rig は生成せず、ログも再生しません。詳しくは[ドキュメント層](../spec/DOCUMENT_LAYER.md)を参照してください。

そのため、編集機能を追加するときは次のようにします。

- 変更は `RigEditOverlay` のシリアライズ可能なフィールドへ書かなければなりません（新しいコマンドは、編集ログの中の JSON コマンドを優先します）。ワークスペース状態のエンコードとデコードで保存と復元をします。`PuppetModel` だけを変える変更は、再構築後に失われます。
- 画面と MCP は、同じ一式の編集コマンドを呼ぶべきです。`org.umamo.edit` の下層メソッドは、自動では公開インターフェースと等しくありません。
- 受け入れの経路：ドメインデータ → 履歴の再生 → プロジェクトの保存と復元 → 対象 Cubism バージョンの処理 → 書き出しの読み戻し → 視覚検査です。

画面の文言を追加するときは、`Messages.properties`、`Messages_zh_CN.properties`、`Messages_ja.properties`、`Messages_ko.properties` の 4 ファイルへ同時に追加し、キー数を一致させます（`CatalogContractsTest` が検査します）。

## 書き出し結果の確認

プラグインの Git 配布の受け入れには、ローカルに Git と `git daemon` のインストールが必要です。`PSD2LIVE_PLUGIN_GIT_TEST=1` を設定して `test --tests '*ModifierPluginTest.gitTagsAndCommitsInstallPrebuiltPinnedPackages'` を実行します。一時ディレクトリにビルド済みのテストパッケージを作り、ローカルだけを待ち受け、タグ、コミット番号、既定ブランチ、バージョンの共存、本物の評価を検査します。外部リポジトリにはアクセスしません。通常の回帰はこの項目を飛ばします。

- `.model3.json` が参照するすべてのファイルは、モデルとともに渡す必要があります。
- ログパネルと診断 JSON の警告を確認します。
- 編集を続ける必要があるときは `.psd2live` プロジェクトを保存します。`.psd2live.json` はレポートにすぎません。

関連：[Cubism Native プレビュー](CUBISM_SDK_SETUP.md) · [CI とリリース](CUBISM_CI_RELEASE.md) · [テクスチャ高解像度化](TEXTURE_UPSCALE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
