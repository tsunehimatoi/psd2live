# デフォーマ階層・数理モデル・パラメータ仕様書 (Deformer & Math Specification)

[中文](../zh/DEFORMER_AND_PARAMETER_SPEC.md) | [English](../en/DEFORMER_AND_PARAMETER_SPEC.md)

本仕様書は、PSD2Live に実装されているデフォーマツリー構造、顔面 9 軸経緯度格子の数理モデル、パーツ別変形アルゴリズム、多段振り子物理演算、および自動幾何整合性検証について解説します。

---

## 目次

- [デフォーマ階層と座標空間](#デフォーマ階層と座標空間)
  - [1. デフォーマツリー構造](#1-デフォーマツリー構造)
  - [2. 座標空間と変換規則](#2-座標空間と変換規則)
- [顔面 9 軸経緯度格子の数理モデル](#顔面-9-軸経緯度格子の数理モデル)
  - [1. 軸の定義と標準キーフォーム](#1-軸の定義と標準キーフォーム)
  - [2. C1 連続水平 Roll 曲線](#2-c1-連続水平-roll-曲線)
  - [3. 垂直仰俯曲率 (V / ^)](#3-垂直仰俯曲率-v--)
  - [4. 斜め 4 隅の相互干渉補正項 ($C_{xy}$)](#4-斜め-4-隅の相互干渉補正項-c_xy)
- [パーツ別変形アルゴリズム](#パーツ別変形アルゴリズム)
  - [1. 知覚深度スケール](#1-知覚深度スケール)
  - [2. 目・眉の透視拘束面](#2-目眉の透視拘束面)
  - [3. まつ毛の Alpha 重心追従と閉眼 U 字曲線](#3-まつ毛の-alpha-重心追従と閉眼-u-字曲線)
  - [4. 口の円柱変形と中心線閉口補間](#4-口の円柱変形と中心線閉口補間)
  - [5. 奥側耳の透過フェード](#5-奥側耳の透過フェード)
- [体幹動作と呼吸変形モデル](#体幹動作と呼吸変形モデル)
  - [1. 体幹偏航 (BodyAngleX ベル型エンベロープ)](#1-体幹偏航-bodyanglex-ベル型エンベロープ)
  - [2. 体幹仰俯 (BodyAngleY S 字緯線再配置)](#2-体幹仰俯-bodyangley-s-字緯線再配置)
  - [3. 体幹傾斜と呼吸 (BodyAngleZ & Breath)](#3-体幹傾斜と呼吸-bodyanglez--breath)
- [物理演算および動的振動子](#物理演算および動的振動子)
  - [1. 髪の毛根固定多段振り子](#1-髪の毛根固定多段振り子)
  - [2. まばたき連動の瞳ぷるぷる物理](#2-まばたき連動の瞳ぷるぷる物理)
- [Cubism 標準パラメータ対応表](#cubism-標準パラメータ対応表)
- [自動幾何および対称性検証](#自動幾何および対称性検証)

---

## デフォーマ階層と座標空間

### 1. デフォーマツリー構造

PSD2Live では**頭部追従（Skull Follow）**と**顔面変形（Facial Surface）**を完全分離しています：

```text
Root (Canvas Space)
 └─ DeformBodyXY [Warp 4×6] (ParamBodyAngleX, ParamBodyAngleY)
     └─ DeformBodyZBreath [Warp 4×6] (ParamBodyAngleZ, ParamBreath)
         └─ DeformHeadRotation [Rotation] (ParamAngleZ)
             └─ DeformHeadContainer [Warp 4×5] (ParamAngleX, ParamAngleY 頭部追従)
                 ├─ DeformFaceNinePose [Warp 8×8] (ParamAngleX, ParamAngleY 9 軸変形)
                 │   ├─ DeformEyeShapeL / R [Warp 4×3] (目透視変形)
                 │   │   └─ DeformIrisPreserveL / R [Warp 4×3] (瞳形状維持)
                 │   │       └─ DeformEyeGazeL / R [Warp 2×2] (ParamEyeBallX, ParamEyeBallY)
                 │   ├─ DeformBrowShapeL / R [Warp 3×3] (眉透視変形)
                 │   ├─ DeformNoseShape [Warp 3×4] (鼻立体深度変形)
                 │   ├─ DeformMouthShape [Warp 4×3] (口円柱透視変形)
                 │   ├─ DeformEarOcclusionL / R [Warp 3×3] (耳透過フェード)
                 │   └─ FaceDetails & Head Accessories
                 ├─ DeformHairFrontFollow [Warp 3×4] (前髪視差追従)
                 │   └─ DeformHairFrontPhysics [Warp 3×4] (ParamHairFront 毛根固定多段振り子)
                 ├─ DeformHairBackFollow [Warp 3×4] (後髪視差追従)
                 │   └─ DeformHairBackPhysics [Warp 3×6] (ParamHairBack 毛根固定多段振り子)
                 └─ HeadAccessories
```

### 2. 座標空間と変換規則

- **キャンバス空間**: 左上原点、X 軸右、Y 軸下。ルート `DeformBodyXY` は絶対ピクセル空間で動作。
- **正規化ローカル空間**: 親 Warp 内の子 Warp および初期メッシュは正規化座標 $[0, 1] \times [0, 1]$ を使用。
- **CMO3 変換不変条件**: プロジェクト出力前に `restMeshesToCanvasSpace` で基準メッシュをキャンバス空間に変換しつつ、キーフォーム差分は親空間拘束を維持。

---

## 顔面 9 軸経緯度格子の数理モデル

### 1. 軸の定義と標準キーフォーム
- **ヨー軸 (Yaw)**: `ParamAngleX` $\in [-45^\circ, 0^\circ, +45^\circ]$
- **ピッチ軸 (Pitch)**: `ParamAngleY` $\in [-30^\circ, 0^\circ, +30^\circ]$
- **9 つの標準キーフォーム**:
  $$\{ \text{AngleX}_{-45}, \text{AngleX}_0, \text{AngleX}_{+45} \} \times \{ \text{AngleY}_{-30}, \text{AngleY}_0, \text{AngleY}_{+30} \}$$

### 2. C1 連続水平 Roll 曲線

頭部旋回時（$+X$ 旋回時など）：
- 手前側輪郭は短スパンで展開；
- 手前目の領域は**幅維持プラトー（Broad Plateau）**を形成し、キャラクター性を保持；
- 奥側は $C^1$ 連続 smoothstep 曲線に沿って滑らかに透視圧縮。

$$\operatorname{Roll}(x_{\text{dir}}) = \begin{cases} 
S\left(\frac{x_{\text{dir}} + 1}{-0.72 + 1}\right), & x_{\text{dir}} < -0.72 \\
1.0, & -0.72 \le x_{\text{dir}} \le 0.08 \\
1.0 - S\left(\frac{x_{\text{dir}} - 0.08}{1.0 - 0.08}\right), & x_{\text{dir}} > 0.08
\end{cases}$$
ここで $S(t) = t^2 (3 - 2t)$。

### 3. 垂直仰俯曲率 (V / ^)
- **伏せ目 ($\text{AngleY} < 0$)**: 緯線が顎に向かって収束し、特徴的な **V 字曲線**を形成。
- **見上げ ($\text{AngleY} > 0$)**: 緯線が上方へ放射し、**$\wedge$ 字曲線**を形成。

### 4. 斜め 4 隅の相互干渉補正項 ($C_{xy}$)
斜め姿勢における輪郭崩れを防ぐため、符号付きクロス項 $C_{xy} = \text{yaw} \cdot \text{pitch}$ を加算：
$$\Delta x_{\text{corner}} = C_{xy} \cdot R_x \cdot \text{arch}_x \cdot (0.012 + 0.018 \cdot y_{\text{lower}})$$
$$\Delta y_{\text{corner}} = C_{xy} \cdot R_y \cdot x \cdot \text{arch}_x \cdot 0.028 - |\text{yaw}| \cdot \text{pitch} \cdot R_y \cdot \text{arch}_x \cdot (0.007 + 0.008 \cdot y_{\text{lower}})$$

---

## パーツ別変形アルゴリズム

### 1. 知覚深度スケール
$$\text{Depth}(\text{Nose Tip}) > \text{Depth}(\text{Nose Bridge}) > \text{Depth}(\text{Mouth}) > \text{Depth}(\text{Eye}) > \text{Depth}(\text{Face Surface}) > \text{Depth}(\text{Ear})$$

### 2. 目・眉の透視拘束面
左右の目と眉は透視傾斜拘束線 $\text{Slope}_{\text{proj}} = \text{yaw} \cdot \text{pitch} \cdot 0.050$ を共有し、斜め姿勢でも平行関係を維持します。瞳は奥側で位置補正され寄り目を防止します。

### 3. まつ毛の Alpha 重心追従と閉眼 U 字曲線
各列の Alpha 重心 $Y_{\text{center}}(x) = \frac{\sum y \cdot \alpha(x,y)}{\sum \alpha(x,y)}$ を抽出し、閉眼時は目標 U 字曲線 $Y_{\text{closed}}$ に沿って滑らかに変形します。

### 4. 口の円柱変形と中心線閉口補間
`mouth` を最大開口状態（$\text{ParamMouthOpenY}=1$）とし、$\text{ParamMouthOpenY} \to 0$ で中心閉口線へ向心圧縮されます。

### 5. 奥側耳の透過フェード
奥側の耳は `ChannelGrids.OPACITY` により最大約 $52\%$ まで自動フェードアウトし、頭部による遮蔽を表現します。

---

## 物理演算および動的振動子

### 1. 髪の毛根固定多段振り子
- 前髪：長さ 3.0、遅延 0.9、倍率 1.522；
- 後ろ髪：長さ 15.0、遅延 0.8、倍率 2.061；
- 毛根を固定し、先端に向かって $v^3$ 立方勾配で揺れと跳ね上がり（Curl Lift）を適用：
  $$\Delta x_{\text{tip}} = \text{swing} \cdot \text{Sway} \cdot v^3, \quad \Delta y_{\text{lift}} = \text{swing}^2 \cdot \text{Curl} \cdot v^3$$

### 2. まばたき連動の瞳ぷるぷる物理
まばたき開閉速度により駆動される 2 階減衰振動子：
$$\dot{v} = 86.0 \cdot (\text{Drive} - \text{Form}) - 10.5 \cdot v, \quad \dot{\text{Form}} = v$$
まばたき瞬間に瞳が扁平化（Squash）し、開眼オーバーシュート時に反発（Stretch）します（`ParamEyeBallForm`）。

---

## Cubism 標準パラメータ対応表

| パラメータ ID | 表示名 | 範囲 | 既定値 | 駆動源 / 物理連動 |
| :--- | :--- | :---: | :---: | :--- |
| `ParamAngleX` | 角度 X | `[-45, +45]` | `0` | マウス X / 9 軸格子 |
| `ParamAngleY` | 角度 Y | `[-30, +30]` | `0` | マウス Y / 仰俯曲率 |
| `ParamAngleZ` | 角度 Z | `[-30, +30]` | `0` | 待機動作 / 頭部回転 |
| `ParamEyeLOpen` / `ROpen` | 左/右 目 開閉 | `[0, 1]` | `1` | まばたき / まつ毛・白目閉合 |
| `ParamEyeBallX` / `Y` | 目玉 X / Y | `[-1, +1]` | `0` | 視線追従 |
| `ParamEyeBallForm` | 目玉 ぷるぷる | `[-1, +1]` | `0` | まばたき連動 2 階減衰物理 |
| `ParamBrowLY` / `RY` | 左/右 眉 上下 | `[-1, +1]` | `0` | 眉上下オフセット |
| `ParamMouthForm` | 口 変形 | `[-1, +1]` | `0` | 口角曲率・幅 |
| `ParamMouthOpenY` | 口 開閉 | `[0, 1]` | `0` | 最大開口 $\to$ 中心線閉口補間 |
| `ParamBodyAngleX` | 体 X | `[-10, +10]` | `0` | 体幹ヨー Roll（行幅不変） |
| `ParamBodyAngleY` | 体 Y | `[-10, +10]` | `0` | 体幹ピッチ（全高不変） |
| `ParamBodyAngleZ` | 体 Z | `[-10, +10]` | `0` | 体幹傾斜 |
| `ParamBreath` | 呼吸 | `[0, 1]` | `0` | ガウス型胸部膨張 |
| `ParamHairFront` / `Back` | 前/後 髪 揺れ | `[-1, +1]` | `0` | 髪多段振り子物理演算 |

---

## 自動幾何および対称性検証

1. **中立姿勢の整合性 (`validateNeutralPose`)**: 世界座標が有限値であり、PSD 元境界との誤差が $\le 4\%$ であること。
2. **極限姿勢の完全性 (`validateHeadAnglePoses`)**: $\text{AngleX}=\pm 45^\circ, \text{AngleY}=\pm 30^\circ$ において各要素のサイズが $8\% \sim 400\%$ 内であり、不透明度が 0 にならないこと。
3. **方向性 Warp の対称性 (`validateDirectionalWarpDimensions`)**: 正負逆方向の角度パラメータにおいて、行幅・列高が完全な鏡像関係を維持すること。

