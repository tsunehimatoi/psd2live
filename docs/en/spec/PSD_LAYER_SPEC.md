# PSD artwork and naming

[Docs](../../README.md) · [User guide](../guide/USER_GUIDE.md)

This page covers source preparation. Automatic classification selects presets; you can correct layer type, part and side after import.

## Prepare artwork

Use a layered RGB, 8-bit PSD with transparency. Rasterize text, vectors and layer effects you need, and merge adjustment effects into the relevant pixels. Keep the body approximately upright. A small initial head tilt is supported by estimation, but inspect the result.

## Separate parts

Separate eye whites, irises and upper lashes. Preserve the hidden part of each iris. The upper-lash rig is not intended for combined lower eyelid lines, even if a lower-lash name is recognized; inspect or classify such details separately.

Supply the mouth fully open, either as one image or with separate upper teeth, lower teeth and tongue. Geometry cannot reconstruct an unseen mouth interior from closed-mouth pixels. Separate front and back hair and preserve overlap behind occluding parts.

## Name reference

The table lists known classifier tags and selected aliases, excluding UNKNOWN. The source contains the full alias list.

| Tag | Names / 名称 |
| --- | --- |
| `BACK_HAIR` | `back hair`, `后发`, `后髪`, `后脑勺` |
| `FRONT_HAIR` | `front hair`, `前发`, `前髪`, `刘海` |
| `HEADWEAR` | `headwear`, `帽子`, `头饰`, `頭飾` |
| `FACE` | `face`, `脸`, `臉`, `脸部` |
| `FACE_DETAIL` | `facedetail`, `脸部细节`, `面部细节`, `腮红` |
| `IRIDES` | `irides`, `瞳孔`, `虹膜`, `眼珠` |
| `EYEBROW` | `eyebrow`, `眉毛`, `眉`, `まゆ毛` |
| `EYEWHITE` | `eyewhite`, `眼白`, `白眼`, `白目` |
| `EYELASH` | `eyelash`, `睫毛`, `まつ毛`, `まつげ` |
| `EYE_CLOSE` | `eye close`, `闭眼`, `閉眼`, `目閉じ` |
| `EYEWEAR` | `eyewear`, `眼镜`, `眼鏡`, `めがね` |
| `EARS` | `ears`, `耳朵`, `耳`, `みみ` |
| `EARWEAR` | `earwear`, `耳环`, `耳環`, `耳饰` |
| `NOSE` | `nose`, `鼻子`, `鼻`, `はな` |
| `MOUTH` | `mouth`, `口`, `嘴`, `嘴巴` |
| `MOUTH_OPEN` | `mouth open`, `张嘴`, `張嘴`, `开口` |
| `MOUTH_CLOSE` | `mouth close`, `闭嘴`, `閉嘴`, `闭口` |
| `TOOTH_T` | `tooth-t`, `上牙`, `上歯`, `上齿` |
| `TOOTH_B` | `tooth-b`, `下牙`, `下歯`, `下齿` |
| `TONGUE` | `tongue`, `舌头`, `舌頭`, `舌` |
| `NECK` | `neck`, `脖子`, `颈部`, `頸部` |
| `NECKWEAR` | `neckwear`, `领饰`, `領飾`, `围巾` |
| `TOPWEAR` | `topwear`, `上衣`, `衣服`, `服装` |
| `HANDWEAR` | `handwear`, `手臂`, `手`, `腕` |
| `BOTTOMWEAR` | `bottomwear`, `下装`, `下裝`, `裤子` |
| `LEGWEAR` | `legwear`, `腿`, `大腿`, `小腿` |
| `FOOTWEAR` | `footwear`, `脚`, `腳`, `鞋` |
| `TAIL` | `tail`, `尾巴`, `尾`, `しっぽ` |
| `WINGS` | `wings`, `翅膀`, `翼`, `つばさ` |
| `OBJECTS` | `objects`, `道具`, `物件` |

## Sides and splitting

Left/right are the character’s own sides: character-left is usually screen-right. Names such as `eyelash-l`, `eyelash-r` and `eyelash_left` are supported. Eligible layers without a side assignment may split when their generated mesh has two disconnected components, including eyes, brows, hair pieces or limbs. Face, mouth and oral parts are excluded. Touching shapes or fragmented artwork need manual separation and inspection.

## Numbers and variants

Numbers such as `front hair 1` distinguish pieces; some Photoshop copy suffixes are removed during classification. Numbered names do not automatically establish expression switching. Set a parameter for toggle variants, or a shared parameter and different association IDs for exclusive variants.

## Unknown layers and checks

An unrecognized name alone does not discard the artwork; fallback classification and meshing remain available. Correct the type in the Layers panel. Inspect neutral and extreme poses, eye/mouth closure, masks, sides, drawing order and overlap.

[LayerClassifier.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerClassifier.kt) · [Parameters](DEFORMER_AND_PARAMETER_SPEC.md)
