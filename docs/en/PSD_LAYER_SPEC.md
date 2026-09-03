# PSD2Live PSD Layer Specification

[中文](../zh/PSD_LAYER_SPEC.md) | [日本語](../ja/PSD_LAYER_SPEC.md)

PSD2Live adopts the **See-Through** semantic specification as its baseline naming standard, extended with multilingual English, Chinese, and Japanese aliases. This guide covers layering conventions, naming syntax, automated connected-component splitting, and best practices for artists and riggers.

---

## Table of Contents

- [General PSD Requirements](#general-psd-requirements)
- [Character Pose & Composition Guidelines](#character-pose--composition-guidelines)
- [Semantic Tag Directory](#semantic-tag-directory)
  - [1. Head Group](#1-head-group)
  - [2. Body Group](#2-body-group)
  - [3. Extra Group](#3-extra-group)
- [Side Resolution Syntax](#side-resolution-syntax)
- [Automated 8-Connected Component Splitting](#automated-8-connected-component-splitting)
- [Specialized Facial & Feature Layering](#specialized-facial--feature-layering)
  - [1. Eye System](#1-eye-system)
  - [2. Mouth & Oral System](#2-mouth--oral-system)
  - [3. Hair System](#3-hair-system)
- [Variants and Photoshop Copy Cleanup](#variants-and-photoshop-copy-cleanup)
- [Handling Unrecognized Layers](#handling-unrecognized-layers)
- [Artist Pre-flight Checklist](#artist-pre-flight-checklist)

---

## General PSD Requirements

1. **Color Mode**: **RGB / 8-bit per channel** (do not use CMYK, Lab, 16-bit, or 32-bit modes).
2. **Transparent Background**: Do not include opaque solid-white or solid-color background layers.
3. **Rasterization**:
   - Convert all text, vector, and shape layers to standard pixel rasters.
   - Merge layer effects (drop shadows, outer glows, strokes) into their parent artwork layer.
   - Merge adjustment layers (curves, color balance) destructively into target pixel layers.
4. **Layer Naming**: Use clear semantic naming instead of default indices (`Layer 1`, `Layer 2`).

---

## Character Pose & Composition Guidelines

To ensure automated rigging, 9-pose lattice fitting, and deformer hierarchies operate predictably, adhere to these composition constraints when preparing PSD artwork:

### 1. Initial Head Tilt & Rotation Range Calibration
- **Initial Tilt Permitted**: The character's head in the source artwork is permitted to have an initial tilt/roll angle (e.g., natural head tilts or expressive poses).
- **Baseline Calibration**: The pipeline estimates the authored roll angle (`initialAngleZ`) from bilateral features (eye-white, pupil, eyelash baselines) and aligns the rotation deformer (`DeformHeadRotation`) pivot axis accordingly.
- **Rotation Range**: Facial 9-pose deformation (`ParamAngleX` / `ParamAngleY`) and planar head rotation (`ParamAngleZ` $\in [-30^\circ, +30^\circ]$) **use the authored initial tilt as their neutral origin**, rather than forcibly snapping the head to a vertical orientation. Keep initial head roll within reasonable bounds (recommended within $\pm 25^\circ$) so bidirectional motion remains balanced.

### 2. Body Pose Restrictions (Excessive Tilt Unsupported)
- **Maintain Upright Stance**: The character's torso and body **must remain predominantly upright (vertical) or only slightly tilted**.
- **Excessive Body Tilt Unsupported**: Avoid importing PSDs where the character's body is heavily tilted, reclining sideways, lying down, or leaning over at steep angles.
- **Rationale**: Body yaw/pitch deformers (`ParamBodyAngleX` / `ParamBodyAngleY`) and chest breathing (`ParamBreath`, formulated with an envelope centered at vertical coordinate $v \approx 0.42$) assume vertical canvas coordinates. Severe body tilting causes breathing expansion to distort horizontally, produces non-linear shearing under torso rotation, and results in mesh tearing or severe surface folding.

---

## Semantic Tag Directory

PSD2Live supports 31 core semantic tags organized across three groups:

### 1. Head Group

| Semantic Tag | Recommended English | Chinese Aliases | Japanese Aliases | Binding Behavior |
| :--- | :--- | :--- | :--- | :--- |
| `BACK_HAIR` | `back hair`, `backhair` | 后发, 后髪, 后脑勺 | 後ろ髪 | Head-follow Warp -> Back hair multi-pendulum physics |
| `FRONT_HAIR` | `front hair`, `fronthair` | 前发, 前髪, 刘海 | 前髪 | Head-follow Warp -> Front hair multi-pendulum physics |
| `HEADWEAR` | `headwear`, `hat` | 帽子, 头饰, 发饰 | 髪飾り | Head container follow |
| `FACE` | `face`, `head` | 脸, 脸部, 面部 | 顔, 肌 | Facial surface baseline and centering reference |
| `FACE_DETAIL`| `facedetail`, `blush` | 脸部细节, 腮红 | チーク | Blushes, beauty marks, and facial tattoos |
| `IRIDES` | `irides`, `iris`, `pupil` | 瞳孔, 虹膜, 眼珠 | 目, 瞳 | Gaze tracking + auto eye-white clipping + eye jelly physics |
| `EYEBROW` | `eyebrow`, `brow` | 眉毛, 眉 | まゆ | Vertical brow movement and projective plane linkage |
| `EYEWHITE` | `eyewhite`, `eye_white` | 眼白, 白眼 | 目白 | Shrinks on closure, serves as clipping mask for iris |
| `EYELASH` | `eyelash`, `lash` | 睫毛 | まつ毛, まつげ | **Upper lashes only**; curves along alpha-weighted centerline into U-shape (no lower lashes) |
| `EYE_CLOSE` | `eye_close`, `eye_c` | 闭眼, 閉眼 | 目閉じ, 閉じ目 | Optional closed eye art (fades in on blink) |
| `EYEWEAR` | `eyewear`, `glasses` | 眼镜, 眼鏡 | メガネ | Eyeglasses on facial 9-pose lattice |
| `EARS` | `ears`, `ear` | 耳朵, 耳 | 耳 | Negative depth shift + far ear opacity attenuation (~52%) |
| `EARWEAR` | `earwear`, `earring` | 耳环, 耳饰 | イヤリング | Ear accessory follow |
| `NOSE` | `nose` | 鼻子, 鼻 | 鼻 | Maximum perceived 3D depth shift (tip > bridge > root) |
| `MOUTH` / `MOUTH_OPEN` | `mouth`, `mouth_open` | 嘴, 嘴巴, 口, 开口 | 口, 口開き | **Fully open art (clean stroke outlines preferred)**; compresses to central seam |
| `MOUTH_CLOSE`| `mouth_close`, `mouth_c` | 闭口, 闭嘴 | 口閉じ | Optional closed line art (fades out on mouth open) |
| `TOOTH_T` | `tooth-t`, `upper tooth` | 上牙, 上歯 | 上歯 | Optional upper teeth (auto-clipped by mouth) |
| `TOOTH_B` | `tooth-b`, `lower tooth` | 下牙, 下歯 | 下歯 | Optional lower teeth (auto-clipped by mouth) |
| `TONGUE` | `tongue` | 舌头, 舌 | 舌 | Optional tongue (auto-clipped by mouth) |

### 2. Body Group

| Semantic Tag | Recommended English | Chinese Aliases | Description |
| :--- | :--- | :--- | :--- |
| `NECK` | `neck` | 脖子, 颈部 | Base neck geometry connecting head to torso |
| `NECKWEAR` | `neckwear`, `collar` | 领饰, 围巾, 项链 | Collars, ties, and necklaces |
| `TOPWEAR` | `topwear`, `clothes` | 上衣, 衣服, 服装 | Torso kinematics and chest breathing expansion |
| `HANDWEAR` | `handwear`, `hand`, `arm`| 手, 手臂, 腕 | Arms and hands |
| `BOTTOMWEAR` | `bottomwear`, `pants` | 下装, 裤子, 裙子 | Lower torso, skirts, and pants |
| `LEGWEAR` | `legwear`, `leg` | 腿, 大腿 | Leg artwork |
| `FOOTWEAR` | `footwear`, `shoe` | 脚, 鞋 | Shoes and feet |

### 3. Extra Group

| Semantic Tag | Recommended English | Chinese Aliases | Description |
| :--- | :--- | :--- | :--- |
| `TAIL` | `tail` | 尾巴, 尾 | Animal tails and back appendages |
| `WINGS` | `wings`, `wing` | 翅膀, 翼 | Wings |
| `OBJECTS` | `objects`, `prop` | 道具, 物件 | Held props and external accessories |

---

## Side Resolution Syntax

For bilateral components (eyes, brows, ears), PSD2Live supports both suffix and prefix naming conventions:

- **Suffix syntax**: `eyelash-l`, `eyelash-r`, `eyelash_left`, `eyelash_right`, `eyelash l`
- **Prefix syntax**: `l-eyelash`, `r-eyelash`, `left_eyelash`, `right_eyelash`

> **Note on Left/Right Orientation**:
> PSD2Live strictly adheres to the **Character's Own Left/Right** convention:
> - **Character Left (`-l` / `LEFT`)**: Located on the **viewer's right**.
> - **Character Right (`-r` / `RIGHT`)**: Located on the **viewer's left**.

---

## Automated 8-Connected Component Splitting

If an artist merges left and right features into a single layer without side suffixes (`eyelash`, `eyewhite`, `irides`, `eyebrow`, `eye_close`):

1. The pipeline runs **8-connected BFS component extraction**;
2. Filters out noise fragments smaller than 12 pixels;
3. Selects the two largest contiguous components;
4. Verifies that they lie on opposite sides of the facial center line (`faceCenterX`);
5. Generates two independent virtual layers (appended with `:l` and `:r`) with discrete bounds and parameter bindings.

---

## Specialized Facial & Feature Layering

### 1. Eye System
- **Eye-White (`eyewhite`)**: Clean, solid white eyeball geometry.
- **Iris / Pupil (`irides`)**: Draw a complete circular iris even if partially obscured by eyelids. The pipeline automatically clips it using `eyewhite`.
- **Eyelash (`eyelash`)**:
  - **Upper eyelashes only**: The `eyelash` layer **must strictly contain only the upper eyelid and upper eyelashes**; do not include lower lashes or complete lower lid contours in this layer.
  - **Blink Deformation Mechanism**: On blink closure, the pipeline samples the alpha-weighted centerline across vertical slices and morphs the mesh downward to match the eye-white's bottom U-shaped boundary. If lower lashes are included, the computed centroid is pulled downward to the middle of the eyeball, causing upper and lower lash elements to crush into each other, resulting in double images, distortion, or tearing.
  - **Lower Lash Authoring**: If your character requires distinct lower lashes or lower eyeliner, place them into `facedetail` or an independent static layer so they remain exempt from blink compression.

### 2. Mouth & Oral System
- **Integrated Mouth Approach (Recommended)**:
  - `mouth` / `mouth_open`: Authored as the **fully open** mouth including lips, teeth, tongue, and oral cavity.
  - **Authoring as Open Mouth**: The open artwork serves as the baseline geometry ($\text{ParamMouthOpenY}=1$). When closed ($\text{ParamMouthOpenY}=0$), the mesh is centripetally compressed toward the horizontal midline into a ~1.25px seam. Drawing a closed mouth in the source art will leave the model unable to open its mouth.
  - **Clean Outlines Preferred (Strokes Recommended)**: Defining clear, crisp stroke outlines along the outer lip contour is strongly recommended. During extreme centripetal compression, clean upper and lower outlines naturally fuse into a sharp, well-defined seam. Lineless soft gradients or feathered paint tend to blur, smudge, or blend unnaturally with surrounding skin tones upon compression.
  - Optional teeth (`tooth-t`/`tooth-b`) and tongue (`tongue`) automatically clip against the mouth mesh.

### 3. Hair System
- **Front Hair (`front hair`)**: Bangs, fringe, and side locks.
- **Back Hair (`back hair`)**: Hair mass behind the head and shoulders.
- **Physics Behavior**: Root vertices are pinned, and swing amplitude scales cubically ($v^3$) toward the tips.

---

## Variants and Photoshop Copy Cleanup

- **Variant Numbers**: Distinguish multiple instances using numeric suffixes: `front hair 1`, `front hair 2`.
- **Copy Suffix Removal**: Automatically strips Photoshop duplicate suffixes (`layer copy`, `图层 副本`, `レイヤー のコピー`).

---

## Handling Unrecognized Layers

Layers not matching any known semantic rules are classified as `UNKNOWN`:
1. **Never Dropped**: Full adaptive mesh generation and atlas packing are performed.
2. **Spatial Container Assignment**:
   - Layers positioned above `face.bottom` are parented under `HeadContainer`.
   - Layers positioned below `face.bottom` are parented under `BodyZ_Breath`.
3. **Manual Reassignment**: You can change tags at any time in the GUI Layers Table.

---

## Artist Pre-flight Checklist

- [ ] Color mode is 8-bit RGB.
- [ ] Opaque background layer is deleted (transparent background).
- [ ] Character body is predominantly upright (no excessive tilt or horizontal reclining poses).
- [ ] Initial head tilt is within natural range (rotation limits will calibrate to this initial angle).
- [ ] Mouth is drawn fully open, preferably with clean outline strokes.
- [ ] Eyelashes are restricted strictly to the upper eye region (no lower eyelashes merged).
- [ ] Bilateral eye and brow layers are cleanly separated or clearly distinct across the midline.
- [ ] Front and back hair are split into distinct layers.
- [ ] Hidden draft and guide layers are removed.

