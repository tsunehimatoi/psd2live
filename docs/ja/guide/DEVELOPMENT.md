# 開発と CLI

[Docs](../../README.md) · [User guide](USER_GUIDE.md)

ソース実行には JDK 21 が必要です。同梱 Gradle Wrapper を使ってください。Windows 配布版のランタイムと、開発用 JDK は別です。

```powershell
.\gradlew.bat run
.\gradlew.bat run --args="--help"
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Linux / macOS: `./gradlew`.

引数なしでは GUI、引数ありでは CLI が起動します。`--help` 以外では `--input` が必要です。CLI は PSD から生成し、デスクトップ工程を開いて編集する機能ではありません。

## CLI

| オプション | 初期値 | 内容 |
| --- | --- | --- |
| `--input <path>` | 必須 | 入力 PSD |
| `--output <path>` | PSD と同じ場所の psd2live-output | 出力先 |
| `--lang <zh\|en\|ja>` | システム言語 | ログ言語 |
| `--atlas <size>` | 4096 | アトラス寸法 |
| `--mesh-spacing <px>` | 64 | CLI メッシュ間隔 |
| `--head-strength <value>` | 1.0 | 頭の変形量 |
| `--body-strength <value>` | 1.0 | 体の変形量 |
| `--mesh-only` | 無効 | メッシュのみ |
| `--no-deformers` | 無効 | デフォーマ生成なし |
| `--no-motions` | 無効 | モーションなし |
| `--no-physics` | 無効 | 物理生成なし |
| `--no-cmo3` | 無効 | CMO3 なし |
| `--no-moc3` | 無効 | MOC3 なし |
| `--no-json` | 無効 | 診断 JSON なし |
| `--upscale <1\|2\|4>` | 1 | 1 で無効 |
| `--upscale-python <path>` | python | Python 実行ファイル |
| `--nunif-dir <path>` | 空 | ソースディレクトリ |
| `--upscale-model <path>` | 空 | 重みディレクトリ |
| `--upscale-tile <64..512>` | 256 | 入力タイル |
| `--upscale-noise <-1..3>` | 1 | -1 でノイズ除去なし |
| `--no-upscale-neural-alpha` | 無効 | ニューラル Alpha 無効 |

下表は Main.kt の初期値です。GUI の PipelineConfig はメッシュ間隔 40、CLI は 64 です。CMO3・MOC3・診断 JSON の少なくとも一つを有効にしてください。ニューラル Alpha は初期状態で有効で、`--no-upscale-neural-alpha` で無効化します。`--upscale-neural-alpha` は互換用です。

## ビルドと検証

```powershell
.\gradlew.bat test
.\gradlew.bat distZip
.\gradlew.bat createDistributable
.\gradlew.bat packageDistributionForCurrentOS
```

変更に対応するテストを実行してください。distZip は distributions.main に設定された文書等を収集するもので、完全なポータブルアプリではありません。createDistributable はランタイム付きのアプリディレクトリを、packageDistributionForCurrentOS は現在の OS 向けに設定されたインストーラーを作ります。任意のネイティブブリッジは別途ビルドし、専有 SDK リソースをコミット・同梱しないでください。

## 出力確認

model3.json が参照する全ファイルを一緒に渡し、警告と診断を確認してください。.psd2live は GUI の工程保存、CLI の .psd2live.json はレポートです。

[Main.kt](../../../src/main/kotlin/io/github/psd2live/Main.kt) · [PipelineConfig](../../../src/main/kotlin/io/github/psd2live/core/Model.kt) · [Gradle](../../../build.gradle.kts) · [SDK](CUBISM_SDK_SETUP.md) · [Texture upscale / 高清化](../../zh/guide/TEXTURE_UPSCALE.md)
