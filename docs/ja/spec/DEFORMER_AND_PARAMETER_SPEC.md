# デフォーマとパラメータ

[Docs](../../README.md) · [PSD](PSD_LAYER_SPEC.md)

自動生成の標準構成と編集上の約束を説明します。実際の構成は素材・設定・編集によって変わるため、階層ツリー、パラメータパネル、MCP inspect の結果を確認してください。

## 構成

体 XY、体 Z / 呼吸の下に頭部回転と頭部追従があり、顔の格子・輪郭・パーツ変位を通じて目眉鼻口耳を制御します。前髪・後ろ髪には独立した追従・物理の枝があります。領域グループ、瞳の形保持・視線、任意の唇などもあるため、固定の古いツリーを工程の代わりにしないでください。

## 座標とキー形状

画布の左上が原点で、X は右、Y は下です。形状は親の局所空間で評価されます。Warp の正規化座標と Rotation の局所座標は異なります。静止メッシュとキー形状の形式変換は restMeshesToCanvasSpace 等で処理します。

直接の関連付けは対象自身の軸を定め、親の動きは継承されます。キー形状はパラメータ位置での形で、時刻のフレームではありません。モーション・物理はパラメータ値を動かします。新規パラメータだけでは動かず、形との関連付けが必要です。

## 自動変形

頭 X/Y の端点と中点の組み合わせから九姿勢を作り、初期の傾きを局所基準として推定します。顔格子、部分補正、瞳保持、マスク、口耳の処理を使います。体 XY・傾き・呼吸、髪の頭部追従・物理を分け、まばたきから瞳の形を駆動できます。

これはプリセットであり汎用 3D 復元ではありません。分層・基準点・極端な角度は確認が必要です。曲線定数や分割数は実装を参照し、古い数式を現行の保証として扱わないでください。

## 標準パラメータ

RigParameters に対応します。メッシュ専用、デフォーマ無効、パーツ不足などで実際の集合は変化します。利用者の追加パラメータや差分もあります。

| ID | Range | Default |
| --- | --- | --- |
| `ParamAngleX` | -45…45 | 0 |
| `ParamAngleY`, `ParamAngleZ` | -30…30 | 0 |
| `ParamBodyAngleX`, `ParamBodyAngleY`, `ParamBodyAngleZ` | -10…10 | 0 |
| `ParamEyeLOpen`, `ParamEyeROpen` | 0…1 | 1 |
| `ParamEyeBallX`, `ParamEyeBallY`, `ParamEyeBallForm` | -1…1 | 0 |
| `ParamBrowLY`, `ParamBrowRY` | -1…1 | 0 |
| `ParamMouthForm` | -1…1 | 0 |
| `ParamMouthOpenY`, `ParamBreath` | 0…1 | 0 |
| `ParamHairFront`, `ParamHairBack` | -1…1 | 0 |

## 検証

初期姿勢、端点、複合角と中間値を確認し、親と局所形状で動きを二重適用していないか調べます。幾何診断と出力再読込は検査であり、全姿勢や公式エディタとの一致の保証ではありません。出力先バージョンによる縮退にも注意してください。

[RigBuilder / RigParameters](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [PuppetModel](../../../src/main/kotlin/org/umamo/runtime/model/PuppetModel.kt) · [Architecture (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
