# PSD2Live

[中文](README.md) | [English](README_en.md)

PSD2Live は、自動化された Live2D モデル生成パイプラインおよびデスクトップアプリケーションです。レイヤー分けされた PSD ファイルを入力として、レイヤーセマンティクスの自動認識、左右パーツの自動分離、適応型 Delaunay メッシュ生成、顔面 9 軸変形格子および分離型デフォーマ階層の構築、髪の多段物理演算、ぷるぷる瞳物理、およびシームレスな待機モーション生成を行い、編集可能な `.cmo3` プロジェクトおよび実行時 `.moc3` ファイル群をワンクリックで書き出します。

> [!IMPORTANT]
> **デスクトップアプリをすぐに使いたい場合：** Windows 10/11 x64 ユーザーは [Releases](https://github.com/tsunehimatoi/psd2live/releases/latest) から実行ファイルをダウンロードしてください。ポータブル ZIP は展開するだけで実行でき、EXE または MSI インストーラーも選択できます。配布パッケージには Java ランタイムが含まれているため、ソースビルド環境は不要です。

<p align="center">
  <img src="docs/imgs/use.gif" alt="PSD2Live 操作デモ" />
  <br>
  <em>ワンクリック自動生成・リアルタイム視線追従・モーションプレビュー</em>
</p>

---

### ドキュメント一覧

| ドキュメント | 概要 |
| :--- | :--- |
| [ユーザー操作ガイド (docs/ja/USER_GUIDE.md)](docs/ja/USER_GUIDE.md) | デスクトップ GUI、バージョン履歴ツリー、独立ログドック、Agent / MCP 接続、ショートカットおよび CLI リファレンス |
| [Agent / MCP 製品・技術設計（中国語）](docs/zh/AGENT_ARCHITECTURE.md) | 実装済み MCP ツール、永続ワークスペース／履歴、画像ワークフロー、ロードマップ |
| [Live2D SDK 設定・利用ガイド (docs/ja/CUBISM_SDK_SETUP.md)](docs/ja/CUBISM_SDK_SETUP.md) | 公式 Native SDK ライセンス方針、シェーダー抽出、およびハードウェア高速化描画の設定手順 |
| [PSD レイヤー仕様および命名規則 (docs/ja/PSD_LAYER_SPEC.md)](docs/ja/PSD_LAYER_SPEC.md) | 31 種のセマンティックタグ、左右判定規則、連結成分自動分離、およびパーツ別レイヤー設計 |
| [デフォーマ階層・数理モデル・パラメータ仕様書 (docs/ja/DEFORMER_AND_PARAMETER_SPEC.md)](docs/ja/DEFORMER_AND_PARAMETER_SPEC.md) | デフォーマツリー構造、顔面 9 軸数理モデル、C1 連続曲線、パーツ別変形および物理演算仕様 |
| [実装比較と技術的設計決定 (docs/ja/IMPLEMENTATION_COMPARISON.md)](docs/ja/IMPLEMENTATION_COMPARISON.md) | 16 段階の処理別技術選定、座標系不変条件、および自動幾何整合性検証 |

---

## 主な機能

- **適応型メッシュ生成**: Alpha ガウス平滑化と適応的二値化；周期的 3 次 Bézier 曲線フィッティングによる角点検出と適応サンプリング（最大 12 倍）；位相適応型 Lawson 辺反転と超長辺中点二分割を備えた拘束付き Delaunay 三角化。

  <p align="center">
    <img src="docs/imgs/mesh22.png" width="32%" alt="22 px 高密度適応メッシュ" />
    <img src="docs/imgs/mesh64.png" width="32%" alt="64 px バランス型適応メッシュ" />
    <img src="docs/imgs/mesh115.png" width="32%" alt="115 px 低密度適応メッシュ" />
    <br>
    <em>メッシュ間隔 22 / 64 / 115 px：精細な輪郭から軽量トポロジーまでの密度比較</em>
  </p>
- **デフォーマ (Warp) 生成**:
  - **目／口の変形**: 目・眉の透視拘束面、瞳の奥側位置補正、まつ毛の Alpha 重心追従による滑らかな閉眼 U 字曲線；口の最大開口状態から中心線への向心閉口補間、歯・舌パーツの自動クリッピング。
  - **9 軸格子の構築**: `AngleX (±45°) × AngleY (±30°)` 8×8 顔面格子、C1 連続水平 Roll 曲線（手前展開、幅維持プラトー、奥側透視圧縮）、垂直 V/^ 仰俯曲率、斜め 4 隅の $C_{xy} = \text{yaw} \times \text{pitch}$ 相互干渉補正。
- **アニメーション**: 呼吸・微小な頭部/身体の揺れ・自然なまばたきを含む 6 秒間のシームレスループ `idle.motion3.json` を自動生成；デスクトップ GUI では公式 Cubism 5-r.5 SDK ネイティブ描画との連携による **100% 公式描画・物理挙動の一致性検証（Ground Truth）** に対応（本プロジェクトには公式専有 SDK バイナリは含まれず配布も行いません。詳細は [SDK設定ガイド](docs/ja/CUBISM_SDK_SETUP.md) を参照。未設定時は純 CPU 高精度ソフトウェアラスタライザーへ自動フォールバックします）；リアルタイム視線追従（Mouse Look）に対応。
- **物理演算**: 前髪・後ろ髪を頭部追従デフォーマへ独立配置し、毛根固定と $v^3$ 立方先端揺れ物理を適用；まばたき速度連動の 2 階減衰振動子による瞳ぷるぷる物理（`ParamEyeBallForm`）。
- **編集可能な Agent / MCP ワークスペース**: Bearer Token で保護されたローカル Streamable HTTP MCP により、ChatGPT/Codex、Gemini/Antigravity、その他の MCP ホストからプロジェクトを調査し、可逆なキャンバス座標付き PNG View の取得、透明素材の追加、パラメータ管理、ArtMesh・Warp/Rotation デフォーマ・Part・Glue の多次元キーフォーム編集を行えます。全変更は再開可能なタスク記録と、永続化された追記専用の分岐履歴へ保存されます。
- **プロジェクト／ランタイム書き出し**: Live2D Cubism Modeler 5 で編集可能な `.cmo3` プロジェクトおよび実行時 `.moc3` ファイル群（`.model3.json`、`.cdi3.json`、`physics3.json`、`idle.motion3.json`、テクスチャアトラス）をワンクリックで同時出力；中立姿勢・極限姿勢・対称性の 3 段階自動検証ゲートを内包。

<p align="center">
  <img src="docs/imgs/agent.png" alt="PSD2Live AI Agent の素材生成・統合・複数パラメータ描画ワークフロー" />
  <br>
  <em>Agent がヘアクリップを追加する例：Skill と MCP ツールの確認、モデルの調査、素材の生成と追加、別のパラメータ姿勢での確認。この事例は、以下のより複雑なタスクが利用可能であることを示すものではありません。</em>
</p>

### Agent の機能と実装状況

#### 利用可能

- その他の髪飾りや装飾を追加し、複数のパラメータ姿勢で重なり、位置、変形結果を確認する。

#### 理論上は可能だが、Agent による調整が安定せず、実装待ち

> [!WARNING]
> **以下のタスクは理論上は可能ですが、現状の Agent では適切な調整と完了を安定して行えません。一連の処理は極めて不安定であり、実装待ちの機能です。プログラムのデバッグが目的でない限り、試すことはお勧めしません。** MCP インターフェースが存在しても、Agent がタスクを完了できるとは限りません。生成・配置・修正の繰り返しによって大量のトークンや画像生成枠を短時間で消費し、利用できる結果が得られない可能性があります。

想定される実装難易度は、以下の順に高くなります。

1. 表情や動作の差分を生成し、追加のパラメータやアニメーションで制御する。例：`@v@` の表情、手を振る動作、腕を組むアニメーション。
2. 口を、唇、口内、歯、舌などの独立して編集できるレイヤーへ分離する。
3. 髪を、前髪、横髪、後ろ髪、アホ毛などの独立してリギングできる毛束へ分離し、隠れている領域を補完する。
4. 影を追加する。髪の影レイヤーや顔の側面の影レイヤーの生成・リギングを含む。

#### 現状の Agent では実現できないこと

- パーツを自然かつ精密に変形させること。AI が Warp/Mesh の各点を個別に操作し、変形を正しく制御する必要がありますが、現在の Agent はこの操作を安定して行えません。

> [!IMPORTANT]
> この種のワークフローでは、選択したモデルと Agent ハーネスの両方が、実際に利用できる画像生成機能を提供している必要があります。テキストや画像を理解できても、画像を生成して返せないモデルでは、素材の作成とインポートを完了できません。

最終結果は、モデル、画像生成器、Agent ハーネス、プロンプト、元 PSD のレイヤー分け品質に左右されます。基盤となるツールの回帰テストに合格しても、上記の実装待ちタスクが信頼できる状態になったことを意味しません。

**プロンプトエンジニアリングと Agent ワークフローに関する Pull Request を募集しています。** 特に、ツールの発見・選択、パーツ分離に必要な奥行きと重なりの理解、生成条件、配置と修正の手順、トークン予算、停止条件の改善に協力してくださる方を求めています。再現可能な事例、有効なプロンプトや Skill の改善、ワークフローの実装、評価用ケースを歓迎します。可能であれば、使用したモデルとホスト、実際の消費量、成功例と失敗例を添えてください。完了率の向上と無駄な再試行の削減を検証するために役立ちます。単発の成功例だけでは、これらの機能を実装完了とは扱いません。

---

## クイックスタート

### 動作環境
- **Java Runtime**: Releases からダウンロードする Windows 配布パッケージにはランタイムが含まれます。JDK 21 以降が必要なのは、Gradle でソースからビルドまたは起動する場合のみです。
- **OS**: Windows 10/11 x64（公式 SDK 設定時に公式ランタイムと 100% ピクセル単位の描画・物理一致性検証に対応）、Linux / macOS（内蔵 CPU ソフトウェア描画）
- **公式 SDK についての注意事項**: 本プロジェクトのソースコードおよび配布物には Live2D 公式の専有 SDK バイナリは**含まれず、再配布も行いません**。内蔵ソフトウェア描画および全モデル書き出し機能は SDK なしで 100% そのまま動作します。公式ランタイムとの厳格な一致性検証を行う場合は、[Live2D SDK 設定ガイド](docs/ja/CUBISM_SDK_SETUP.md) をご参照ください。

### デスクトップ GUI の起動

- **Windows クイック起動**: プロジェクトルートの `run-gui.bat` を実行。
- **Gradle 起動**:
  ```powershell
  # Windows
  .\umamo\gradlew.bat -p .\psd2live run

  # Linux / macOS
  ../umamo/gradlew -p ./psd2live run
  ```

#### 主なショートカット

| 操作 | ショートカット / マウス |
| :--- | :--- |
| **ズーム** | マウスホイール（`0.05x ~ 64.0x`） |
| **パン (平行移動)** | 中ボタンドラッグ または 余白左ドラッグ |
| **全体表示** | `F` / `Home` / `0` |
| **パーツ選択** | 描画要素を左クリック |
| **PSD を開く** | `Ctrl + O` |
| **再解析** | `Ctrl + R` |
| **生成して書き出し** | `Ctrl + G` |
| **指定先へ書き出し** | `Ctrl + Shift + G` |

#### AI Agent / MCP ホストへの接続

1. PSD2Live デスクトップアプリを起動したまま、**Agent / MCP → Agent / MCP 接続とプロンプト…** を開きます。
2. ChatGPT デスクトップ／Codex では HTTP TOML、Gemini／Antigravity では HTTP JSON をコピーします。その他の Streamable HTTP 対応ホストでは、表示されたエンドポイントと `Authorization: Bearer <token>` ヘッダーを使用し、旧 `/sse` へ変更しないでください。
3. HTTP MCP 非対応ホストでのみ Stdio JSON を使用します。Python 3 でリポジトリ直下の `mcp_proxy.py` を起動し、`PSD2LIVE_MCP_TOKEN` を読み取ります。Windows では PSD2Live が保存した Token も利用できます。
4. ドメイン作業には `.agent/skills/psd2live-rigging` と `.agent/skills/hair-separation` をホスト公式の Skill ディレクトリへ配置します。接続後はツール一覧を取得し、最初に `project_get_state` を呼び出します。

現在の MCP は、プロジェクト／レイヤー／パラメータ参照、オブジェクトとキーフォーム編集、パラメータ CRUD、モデルデータ PNG View、透明素材インポート、ソフト削除、再開可能タスク、追記専用の分岐履歴を提供します。プロジェクトの `HEAD` を進める編集には必ず最新の `expected_history_head_node_id` が必要です。タイムアウトや切断後は、再試行前に `project_get_state` と `history_list` でコミット状態を確認してください。

PSD2Live が提供するのは View、空間マッピング、素材インポートであり、ホスト固有の画像生成器ではありません。描画差分、パーツ分割、オクルージョン補完、ピクセル再構築では Nano Banana Pro/NBP、GPT Image 2（`gpt-image-2`）、または同等のホストネイティブ画像ツールを実際に呼び出し、その透明 PNG を `asset_import_png` へ渡します。Python、PIL/OpenCV、SVG、Canvas で代替画像を描かないでください。詳細は [ユーザー操作ガイド](docs/ja/USER_GUIDE.md) を参照してください。

---

### コマンドライン実行 (CLI)

```powershell
# 基本実行
.\umamo\gradlew.bat -p .\psd2live run --args="--input ./sample.psd --output ./output"

# 詳細オプション指定
.\umamo\gradlew.bat -p .\psd2live run --args="--input ./sample.psd --output ./output --atlas 8192 --mesh-spacing 48 --head-strength 1.2 --lang ja"
```

| オプション | 型 | 既定値 | 説明 |
| :--- | :---: | :---: | :--- |
| `--input <path>` | パス | *(必須)* | 入力 PSD ファイルパス |
| `--output <path>` | パス | `PSD同階層/psd2live-output` | 出力先ディレクトリ |
| `--lang <zh\|en\|ja>` | 文字列 | システム言語 | 言語設定 (`zh` / `en` / `ja`) |
| `--atlas <size>` | 整数 | `4096` | テクスチャアトラスサイズ (`256 ~ 16384`) |
| `--mesh-spacing <px>` | 整数 | `64` | 基本メッシュ間隔（ピクセル） |
| `--head-strength <val>`| 浮点数 | `1.0` | 頭部 9 軸変形倍率 |
| `--body-strength <val>`| 浮点数 | `1.0` | 体幹動作・呼吸倍率 |
| `--no-physics` | フラグ | `false` | 物理演算設定の生成をスキップ |
| `--no-cmo3` | フラグ | `false` | `.cmo3` プロジェクト書き出しをスキップ |
| `--no-moc3` | フラグ | `false` | `.moc3` ランタイム書き出しをスキップ |

---

## PSD 命名速查表

> [!TIP]
> **PSD 原画制作と構図の重要ポイント**：
> - **口は最大開口で描き、輪郭線（アウトライン）推奨**：口は最大開口状態で描画してください。唇の外周に明確な線画があると、求心圧縮時に自然で美しい閉口リップラインが形成されます。
> - **まつ毛は目の上半部のみ**：`eyelash` レイヤーは上まつ毛のみを描画し、下まつ毛は混在させないでください（閉眼 U 字曲線アルゴリズムの歪みを防ぐため）。
> - **頭部の初期傾きに対応**：原画頭部の自然な傾き（首かしげ）は許容され、自動検出された初期角度を基準（ニュートラル）として回転可動域が決定されます。
> - **身体は直立姿勢を維持（過度の傾きは非対応）**：体幹動作と胸部呼吸は垂直座標系を基準とするため、過度に傾いたポーズや横たわり姿勢は非対応です。
> 
> 詳細なレイヤー設計規則は [PSD レイヤー仕様および命名規則 (docs/ja/PSD_LAYER_SPEC.md)](docs/ja/PSD_LAYER_SPEC.md) を参照してください。

| パーツ | 推奨英語名 | 日本語別名 | 動作説明 |
| :--- | :--- | :--- | :--- |
| **髪** | `front hair`, `back hair` | 前髪, 後ろ髪, 後髪 | 頭部追従 Warp + $v^3$ 先端多段物理演算 |
| **顔** | `face`, `facedetail` | 顔, 肌, チーク, 頬紅 | 顔面輪郭およびディテール |
| **目** | `eyewhite`, `eyelash`, `irides`, `eye_close` | 目白, 白目, まつ毛, 瞳, 目閉じ | 左右自動分離、白目クリッピング、上まつ毛による滑らかな閉眼 |
| **眉** | `eyebrow` | 眉, まゆ | 左右自動分離および透视連動 |
| **鼻** | `nose` | 鼻 | 最大の立体空間深度変形 |
| **口** | `mouth`, `mouth_open` | 口, 口開き, 開口 | 最大開口原画（輪郭線推奨）；閉口時は中心線へ向心圧縮 |
| **口腔部品** | `tooth-t`, `tooth-b`, `tongue` | 上歯, 下歯, 舌 | オプション部品；口で自動クリッピング |
| **耳** | `ears` | 耳 | 負の深度オフセットおよび奥側透過フェード |
| **体** | `neck`, `topwear`, `bottomwear`, `legwear` | 首, 服, 上着, スカート, ズボン | 体幹ヨー、ピッチ、傾斜および呼吸膨張（身体の直立維持が必要） |
| **装飾** | `headwear`, `earwear`, `neckwear`, `tail`, `wings` | 髪飾り, イヤリング, マフラー, 尻尾, 羽 | 各親コンテナへ追従 |

---

## デフォーマ階層とパラメータ

```text
Root (Canvas Space)
 └─ DeformBodyXY (ParamBodyAngleX, ParamBodyAngleY)
     └─ DeformBodyZBreath (ParamBodyAngleZ, ParamBreath)
         └─ DeformHeadRotation (ParamAngleZ)
             └─ DeformHeadContainer (ParamAngleX, ParamAngleY 頭部追従)
                 ├─ DeformFaceNinePose (ParamAngleX, ParamAngleY 9 軸変形)
                 │   ├─ Eye / Iris / Brow / Nose / Mouth / Ear
                 │   └─ FaceDetails
                 ├─ HairFrontFollow → HairFrontPhysics (ParamHairFront)
                 ├─ HairBackFollow  → HairBackPhysics  (ParamHairBack)
                 └─ HeadAccessories
```

| パラメータ ID | 表示名 | 範囲 | 既定値 | 説明 |
| :--- | :--- | :---: | :---: | :--- |
| `ParamAngleX` / `Y` / `Z` | 角度 X / Y / Z | `[-45..45]` / `[-30..30]` / `[-30..30]` | `0` | 頭部ヨー、ピッチ、平面回転 |
| `ParamEyeLOpen` / `ROpen` | 左/右 目 開閉 | `[0, 1]` | `1` | まつ毛閉眼 U 字線、白目による瞳クリッピング |
| `ParamEyeBallX` / `Y` | 目玉 X / Y | `[-1, +1]` | `0` | 視線追従オフセット |
| `ParamEyeBallForm` | 目玉 ぷるぷる | `[-1, +1]` | `0` | まばたき連動 2 階減衰物理 |
| `ParamBrowLY` / `RY` | 左/右 眉 上下 | `[-1, +1]` | `0` | 眉上下オフセット |
| `ParamMouthForm` | 口 変形 | `[-1, +1]` | `0` | 口角曲率および幅 |
| `ParamMouthOpenY` | 口 開閉 | `[0, 1]` | `0` | 最大開口 $\to$ 中心線閉口補間 |
| `ParamBodyAngleX` / `Y` / `Z`| 体 X / Y / Z | `[-10, +10]` | `0` | 体幹ヨー Roll、S 字ピッチ、傾斜 |
| `ParamBreath` | 呼吸 | `[0, 1]` | `0` | ガウス型胸部呼吸膨張 |
| `ParamHairFront` / `Back` | 前/後 髪 揺れ | `[-1, +1]` | `0` | 髪多段振り子物理演算 |

---

## 出力ファイル構成

```text
output_dir/
├── sample.cmo3                    # 編集可能な Live2D Modeler 5 プロジェクト
├── sample.moc3                    # 実行時モデルバイナリ (MOC5 準拠)
├── sample.model3.json             # 実行時構成ファイル (テクスチャ・物理・モーション)
├── sample.cdi3.json               # 表示名メタデータ
├── sample.physics3.json           # 物理演算構成ファイル (髪多段振り子 + 瞳物理)
├── sample.idle.motion3.json       # 6 秒シームレスループ待機モーション
├── sample.4096/texture_00.png     # テクスチャアトラス
└── sample.psd2live.json         # 診断レポートおよびマッピングメタデータ
```

---

## ビルドとテスト

```powershell
# 配布用 ZIP アーカイブの生成
.\umamo\gradlew.bat -p .\psd2live clean test distZip

# 単体テストの実行
.\umamo\gradlew.bat -p .\psd2live test
```

---

## ライセンスおよび謝辞

- **ライセンス**: [GNU General Public License v3.0 (GPL-3.0)](LICENSE)。
- **サードパーティ謝辞**: 本プロジェクトは [Umamo](THIRD_PARTY_NOTICES.md) モジュールをリンクしており、セマンティクス設計やアルゴリズムにおいて [Stretchy Studio](THIRD_PARTY_NOTICES.md) を参考にしています。詳細は [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) を参照してください。

---

## 免責事項

- PSD2Live は独立して開発されたオープンソースプロジェクトであり、株式会社 Live2D およびその関連会社との提携、公認、または後援関係はありません。
- `Live2D`、`Cubism`、`.cmo3`、`.moc3` 等の名称および拡張子はフォーマット互换性の説明のみに使用されており、商標および知的財産権は各権利者に帰属します。本プロジェクトは公式の Live2D SDK を内包・再配布しません。
- 本ソフトウェアは現状有姿で提供されます。本番利用の前に必ず元の PSD ファイルのバックアップを取り、対象アプリケーションで生成結果をご確認ください。
