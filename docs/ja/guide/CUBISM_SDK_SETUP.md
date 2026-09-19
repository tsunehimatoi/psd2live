# Live2D Cubism SDK 設定・利用ガイド

[English](../../en/guide/CUBISM_SDK_SETUP.md) | [中文](../../zh/guide/CUBISM_SDK_SETUP.md)

本ガイドは、PSD2Live で公式 Live2D® Cubism® Native SDK ランタイム環境を設定し、公式 Cubism ランタイムと**100% 忠実な描画および物理挙動の一致性検証（Consistency & Ground Truth）**を有効化する手順を説明します。

---

## 目次

- [核心的価値：なぜ公式SDKが必要なのか（単なる高速化ではなく「厳格な一致性」）](#核心的価値なぜ公式sdkが必要なのか単なる高速化ではなく厳格な一致性)
- [法的通知および非再配布ポリシー](#法的通知および非再配布ポリシー)
- [SDK不要の基本動作 (Out-of-the-Box)](#sdk不要の基本動作-out-of-the-box)
- [推奨フロー：ワンショット・ビルド＆配置](#推奨フローワンショットビルド配置)
- [ランタイム構成と依存関係プロファイル](#ランタイム構成と依存関係プロファイル)
- [代替の配置場所](#代替の配置場所)
- [動作確認とステータス表示](#動作確認とステータス表示)
- [よくある質問 (FAQ)](#よくある質問-faq)

---

## 核心的価値：なぜ公式SDKが必要なのか（単なる高速化ではなく「厳格な一致性」）

> [!NOTE]
> **公式 SDK を導入する真の目的は「描画速度の高速化」ではなく、「公式ランタイム環境との厳格な一致性（Consistency）」の担保にあります。**

Live2D のアセット制作パイプラインにおいて、描画と物理シミュレーションは独自の専有ロジックに支配されています：
1. **ピクセル単位の描画・マスク一致性 (Rendering Parity)**：
   - 公式 Framework シェーダーは Premultiplied Alpha、乗算/加算/スクリーン等のブレンド、専用 FBO によるクリッピングマスク／反転マスクのサンプリングを厳密に定義します。
   - 純 CPU ソフトウェア描画では補間や色演算にわずかな差が出得ます。公式 Native SDK を使うと、PSD2Live の画質・マスク境界・色調が **Cubism Viewer** および実機ゲームとピクセル単位で一致し、マスクのにじみや黒縁を防げます。
2. **物理・モーション一致性 (Physics & Motion Parity)**：
   - 髪の揺れ、呼吸、まばたきのゼリー目などは公式 `Live2D_Update` 内の物理で駆動されます。
   - 公式ランタイムでプレビューすれば、書き出した `physics3.json` の減衰・重力・振幅が本番と一致します。
3. **権威ある Ground Truth**：
   - 公式 SDK は `.moc3` / `.model3.json` 等が公式仕様に適合しているかを検証する基準です。
   - 内蔵 CPU ラスタライザは環境未整備時の高速プレビュー用、公式 SDK は納品前の最終一致性チェック用です。

---

## 法的通知および非再配布ポリシー

> [!IMPORTANT]
> **本プロジェクトはオープンソースライセンスおよび Live2D 社の専有ソフトウェアライセンスを厳格に遵守しています：**
> 1. **非再配布ポリシー**：株式会社 Live2D（Live2D Inc.）の「Live2D Proprietary Software License」に基づき、Live2D Cubism Core 原生ライブラリおよび公式バイナリアセットは専有財産であり、**第三者による再配布はいかなる形態でも固く禁止されています**。
> 2. **リポジトリのコンプライアンス**：PSD2Live のソースリポジトリには、Live2D 公式 SDK バイナリ、動的リンクライブラリ（`.dll`）、著作権で保護されたシェーダーソースコードは**一切含まれず、同梱・配布も行いません**。本リポジトリが提供するのは [`native/live2d_renderer/`](../../../native/live2d_renderer/) のオープンソース・ラッパーのみで、利用者自身が入手した SDK とローカルでリンクしてビルドします。
> 3. **商標権**：`Live2D`、`Cubism`、`.cmo3`、`.moc3` 等は株式会社 Live2D の登録商標または商標です。本書および本リポジトリでは相互運用性の技術的説明のためにのみ引用しています。

---

## SDK不要の基本動作 (Out-of-the-Box)

**PSD2Live は公式 SDK がなくても完全にスタンドアロンで動作します：**

- **全パイプラインが自己完結**：PSD レイヤーの意味解析、連結成分分離、適応型ドロネー三角形分割、9軸顔面デフォーマ構築、物理演算シミュレーション、ループモーション生成、そして `.cmo3` および `.moc3` ファイル群のエクスポートは**完全に内蔵ロジックで実行可能であり、100% すぐに使えます**。
- **内蔵ソフトウェアラスタライザー**：公式 SDK が未設定の場合や、Windows 以外の OS（macOS / Linux）では、GUI プレビュー画面は内蔵の純 CPU ソフトウェアラスタライザーへ自動フォールバックし、変形確認やパラメータ調整を行えます。

---

## 推奨フロー：ワンショット・ビルド＆配置

目標：本リポジトリのラッパー＋手元の公式 SDK から、**従来検証済み DLL と同じ依存プロファイル**（静的 CRT `/MT`、VC++ 再頒布パッケージ不要）の `live2d_renderer.dll` をビルドし、シェーダーと共に配置する。

### 前提条件

| 項目 | 要件 |
| :--- | :--- |
| OS | Windows x86-64 |
| ツールチェーン | CMake 3.16+、Visual Studio 2022 C++（MSVC toolset **143**） |
| 公式 SDK | **Cubism 5 SDK for Native** を各自ダウンロード・展開（例：`CubismSdkForNative-5-r.5`） |
| Core ライブラリ | `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`（`/MT` と対；`*_MD.lib` は使わない） |

SDK 入手先：[Live2D Cubism SDK for Native](https://www.live2d.com/en/sdk/download/native/)（専有ライセンスへの同意が必要）。

### ワンコマンド

リポジトリルート（PowerShell）：

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

スクリプトの処理：

1. **`/MT` + `Live2DCubismCore_MT.lib`** で `live2d_renderer.dll` を構成・ビルド
2. SDK から公式 OpenGL シェーダー 22 個を `FrameworkShaders/` へ自動コピー
3. `-Deploy` で `src/main/resources/cubism/windows-x86_64/` へ配置（`.gitignore` 済み）

> [!IMPORTANT]
> 以前 `/MD` や `Core_MD.lib` で CMake を構成した場合は、**必ず** `-Clean`（または `native/live2d_renderer/build` 削除）が必要です。さもなくばキャッシュが `VCRUNTIME140.dll` 依存の誤った DLL を出し続けます。

詳細は [`native/live2d_renderer/README.md`](../../../native/live2d_renderer/README.md) を参照。

---

## ランタイム構成と依存関係プロファイル

```text
cubism/windows-x86_64/          # または src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/           # 公式シェーダー 22 個（ビルド時コピー、Git に入れない）
```

**正しい DLL の依存は次のみ：**

- `OPENGL32.dll`
- `KERNEL32.dll`
- `USER32.dll`
- `GDI32.dll`

`VCRUNTIME140.dll` / `MSVCP140.dll` / `api-ms-win-crt-*.dll` が**出ていてはいけません**。出る場合は `-Clean` で再ビルドしてください。

確認例：`dumpbin /DEPENDENTS live2d_renderer.dll`

---

## 代替の配置場所

アプリは次の優先順で探します（どれか一つで可。推奨フローは 1 に書き込みます）：

1. **プロジェクト資源（推奨）**：`src/main/resources/cubism/windows-x86_64/`（`-Deploy` 既定、gitignore）
2. **リポジトリルート**：`cubism/windows-x86_64/`（同様に gitignore）
3. **外部パス**：環境変数 `CUBISM_SDK_PATH` / `LIVE2D_SDK_PATH`、または JVM `-Dpsd2live.cubism.path=...`（`live2d_renderer.dll` と `FrameworkShaders/` を含むディレクトリ）

---

## 動作確認とステータス表示

```powershell
.\run-gui.bat
```

モデルまたは PSD を読み込み、プレビュー左下のステータスを確認：

- **`ネイティブ Cubism（リアルタイム物理）`** / **`ネイティブ Cubism`**：公式ランタイム読込成功
- **`ソフトウェアラスタライズ`**：未検出または非 Windows。CPU へフォールバック（基本機能は維持）
- シェーダー欠落などは左上に赤文字で診断表示

---

## よくある質問 (FAQ)

### Q1: SDK 未設定でも `.cmo3` / `.moc3` 書き出しはできますか？
できます。公式 SDK の価値は Ground Truth プレビューであり、書き出し自体には必須ではありません。

### Q2: `Missing Cubism SDK 5-r.5 runtime resource: live2d_renderer.dll`
1. `-Deploy` 済みか、3 つの配置場所のいずれかに置いたか
2. 64-bit Windows か
3. 外部パス利用時、環境変数が現在の端末/IDE に効いているか

### Q3: 実行時に `VCRUNTIME140.dll` / `MSVCP140.dll` を要求される
動的 CRT（`/MD`）でビルドされています。現行の `native/build_live2d_renderer.bat` を `-Clean` 付きで再実行し、システム OpenGL/GDI のみに依存する DLL にしてください。

### Q4: Git が `src/main/resources/cubism/` を無視／削除表示する理由は？
ライセンス遵守のための想定動作です。ローカルファイルはディスク上に残ります。

### Q5: ビルドスクリプトなしでシェーダーだけ手動コピーできますか？
可能です（`Framework/.../Shaders/Standard/` → 配置先 `FrameworkShaders/`）。推奨フローでは自動コピー済みです。
