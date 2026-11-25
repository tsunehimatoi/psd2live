<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/CUBISM_CI_RELEASE.md) · [English](../../en/guide/CUBISM_CI_RELEASE.md) · [한국어](../../ko/guide/CUBISM_CI_RELEASE.md) · **日本語**

# Cubism の CI とリリース作業

[ドキュメント目次](../README.md) · [Cubism Native プレビュー](CUBISM_SDK_SETUP.md)

このページでは、リポジトリにある 2 つの GitHub Actions ワークフローを説明します。公開テスト（SDK を含まない）と、任意の Cubism プレビューのパッケージングです。Live2D のライセンスに代わるものではなく、専有コンポーネントの再配布を許諾するものでもありません。

## 2 つのワークフロー

| ワークフロー | ファイル | 役割 |
| --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | `master`（および `main`）への `push` / `pull_request` のとき、Ubuntu と Windows の x64 および arm64 で `./gradlew test` を実行します。Cubism SDK はダウンロードせず、`PSD2LIVE_INCLUDE_CUBISM` も設定しません。 |
| **Release Cubism** | `.github/workflows/release-cubism.yml` | 手動の `workflow_dispatch`、または `v*` タグの push のとき、SDK を非公開で取得し、ネイティブブリッジをコンパイルし、Cubism を含む Windows / Linux のインストーラーを作ります（Linux は x64 と arm64）。あわせて Cubism を含まない Windows ARM64 パッケージを作り、GitHub Release を作成できます。 |

macOS のパッケージングは見送りです。このワークフローではビルドしません。

## 公開リポジトリと SDK の漏えい（必読）

**公開リポジトリでは、GitHub Release の添付ファイルは既定ですべての人に見えます。** `CubismSdkForNative-*.zip` を、この公開アプリケーションリポジトリの Release に添付しないでください。

推奨するやり方：

1. **非公開の兄弟リポジトリ**に SDK の zip を置き、prerelease / タグ（例：`internal-sdk/cubism-5-r.5`）を打ちます。添付ファイル名の既定は `CubismSdkForNative-5-r.5.zip` です。
2. このリポジトリの変数 `CUBISM_SDK_SOURCE_REPO` を、その非公開リポジトリ（`owner/name`）に向けます。
3. シークレット `SDK_FETCH_TOKEN` を設定します。その非公開リポジトリの Release を読める PAT（または fine-grained token）です。未設定のときは `GITHUB_TOKEN` に戻りますが、通常は他の非公開リポジトリを**読めません**。

別案：zip を別の非公開ストレージに置き、ワークフローの取得処理を別途変更します。現在の実装は `gh release download` を基準にしています。

## 変数とシークレット

| 名前 | 種類 | 既定 | 説明 |
| --- | --- | --- | --- |
| `CUBISM_SDK_SOURCE_REPO` | Repository variable | 現在のリポジトリ `github.repository` | SDK の Release があるリポジトリ。**公開アプリケーションリポジトリでは、必ず非公開リポジトリに変更します。** |
| `CUBISM_SDK_RELEASE_TAG` | Repository variable | `internal-sdk/cubism-5-r.5` | 非公開 SDK の Release / タグ名 |
| `CUBISM_SDK_ASSET_NAME` | Repository variable | `CubismSdkForNative-5-r.5.zip` | 添付ファイル名 |
| `SDK_FETCH_TOKEN` | Secret | （空なら `GITHUB_TOKEN`） | 非公開 SDK リポジトリを取得するときに使います |

## Environment の保護

ジョブ `build-windows`、`build-linux`、`release` は、GitHub Environment 名 **`release-cubism`** を使います。リポジトリの Settings → Environments でこの環境を作成し、必須のレビュアー / デプロイブランチ制限を付けてください。任意の共同作業者が、SDK を含むパッケージを直接作れないようにします。

## バージョンを公開する

前提：非公開の SDK リポジトリに zip がアップロード済みで、このリポジトリに上の表の変数、シークレット、`release-cubism` 環境が設定済みであること。以下では `<version>` を対象のバージョン番号とします。例は `3.8.0` です。

1. **先にバージョン番号を上げます。** ワークフローはリポジトリの内容を変更しません。`build.gradle.kts` の `version` を変更します。`packageVersion` と、リソース内の `psd2live/version.properties` は、どちらもこれから生成されます。コマンドラインヘルプのバージョン（4 つの `Messages*.properties` の `cli.usage`）と、4 つの `src/main/resources/whatsnew/<言語>.md` の先頭バージョンも変更します。`VersionConsistencyTest` が CI で、これらとビルドバージョンの一致を確認します。`docs/zh/CHANGELOG.md`、`docs/en/CHANGELOG.md`、`docs/ja/CHANGELOG.md`、`docs/ko/CHANGELOG.md` にこの版の項目を追加し、この版の説明を `.github/release-notes.md` に書きます（中国語を先にし、英語は折りたたみの `<details><summary>English</summary>` に置きます。ダウンロード欄は書かず、公開時に `.github/scripts/release_notes.py` が実際の添付ファイルから分類したダウンロードバッジを生成します）。コミットして push します。
2. Actions → **Release Cubism** → **Run workflow** を開き、`version` に `<version>` を入力します（`v` は付けません）。
3. 必要に応じて `create_github_release` をオンにします（既定はオン。オフのときはビルド成果物だけを生成します）。プレビュー版は `prerelease` をオンにし、プレリリースとして公開します。最新版としては印を付けません。
4. `release-cubism` 環境に必須レビュアーが設定されていれば、ここで承認します。未設定ならビルドはすぐに始まります。Windows と Linux のビルド完了を待ちます。
5. 公開をオンにしていると、ワークフローはタグ `v<version>` の GitHub Release を作成または更新します（既定は正式版。`prerelease` をオンにするとプレリリースです）。添付ファイルは次のとおりです。
   名前は `PSD2Live-<version>-<システム>-<アーキテクチャ>[-<種類>][-ffmpeg].<拡張子>` に統一します。システムは `windows` / `linux`、アーキテクチャは `x64` / `arm64`、Windows の種類は `setup`（インストーラー）/ `portable`（インストール不要）です。
   - `PSD2Live-<version>-windows-x64-setup.exe`、`PSD2Live-<version>-windows-x64-portable.zip`
   - 上の 2 つには、ffmpeg を内蔵した版もあります。`PSD2Live-<version>-windows-x64-setup-ffmpeg.exe`、`PSD2Live-<version>-windows-x64-portable-ffmpeg.zip`
   - `PSD2Live-<version>-windows-arm64-setup.exe`、`PSD2Live-<version>-windows-arm64-portable.zip`：Windows ARM64。Cubism は含みません（Live2D はこのプラットフォームの Cubism Core を提供していません）。ffmpeg 版はありません
   - `PSD2Live-<version>-linux-x64.deb`、`PSD2Live-<version>-linux-arm64.deb`（arm64 は SDK 内の実験的な Cubism Core を使います）。同じ内容の `PSD2Live-<version>-linux-x64.tar.gz`、`PSD2Live-<version>-linux-arm64.tar.gz`（app image。Deb がないディストリビューション向け）

   3.4.0 以前のバージョンは、旧来の名前を使います（例：`PSD2Live-<version>.exe`、`-windows-x86_64-portable.zip`、`-linux-amd64.deb`）。

タグ `v<version>` を直接 push して、同じワークフローを起動することもできます。2 つの入口はどちらか一方にして、ビルドマトリクス全体を重複して実行しないようにしてください。

## 成果物とプラットフォームの制限

- Linux のプレビューには X11/GLX が必要です（XWayland / `xvfb-run` を含みます）。純粋な Wayland と musl/Alpine には対応しません。詳細は [CUBISM_SDK_SETUP](CUBISM_SDK_SETUP.md) を参照してください。
- ARM64 パッケージは、まだ実機では検証していません。添付ファイルに arm64 パッケージがあるときは、`release_notes.py` がリリースノートへ、実機未検証であることと既知の制限（Linux arm64 の Cubism Core は実験版であること、OpenGL 3.1 だけに対応する GPU ではキャンバスを描けないことがあること）を記します。実機で確認したあとは、この説明を削除します。
- v1 では、壊れやすい GUI スモークは行いません。Linux 側は、`.deb` が存在し、空でないことを基準にします。
- Windows パッケージは 2 回作ります。2 回目は `-Ppsd2live.ffmpegDir=<ディレクトリ>` を付け、固定バージョンの Gyan.dev ffmpeg essentials ビルド（GPLv3。ダウンロード後に SHA-256 を検証し、動画とアニメーション画像の書き出しで使うエンコーダーも確認します）を、その `LICENSE.txt` と `README.txt` とともに、アプリの `resources/ffmpeg/` へ入れます。ffmpeg を上げるときは、ワークフローの `FFMPEG_URL` と `FFMPEG_SHA256` も同時に変更します。Linux パッケージに ffmpeg は内蔵しません。
- Windows のインストーラーは、`packageExe` が Inno Setup 6 でアプリイメージからビルドします。スクリプトは `packaging/windows/psd2live.iss` です。`ISCC.exe` は、`-Ppsd2live.iscc`、環境変数 `ISCC`、Inno Setup 6 の既定インストールディレクトリの順に探します。ワークフローは Chocolatey で Inno Setup をインストールします。ファイルはその場でコピーし、MSI のように古いファイルを先に `Config.Msi` へ移しません。既定は全ユーザー向けのインストールです（管理者権限が必要）。起動時のダイアログ、または `/CURRENTUSER` で、現在のユーザーだけにインストールすることもできます。アップグレードのときは、インストール済みのディレクトリへ戻し、当時のインストール方法を引き継ぎます。先に前の版の `app` と `runtime` ディレクトリを削除してからコピーし、インストールディレクトリ内のユーザー自身のファイルは残します。ffmpeg ありとなしのパッケージは同じ AppId を共有し、互いに置き換わります。インストール前に、インストールディレクトリから PSD2Live が実行中なら、閉じてから再試行するよう案内します（サイレントインストールは、その場で中止します）。3.1.x 以前は jpackage が生成した MSI です。インストーラーはアップグレードコードでそれらを見つけ、既定ではそのディレクトリへインストールし、ファイルをコピーする前に、それらが記録したインストールディレクトリを先に片付け（3.0.0 以前は、アンインストール時にそのディレクトリを空にします）、それらがインストールしたファイルを削除してから、`msiexec /x` でサイレントアンインストールします。このとき MSI は登録解除だけを行い、ファイルを `Config.Msi` へ移す必要はありません。アンインストールのログは Setup ログの横に書きます（`PSD2Live MSI removal.log`）。現在のユーザーだけにインストールし、旧版が全ユーザー向けだった場合は、管理者権限を要求します。アンインストール時には、ユーザーデータを同時に削除するかを尋ねます（既定はいいえ。実行中のアプリの `--clear-user-data` を、アンインストーラーを実行したアカウントで実行します。コマンドラインのアンインストールでは `/CLEARUSERDATA=1` を渡せます）。インストールとアンインストールのたびに、`%TEMP%` へログを書きます（`Setup Log *.txt`）。
- Cubism を含むパッケージは、ライセンスが許す範囲の用途に限られます。**専有バイナリを公開の経路へ再配布しないでください。**

## ライセンスの注意

リポジトリのブリッジコードは GPL です。Live2D Cubism Core / Framework / シェーダーは Live2D の専有ソフトウェアであり、ご自身で入手し、そのライセンスに従う必要があります。[THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md) を参照してください。

[ローカル SDK の設定](CUBISM_SDK_SETUP.md) · [native スクリプト](../../../native/README.md)
