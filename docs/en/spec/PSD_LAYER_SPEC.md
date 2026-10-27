# PSD artwork and naming

[Documentation](../../README.md) · [中文](../../zh/spec/PSD_LAYER_SPEC.md) · [日本語](../../ja/spec/PSD_LAYER_SPEC.md) · [User guide](../guide/USER_GUIDE.md)

This page covers source preparation. Automatic classification is a starting point; after import you can change each layer's tags and variant in the Layers table (see [semantic tags](#semantic-tags)).

## Prepare artwork

Use a layered RGB, 8-bit PSD with transparency. Rasterize text, vectors and layer effects you need, and merge adjustment effects into the relevant pixels. Keep the body approximately upright. A small initial head tilt is supported by estimation, but inspect the result.

Layer blend modes: Linear Dodge (Add), Add Glow and Linear Light become Cubism's Add; Multiply, Linear Burn and Color Burn become Multiply; Screen stays Screen (a Cubism 5.3 blend mode: exporting for an earlier runtime version draws it as Add and says so in the loss report). Other modes are drawn as Normal. A group's blend mode has no effect; merge the group into one layer if it matters.

## Without a PSD: layout manifests

Parts split by a program, such as AI layer decomposition, can reach PSD2Live without a PSD, as a [layout manifest](LAYOUT_MANIFEST.md): a JSON file, or a zip holding it at its root, that lists each layer's image, canvas rectangle, stacking order, group, tags and variant, and whether it is only a reference (such as the picture the parts were split from: shown on the canvas, never generated or exported). Small parts may carry more pixels than the canvas gives them; opening the manifest gives a laid-out project that is not generated yet. Layers without tags are recognised by the naming rules on this page.

## Separate parts

Separate eye whites, irises and upper lashes. Preserve the hidden part of each iris. The upper-lash rig is not intended for combined lower eyelid lines, even if a lower-lash name is recognized; inspect or classify such details separately.

Supply the mouth fully open, either as one image or with separate upper teeth, lower teeth and tongue. Geometry cannot reconstruct an unseen mouth interior from closed-mouth pixels.

For painted lips, or a cleaner open and close, split the mouth into two groups as hand riggers do. The inside: `mouth inside` (`口腔`; the opening at its widest, one filled shape drawn a little larger than the lips' inner edge, which also clips the teeth and tongue), `upper teeth`, `lower teeth` and `tongue`, with any dark inside the mouth as a layer named as a shadow. The lips: `upper lip` and `lower lip` (each lip with a band of face-coloured skin about it, its colour, gloss and shading painted in), and, where the art has line work, `upper lip line` and `lower lip line`. A lip rides its edge whole without squashing and covers the inside when the mouth shuts, so a mouth with lip layers closes its inside only by the "Inside behind the lips" setting. A line follows its edge at its own thickness, and the two meet in one line when shut. A mouth with its own lips or lines gets no generated outline. Separate front and back hair and preserve overlap behind occluding parts.

## Name reference

The table lists known classifier tags and selected aliases, excluding UNKNOWN. The source contains the full alias list.

| Tag | Names / 名称 |
| --- | --- |
| `BACK_HAIR` | `back hair`, `后发`, `后髪`, `后脑勺`, `뒷머리` |
| `FRONT_HAIR` | `front hair`, `前发`, `前髪`, `刘海`, `앞머리` |
| `HEADWEAR` | `headwear`, `帽子`, `头饰`, `頭飾`, `모자` |
| `FACE` | `face`, `脸`, `臉`, `脸部`, `얼굴` |
| `FACE_DETAIL` | `facedetail`, `脸部细节`, `面部细节`, `腮红`, `볼터치` |
| `IRIDES` | `irides`, `瞳孔`, `虹膜`, `眼珠`, `눈동자` |
| `EYEBROW` | `eyebrow`, `眉毛`, `眉`, `まゆ毛`, `눈썹` |
| `EYEWHITE` | `eyewhite`, `眼白`, `白眼`, `白目`, `흰자` |
| `EYELASH` | `eyelash`, `睫毛`, `まつ毛`, `まつげ`, `속눈썹` |
| `EYE_CLOSE` | `eye close`, `闭眼`, `閉眼`, `目閉じ`, `감은 눈` |
| `EYEWEAR` | `eyewear`, `眼镜`, `眼鏡`, `めがね`, `안경` |
| `EARS` | `ears`, `耳朵`, `耳`, `みみ`, `귀` |
| `EARWEAR` | `earwear`, `耳环`, `耳環`, `耳饰`, `귀걸이` |
| `NOSE` | `nose`, `鼻子`, `鼻`, `はな`, `코` |
| `MOUTH` | `mouth`, `口`, `嘴`, `嘴巴`, `입`, `上唇`, `下唇`, `上唇线`, `下唇线`, `upper lip`, `lower lip line` |
| `MOUTH_OPEN` | `mouth open`, `张嘴`, `張嘴`, `开口`, `벌린 입` |
| `MOUTH_CLOSE` | `mouth close`, `闭嘴`, `閉嘴`, `闭口`, `다문 입` |
| `TOOTH_T` | `tooth-t`, `上牙`, `上歯`, `上齿`, `윗니` |
| `TOOTH_B` | `tooth-b`, `下牙`, `下歯`, `下齿`, `아랫니` |
| `TONGUE` | `tongue`, `舌头`, `舌頭`, `舌`, `혀` |
| `NECK` | `neck`, `脖子`, `颈部`, `頸部`, `목` |
| `NECKWEAR` | `neckwear`, `领饰`, `領飾`, `围巾`, `목도리` |
| `TOPWEAR` | `topwear`, `上衣`, `衣服`, `服装`, `상의` |
| `HANDWEAR` | `handwear`, `手臂`, `手`, `腕`, `팔` |
| `BOTTOMWEAR` | `bottomwear`, `下装`, `下裝`, `裤子`, `치마` |
| `LEGWEAR` | `legwear`, `腿`, `大腿`, `小腿`, `다리` |
| `FOOTWEAR` | `footwear`, `脚`, `腳`, `鞋`, `신발` |
| `TAIL` | `tail`, `尾巴`, `尾`, `しっぽ`, `꼬리` |
| `WINGS` | `wings`, `翅膀`, `翼`, `つばさ`, `날개` |
| `OBJECTS` | `objects`, `道具`, `物件`, `소품` |

## Semantic tags

The recognised part and side show in the editor as a set of tags you can change at any time. The generators read the combinations below; any other word is your own tag and does not affect generation; `left` / `right` give the side. Vocabulary and matching rules: [LayerTags.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerTags.kt) and the Chinese [materials and Layout](../../zh/spec/MATERIAL_LAYOUT_WORKSPACE.md) reference.

| Tag | Tags | Tag | Tags |
| --- | --- | --- | --- |
| `BACK_HAIR` | `hair` `back` | `MOUTH` | `mouth` |
| `FRONT_HAIR` | `hair` `front` | `MOUTH_OPEN` | `mouth` `open` |
| `HEADWEAR` | `headwear` | `MOUTH_CLOSE` | `mouth` `closed` |
| `FACE` | `face` | `TOOTH_T` | `teeth` `upper` |
| `FACE_DETAIL` | `face` `detail` | `TOOTH_B` | `teeth` `lower` |
| `IRIDES` | `eye` `iris` | `TONGUE` | `tongue` |
| `EYEBROW` | `brow` | `NECK` | `neck` |
| `EYEWHITE` | `eye` `white` | `NECKWEAR` | `neck` `accessory` |
| `EYELASH` | `eye` `lash` | `TOPWEAR` | `top` |
| `EYE_CLOSE` | `eye` `closed` | `HANDWEAR` | `arm` |
| `EYEWEAR` | `eyewear` | `BOTTOMWEAR` | `bottom` |
| `EARS` | `ear` | `LEGWEAR` | `leg` |
| `EARWEAR` | `ear` `accessory` | `FOOTWEAR` | `foot` |
| `NOSE` | `nose` | `TAIL` / `WINGS` / `OBJECTS` | `tail` / `wings` / `object` |

## Sides and splitting

Left/right are the character’s own sides: character-left is usually screen-right. Names such as `eyelash-l`, `eyelash-r`, `eyelash_left`, `왼쪽 속눈썹` and `속눈썹_우` are supported; the single-syllable Korean `좌` / `우` needs a separator or brackets. Eligible layers without a side assignment may split when their generated mesh has two disconnected components, including eyes, brows, hair pieces or limbs. Face, mouth and oral parts are excluded. Touching shapes or fragmented artwork need manual separation and inspection.

## Numbers and variants

Numbers such as `front hair 1` distinguish pieces; some Photoshop copy suffixes are removed during classification. Numbered names do not automatically establish expression switching. Set a parameter for toggle variants, or a shared parameter and different association IDs for exclusive variants.

## Unknown layers and checks

An unrecognized name alone does not discard the artwork; fallback classification and meshing remain available. Correct the type in the Layers panel. Inspect neutral and extreme poses, eye/mouth closure, masks, sides, drawing order and overlap.

[LayerClassifier.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerClassifier.kt) · [Parameters](DEFORMER_AND_PARAMETER_SPEC.md)
