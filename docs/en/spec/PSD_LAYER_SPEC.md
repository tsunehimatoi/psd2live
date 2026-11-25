<!-- lang: zh en ko ja -->
> [中文](../../zh/spec/PSD_LAYER_SPEC.md) · **English** · [한국어](../../ko/spec/PSD_LAYER_SPEC.md) · [日本語](../../ja/spec/PSD_LAYER_SPEC.md)

# PSD artwork and naming

[Documentation](../README.md) · [Operation reference](../guide/USER_GUIDE.md)

This page is for preparing artwork. Automatic recognition is only the preset entry. After import, tags and variants can still be changed in the layer table (see [Semantic tags](#semantic-tags)).

## Preparing artwork

A layered PSD in RGB, 8-bit, with a transparent background is the suggestion that makes automatic rigging easier. The user does not have to organize artwork under fixed names or groups first. The reader also supports 16-bit RGB and grayscale, indexed color, and 1-bit bitmaps. A PSD with no separate layers imports the merged image as one layer. Text, vectors, layer styles, and adjustment effects depend on the raster pixels already in the file. Their Photoshop editing meaning cannot be kept in full. A body that stands roughly upright, with parts drawn separately, helps automatic rigging, but it is not an import requirement.

## Import tolerance and limits

- An unknown name, a duplicate layer name, or a duplicate group name should not block import. A duplicate group gets its own internal path and keeps the display name.
- When a single layer's pixel channels cannot be decoded, the layer name and position are kept, empty pixels are used, and a warning is given. The other decodable layers continue to be read. Empty pixels do not mean damaged content has been recovered. The original PSD should be kept.
- When a modifier cannot handle the current input, that modifier's output is rolled back, the reason is shown in the modifier menu, and it and the features that depend on it are paused. Independent modifiers continue. Re-enabling a failed item retries it. A failure recorded during simulation preparation is saved with the project. Other runtime state is recomputed on rebuild. Cancel still stops the build immediately.
- PSD parsing and RLE decoding support interruption checks. Preset building reports the current stage. A large canvas, many layers, and simulation baking can still take time. A successful import does not mean every automatic motion has reached a usable quality.
- PSB, 32-bit float, CMYK, Lab, multichannel, and duotone are not supported. When the file structure is badly damaged, or it exceeds available memory, recovery is not guaranteed either. The error should be reported clearly. There is no promise that any file can be imported without loss.

The layering and naming suggestions below raise the quality of automatic recognition and deformation. They can be adjusted step by step after import.

An unnamed pixel layer can inherit the nearest explicit part folder (for example “脸”, “嘴”, or “眉毛”). Broad groups such as “身体” and “眼睛” do not assign a pixel's use directly. An explicit layer name wins. A generic layer number is not treated as the variant number of an inherited part. A one-sided eyebrow with no left/right mark takes its side from the eye position. A classification the user set by hand wins.

A layer's linear dodge (add), glow dodge, and linear light generate add. Multiply, linear burn, and color burn generate multiply. Screen stays screen, and on export to a runtime earlier than Cubism 5.3 it is lowered to add and written into the loss report. Other layer blend modes draw as normal. Automatic rigging keeps the hidden state of the source folder. A multiply group that does not use clipping composites its inner layers first, then multiplies the whole group, at its original opacity, with the layers below. A normal-blend group that can be composited on its own also keeps the group opacity. A pass-through group inside it requires the inner drawing to be normal blend and unclipped.

Semi-transparent brows and eyes that already have a see-through-hair effect can be placed in the `眼眉透发` group. Automatic rigging clips the composited group inside the visible front hair below it, and keeps the group opacity. A multiply hair shadow placed in the `刘海脸部投影` group is clipped by the main face skin below. A shadow layer can be paired as `front hair shadow fringe 1` with the matching `front hair fringe 1`. The program also checks whether the source alphas overlap, then reuses that hair piece's head-turn path. A headband that should turn with the back of the head can be marked `headwear rear` or `后置头饰`.

## Without a PSD: the layout manifest

Parts already separated by a program (for example the result of AI layering) can skip the PSD and be written as a [layout manifest](LAYOUT_MANIFEST.md) for PSD2Live: one JSON file (or a zip with that JSON at its root). Each layer states the image, the canvas rectangle, stacking order, group, tags, variant, and whether it is reference only (for example the original image before the parts were separated, shown on the canvas but not generated and not exported). A small part can carry more pixels than the canvas. Opening it produces a placed, ungenerated project. A layer with no tags is recognized by the name rules on this page.

## Part layering

### Eyes and eyelashes
- **Layering suggestion**: Draw the eye white, the pupil, and the eyelashes separately. The pupil keeps the full part that the eyelid covers.
- **Eyelash breakdown**: This can include several horizontal main strokes, pointed tufts, and a side wrap (a numeric suffix means coexisting layers by default). An independent lower lash is marked `lower`. The eyelid line and the shadow are marked `lid` and `shadow`.
- **Head-turn fit**: Layer by the upper lash line (the outer-corner lash wing can be its own layer), the eye white (drawn large, with the edge hidden by the lashes), the iris (drawn as a full circle, clipped by the eye white), the pupil, and the highlights. The iris and the highlights barely deform on a head turn. The eyelid opening deforms around them.

### Mouth and mouth interior
- **Basic requirement**: Provide artwork of the widest open mouth. The mouth interior can be drawn as one piece, or split further into upper teeth, lower teeth, and tongue (a closed-mouth original cannot recover a mouth interior that was never drawn).
- **Thick-painted lips / a precise open and close (two groups are recommended)**:
  - **Mouth-interior group**: `口腔` (the fill layer in the widest-open shape; opacity can be 0 so it is a pure clip mask), `口腔底` (drawn larger than the open mouth and clipped to `口腔`), `上牙` / `下牙` / `舌` (drawn as a real row of teeth, flat or slightly U-shaped, not cut into a ∩), and a dark shadow layer inside the mouth.
  - **Lip group**: `上唇` and `下唇` (including a ring of skin the same color as the face around them), and, when there is line art, `上唇线` and `下唇线` (the two lines close and meet at the same point at the left and right mouth corners).
- **Automatic stroke**: A stroke is generated only for a single mouth interior that has no lip artwork. When there is already a lip line, lip skin, a closed mouth seam, or an overlapping full mouth-shape variant, the original art is used. Interior clip layers, highlights, and shadows are not stroke sources. A lip layer that has pixels but is currently hidden still takes part in the decision.
- **Tongue out**: When tongue-out is enabled, the program generates an extended-tongue copy (the mouth-interior clip is removed, and it is placed above the lower lip and below the upper lip). No extra extended-tongue layer has to be drawn by hand.

### Face, features, and hair
- **Face skin**: Draw it as one sheet (or a complete outline band). The chin, the jaw corners, both cheeks, and the forehead corners must have a clear outline. On a head turn the face shape read from the feature points is kept.
- **Nose and ears**: The nose can be drawn on the face skin or as its own layer (an independent layer leads the most on a head turn; a complex nose can be split into the bridge line, the nose base, and the side silhouette). Draw the whole ear so it can tuck behind the hair on a head turn.
- **Hair**: Separate front hair, side hair (`side`), and back hair. Back hair and covered regions keep enough overlap for motion.

## Name reference

The table lists the classifier's known tags and some aliases. It does not include UNKNOWN. The full alias list is the source.

| Tag | Names |
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

The part and the side recognized from the name are shown in the editor as a set of tags, and they can be added or removed at any time. The combinations a generator understands are below. Any other text is the user's own tag and does not affect generation. `left` / `right` means the side. The vocabulary and the match rules are in [LayerTags.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerTags.kt).

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

## Left and right, and splitting

Left and right are the character's own: the character's left is usually on the right of the picture. Names such as `eyelash-l`, `eyelash-r`, `eyelash_left`, `左睫毛`, `왼쪽 속눈썹`, and `속눈썹_우` can be used. The Korean single characters `좌` / `우` need a separator or parentheses around them. The arrows `>` / `<` can also be a side (again with a separator or parentheses): `>` is the character's left (pointing at the right side of the picture, where it sits), and `<` is the character's right, for example `눈썹_>` and `흰자(<)`.

Eyelid blush, eyeliner highlights, and eyeliner covers can use `eyelash eye-detail-l/r`. They deform with the skin of the matching eye. The main lash line and the see-through hair layer use their own artwork. A brow shadow can use `eyebrow brow-detail-l/r`. When the original art already provides a brow on the bangs, mark it `eyebrow front-l/r` or `eyebrow cover-l/r`. Automatic see-through brows respect a cover that already exists on the same side.

A two-sided layer with no side (for example both eyes drawn on one layer) is not split automatically on import. It is generated as one part. When left and right need to be separate, layer them in the PSD, or split by mesh or polygon in the editor and check the classification. The `:r` / `:l` parts that an earlier version split out automatically are kept in old projects.

## Numbers and variants

A number such as `front hair 1` can tell pieces of artwork apart. The classifier also cleans some Photoshop copy suffixes. A numeric name by itself does not mean an expression switch has been set up. A toggle variant needs a parameter. A switch variant needs a shared parameter and a different linked ID.

## Unknown layers and checks

An unrecognized layer is not dropped only because its name is unknown. It can generate a mesh and use a fallback assignment. Correct it by hand in the layer panel. Check the neutral pose, eye and mouth open and close, head turns, and hair motion, and check layer order, left and right, masks, and exposed background. A successful name does not mean the automatic modeling has passed acceptance.

[LayerClassifier.kt](../../../src/main/kotlin/io/github/psd2live/core/LayerClassifier.kt) · [Parameters](DEFORMER_AND_PARAMETER_SPEC.md)
