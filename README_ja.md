# PSD2Live

[中文](README.md) | [English](README_en.md)

PSD2Live は、自動化された Live2D モデル生成パイプラインおよびデスクトップアプリケーションです。レイヤー分けされた PSD ファイルを入力として、レイヤーセマンティクスの自動認識、左右パーツの自動分離、適応型 Delaunay メッシュ生成、顔面 9 軸変形格子および分離型デフォーマ階層の構築、髪の多段物理演算、ぷるぷる瞳物理、およびシームレスな待機モーション生成を行い、編集可能な `.cmo3` プロジェクトおよび実行時 `.moc3` ファイル群をワンクリックで書き出します。

---

## ドキュメント一覧

| ドキュメント | 概要 |
| :--- | :--- |
| [ユーザー操作ガイド (docs/ja/USER_GUIDE.md)](docs/ja/USER_GUIDE.md) | デスクトップ GUI 構成、4 つのワークスペース画面、キャンバス操作、ショートカットおよび CLI リファレンス |
| [PSD レイヤー仕様および命名規則 (docs/ja/PSD_LAYER_SPEC.md)](docs/ja/PSD_LAYER_SPEC.md) | 31 種のセマンティックタグ、左右判定規則、連結成分自動分離、およびパーツ別レイヤー設計 |
| [デフォーマ階層・数理モデル・パラメータ仕様書 (docs/ja/DEFORMER_AND_PARAMETER_SPEC.md)](docs/ja/DEFORMER_AND_PARAMETER_SPEC.md) | デフォーマツリー構造、顔面 9 軸数理モデル、C1 連続曲線、パーツ別変形および物理演算仕様 |
| [実装比較と技術的設計決定 (docs/ja/IMPLEMENTATION_COMPARISON.md)](docs/ja/IMPLEMENTATION_COMPARISON.md) | 16 段階の処理別技術選定、座標系不変条件、および自動幾何整合性検証 |

---

## 主な機能

- **適応型メッシュ生成**: Alpha ガウス平滑化と適応的二値化；周期的 3 次 Bézier 曲線フィッティングによる角点検出と適応サンプリング（最大 12 倍）；位相適応型 Lawson 辺反転と超長辺中点二分割を備えた拘束付き Delaunay 三角化。
- **デフォーマ (Warp) 生成**:
  - **目／口の変形**: 目・眉の透視拘束面、瞳の奥側位置補正、まつ毛の Alpha 重心追従による滑らかな閉眼 U 字曲線；口の最大開口状態から中心線への向心閉口補間、歯・舌パーツの自動クリッピング。
  - **9 軸格子の構築**: `AngleX (±45°) × AngleY (±30°)` 8×8 顔面格子、C1 連続水平 Roll 曲線（手前展開、幅維持プラトー、奥側透視圧縮）、垂直 V/^ 仰俯曲率、斜め 4 隅の $C_{xy} = \text{yaw} \times \text{pitch}$ 相互干渉補正。
- **アニメーション**: 呼吸・微小な頭部/身体の揺れ・自然なまばたきを含む 6 秒間のシームレスループ `idle.motion3.json` を自動生成；デスクトップ GUI では公式 Cubism 5-r.5 SDK ネイティブ描画によるリアルタイム視線追従（Mouse Look）に対応。
- **物理演算**: 前髪・後ろ髪を頭部追従デフォーマへ独立配置し、毛根固定と $v^3$ 立方先端揺れ物理を適用；まばたき速度連動の 2 階減衰振動子による瞳ぷるぷる物理（`ParamEyeBallForm`）。
- **プロジェクト／ランタイム書き出し**: Live2D Cubism Modeler 5 で編集可能な `.cmo3` プロジェクトおよび実行時 `.moc3` ファイル群（`.model3.json`、`.cdi3.json`、`physics3.json`、`idle.motion3.json`、テクスチャアトラス）をワンクリックで同時出力；中立姿勢・極限姿勢・対称性の 3 段階自動検証ゲートを内包。

---

## クイックスタート

### 動作環境
- **Java Runtime**: JDK 21 以降
- **OS**: Windows 10/11 x64（公式 SDK ネイティブプレビュー推奨）、Linux / macOS（ソフトウェア描画）

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
