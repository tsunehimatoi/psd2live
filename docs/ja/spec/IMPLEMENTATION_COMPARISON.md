# 実装概要と設計上の選択

[Docs](../../README.md) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)

旧 IMPLEMENTATION_COMPARISON の URL を維持しています。計測根拠のない他方式との優劣比較は行わず、出典は第三者表記にまとめます。

| 段階 | 実装と選択 |
| --- | --- |
| 素材 | PSD の画素・順序・属性を読む。複雑な効果は素材準備時にラスター化する。 |
| 分類 | NFKC、別名、左右、番号を解析。未分類レイヤーは残して手動修正できる。 |
| メッシュ | Alpha 輪郭から適応三角形を生成。密度・輪郭精度・処理量を調整する。 |
| テクスチャ | アトラス、余白、任意の高解像度化。解像度と画布座標を分離する。 |
| Rig | 頭体・顔・髪を生成後、パラメータ・構造・形状・パス編集を適用する。 |
| 操作 | 選択・変形・編集・描画を一つの画布に統合。描画は独立セッション。 |
| 履歴 | 追記型分岐と内容参照素材を単一工程へ保存する。 |
| 出力 | CMO3 / MOC3 と付属ファイルを作成し、再読込・幾何診断を行う。 |

## 不変条件

- 画像・画布は左上原点、X は右、Y は下。親の局所空間は対象種別に従う。
- トポロジー変更時は UV、キー形状、Glue 参照、パス結合を更新する。
- 復元の基準は源画素と再実行可能な編集であり、描画スナップショットだけではない。
- 相対リソース参照は出力一式の中で解決できる必要がある。
- 解析、未解釈情報の保持、公開編集、視覚的一致は別々に検証する。

[Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [RigBuilder](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [CanvasEditor](../../../src/main/kotlin/io/github/psd2live/ui/CanvasEditor.kt) · [Runtime / export (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
