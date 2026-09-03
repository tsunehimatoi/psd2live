# Live2D Cubism SDK 設定・利用ガイド

[English](../en/CUBISM_SDK_SETUP.md) | [中文](../zh/CUBISM_SDK_SETUP.md)

本ガイドは、PSD2Live で公式 Live2D® Cubism® Native SDK ランタイム環境を設定し、公式 Cubism ランタイムと**100% 忠実な描画および物理挙動の一致性検証（Consistency & Ground Truth）**を有効化する手順を説明します。

---

## 目次

- [核心的価値：なぜ公式SDKが必要なのか（単なる高速化ではなく「厳格な一致性」）](#核心的価値なぜ公式sdkが必要なのか単なる高速化ではなく厳格な一致性)
- [法的通知および非再配布ポリシー](#法的通知および非再配布ポリシー)
- [SDK不要の基本動作 (Out-of-the-Box)](#sdk不要の基本動作-out-of-the-box)
- [必要コンポーネント・ファイル一覧](#必要コンポーネントファイル一覧)
- [設定手順ガイド](#設定手順ガイド)
  - [ステップ 1：公式 SDK の入手](#ステップ-1公式-sdk-の入手)
  - [ステップ 2：OpenGL シェーダーファイルの抽出](#ステップ-2opengl-シェーダーファイルの抽出)
  - [ステップ 3：レンダラー DLL の取得またはビルド](#ステップ-3レンダラー-dll-の取得またはビルド)
  - [ステップ 4：所定ディレクトリへの配置 (3つの方法)](#ステップ-4所定ディレクトリへの配置-3つの方法)
- [動作確認とステータス表示](#動作確認とステータス表示)
- [よくある質問 (FAQ)](#よくある質問-faq)

---

## 核心的価値：なぜ公式SDKが必要なのか（単なる高速化ではなく「厳格な一致性」）

> [!NOTE]
> **公式 SDK を導入する真の目的は「描画速度の高速化」ではなく、「公式ランタイム環境との厳格な一致性（Consistency）」の担保にあります。**

Live2D のアセット制作パイプラインにおいて、描画と物理シミュレーションは独自の専有ロジックに支配されています：
1. **ピクセル単位の描画・マスク完全一致 (Rendering Parity)**：
   - 公式標準シェーダー（FrameworkShaders）は、乗算・加算・色合成・Premultiplied Alpha、および FBO によるクリッピングマスク／反転マスクのサンプリング式を厳格に定義しています。
   - 純粋な CPU ソフトウェアラスタライズでは補間精度やブレンド計算に微細な差異が生じる可能性があります。公式 Native SDK を通じて描画することで、PSD2Live の画面上の質感・マスク輪郭・色調が、公式 **Cubism Viewer** や実機ゲームクライアントと**ピクセル単位で完全に同一**であることを保証し、黒フチやマスク漏れを未然に防ぎます。
2. **物理演算・挙動シミュレーションの一致性 (Physics & Motion Parity)**：
   - 髪の毛の多段振り子、呼吸、まばたきのぷるぷる瞳物理は公式 `Live2D_Update` 内部の物理モジュールによって更新されます。
   - 公式ランタイムを用いることで、出力される `physics3.json` の減衰係数・重力応答・揺れ幅が実際のゲーム本番環境と 100% 一致することを保証します。
3. **成果物の権威ある真値検証 (Ground Truth)**：
   - 公式 SDK は、生成された `.moc3` や `.model3.json` が公式仕様に適合しているかを検証する「基準器（Ground Truth）」として機能します。
   - *(GPU ハードウェアアクセラレーションによる高フレームレートは副次的な恩恵であり、公式環境との完全な一致性こそが本質です。)*

---

## 法的通知および非再配布ポリシー

> [!IMPORTANT]
> **本プロジェクトはオープンソースライセンスおよび Live2D 社の専有ソフトウェアライセンスを厳格に遵守しています：**
> 1. **非再配布ポリシー**：株式会社 Live2D（Live2D Inc.）の「Live2D Proprietary Software License」に基づき、Live2D Cubism Core 原生ライブラリおよび公式バイナリアセットは専有財産であり、**第三者による再配布はいかなる形態でも固く禁止されています**。
> 2. **リポジトリのコンプライアンス**：PSD2Live のソースリポジトリには、Live2D 公式 SDK バイナリ、動的リンクライブラリ（`.dll`）、著作権で保護されたシェーダーソースコードは**一切含まれず、同梱・配布も行いません**。
> 3. **商標権**：`Live2D`、`Cubism`、`.cmo3`、`.moc3` 等は株式会社 Live2D の登録商標または商標です。本書および本リポジトリでは相互運用性の技術的説明のためにのみ引用しています。

---

## SDK不要の基本動作 (Out-of-the-Box)

**PSD2Live は公式 SDK がなくても完全にスタンドアロンで動作します：**

- **全パイプラインが自己完結**：PSD レイヤーの意味解析、連結成分分離、適応型ドロネー三角形分割、9軸顔面デフォーマ構築、物理演算シミュレーション、ループモーション生成、そして `.cmo3` および `.moc3` ファイル群のエクスポートは**完全に内蔵ロジックで実行可能であり、100% すぐに使えます**。
- **内蔵ソフトウェアラスタライザー**：公式 SDK が未設定の場合や、Windows 以外の OS（macOS / Linux）では、GUI プレビュー画面は内蔵の純 CPU ソフトウェアラスタライザーへ自動フォールバックし、変形確認やパラメータ調整を行えます。

---

## 必要コンポーネント・ファイル一覧

公式一致性プレビューに必要な構成（対象アーキテクチャ：`Windows x86-64`）：

```text
cubism/
└── windows-x86_64/
    ├── live2d_renderer.dll                     # Live2D Cubism Core 5-r.5 と静的リンクしたネイティブ DLL
    └── FrameworkShaders/                       # 公式 Framework OpenGL 標準シェーダー (計 22 ファイル)
        ├── FragShaderSrc.frag
        ├── FragShaderSrcAlphaBlend.frag
        ├── FragShaderSrcBlend.frag
        ├── FragShaderSrcColorBlend.frag
        ├── FragShaderSrcCopy.frag
        ├── FragShaderSrcMask.frag
        ├── FragShaderSrcMaskBlend.frag
        ├── FragShaderSrcMaskInverted.frag
        ├── FragShaderSrcMaskInvertedBlend.frag
        ├── FragShaderSrcMaskInvertedPremultipliedAlpha.frag
        ├── FragShaderSrcMaskInvertedPremultipliedAlphaBlend.frag
        ├── FragShaderSrcMaskPremultipliedAlpha.frag
        ├── FragShaderSrcMaskPremultipliedAlphaBlend.frag
        ├── FragShaderSrcPremultipliedAlpha.frag
        ├── FragShaderSrcPremultipliedAlphaBlend.frag
        ├── FragShaderSrcSetupMask.frag
        ├── VertShaderSrc.vert
        ├── VertShaderSrcBlend.vert
        ├── VertShaderSrcCopy.vert
        ├── VertShaderSrcMasked.vert
        ├── VertShaderSrcMaskedBlend.vert
        └── VertShaderSrcSetupMask.vert
```

---

## 設定手順ガイド

### ステップ 1：公式 SDK の入手

1. Live2D 公式ダウンロードページを開きます：[Live2D Cubism SDK for Native ダウンロード](https://www.live2d.com/en/sdk/download/native/)。
2. ライセンス規約を確認・同意の上、**Cubism 5 SDK for Native** アーカイブ（例：`CubismSdkForNative-5-r.5.zip`）をダウンロードし、任意の場所へ解凍します。

### ステップ 2：OpenGL シェーダーファイルの抽出

1. 解凍先フォルダから以下のディレクトリを開きます：
   ```text
   CubismSdkForNative-5-r.5/Framework/src/Rendering/OpenGL/Shaders/Standard/
   ```
2. 16 個の `.frag` フラグメントシェーダーと 6 個の `.vert` 頂点シェーダーが含まれています。
3. これら 22 ファイルすべてを、配置対象先の `FrameworkShaders/` フォルダへコピーします。

### ステップ 3：レンダラー DLL の取得またはビルド

`live2d_renderer.dll` は SDK の `Live2DCubismCore_MD.lib` および Framework と静的リンクしたラッパー DLL です。

ソースからビルドする場合（CMake 3.16+ および Visual Studio 2022 MSVC が必要）：
1. SDK 内の `Core/lib/windows/x86_64/143/Live2DCubismCore_MD.lib` を確認します。
2. `Samples/OpenGL/thirdParty/glew` と `stb` を確認します。
3. CMake ビルドを実行：
   ```powershell
   cmake -G "Visual Studio 17 2022" -A x64 -B build
   cmake --build build --config Release --target live2d_renderer
   ```
4. 生成された `live2d_renderer.dll` を配置先ディレクトリへコピーします。

### ステップ 4：所定ディレクトリへの配置 (3つの方法)

以下のいずれかの方法で配置します（アプリは上から順に優先探索します）：

#### 方法 1：プロジェクト内部のリソースディレクトリへ配置 (推奨)
`live2d_renderer.dll` と `FrameworkShaders` フォルダを以下へ配置：
```text
psd2live/src/main/resources/cubism/windows-x86_64/
```
> [!NOTE]
> このパスは `.gitignore` に登録されており、ローカル環境でのみ認識され、**Git リポジトリへ誤ってコミットされることはありません**。

#### 方法 2：プロジェクトルートへ配置
`psd2live` のルート直下に `cubism` フォルダを作成して配置：
```text
psd2live/cubism/windows-x86_64/
```
（このパスも `.gitignore` の対象です）。

#### 方法 3：環境変数または JVM パラメータによる指定 (外部配置)
外部の共通ディレクトリ（例：`D:\SDK\live2d_cubism_runtime`）に配置する場合：
- **環境変数**：`CUBISM_SDK_PATH` または `LIVE2D_SDK_PATH` に当該パスを設定。
  ```powershell
  [System.Environment]::SetEnvironmentVariable("CUBISM_SDK_PATH", "D:\SDK\live2d_cubism_runtime", "User")
  ```
- **JVM 引数**：起動時に `-Dpsd2live.cubism.path="D:\SDK\live2d_cubism_runtime"` を指定。

---

## 動作確認とステータス表示

配置後、GUI を起動します：
```powershell
.\run-gui.bat
```

プレビュー（Preview）ビューの左下ステータス表示を確認します：
- **`原生 Cubism (实时物理)`** または **`原生 Cubism`**：公式 SDK のロードに成功し、GPU オフスクリーン描画が有効です。
- **`ソフトウェアラスタライズ`**：SDK が未設定または Windows 以外の環境であり、内蔵 CPU 描画へ正常にフォールバックしています。

---

## よくある質問 (FAQ)

### Q1: SDK を設定しなくても `.cmo3` や `.moc3` は出力できますか？
**基本出力は全く問題なく実行できますが、公式 SDK は権威ある真値一致性（Ground Truth）を提供します。** モデルの生成・出力ロジックはすべて Kotlin/Java 独自実装であり、生成された `.cmo3` は公式 Live2D Cubism Modeler で直接開いて編集できます。公式 SDK を設定する真の価値は、書き出し前にクリッピングマスクの透明度、Premultiplied Alpha ブレンド、髪や瞳の物理揺れが実機ゲーム環境と 100% 同一であることを事前に検証できる点にあります。

### Q2: `Missing Cubism SDK 5-r.5 runtime resource: live2d_renderer.dll` と表示される
1. DLL が上記3つのいずれかのパスに存在するか確認してください；
2. 実行環境が 64-bit Windows であるか確認してください；
3. 方法3を利用している場合、環境変数が正しく反映されているか確認してください。

