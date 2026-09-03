# PSD2Live PSD Layer Specification

[中文](../zh/PSD_LAYER_SPEC.md) | [日本語](../ja/PSD_LAYER_SPEC.md)

PSD2Live adopts the **See-Through** semantic specification as its baseline naming standard, extended with multilingual English, Chinese, and Japanese aliases. This guide covers layering conventions, naming syntax, automated connected-component splitting, and best practices for artists and riggers.

---

## Table of Contents

- [General PSD Requirements](#general-psd-requirements)
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
| `EYELASH` | `eyelash`, `lash` | 睫毛 | まつ毛, まつげ | Eyelash centerline curves into smooth U-shaped closed eye line |
| `EYE_CLOSE` | `eye_close`, `eye_c` | 闭眼, 閉眼 | 目閉じ, 閉じ目 | Optional closed eye art (fades in on blink) |
| `EYEWEAR` | `eyewear`, `glasses` | 眼镜, 眼鏡 | メガネ | Eyeglasses on facial 9-pose lattice |
| `EARS` | `ears`, `ear` | 耳朵, 耳 | 耳 | Negative depth shift + far ear opacity attenuation (~52%) |
| `EARWEAR` | `earwear`, `earring` | 耳环, 耳饰 | イヤリング | Ear accessory follow |
| `NOSE` | `nose` | 鼻子, 鼻 | 鼻 | Maximum perceived 3D depth shift (tip > bridge > root) |
| `MOUTH` / `MOUTH_OPEN` | `mouth`, `mouth_open` | 嘴, 嘴巴, 口, 开口 | 口, 口開き | **Maximum open art**; `ParamMouthOpenY` compresses to center seam |
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
- **Eyelash (`eyelash`)**: Draw the standard open upper eyelash. On blink, the mesh tracks the alpha-weighted centerline to curve into a smooth U-shape.

### 2. Mouth & Oral System
- **Integrated Mouth Approach (Recommended)**:
  - `mouth` / `mouth_open`: Authored as the **fully open** mouth including lips, teeth, tongue, and oral cavity.
  - `ParamMouthOpenY` compresses the mesh toward the central seam upon closing.
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
- [ ] Bilateral eye and brow layers are cleanly separated or clearly distinct across the midline.
- [ ] Mouth is drawn in a fully open state.
- [ ] Front and back hair are split into distinct layers.
- [ ] Hidden draft and guide layers are removed.

