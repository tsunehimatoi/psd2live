# Deformer Hierarchy, Mathematical Formulations, and Parameter Specification

[中文](../zh/DEFORMER_AND_PARAMETER_SPEC.md) | [日本語](../ja/DEFORMER_AND_PARAMETER_SPEC.md)

This specification documents the topological deformer hierarchy, 9-pose facial lattice mathematics, secondary feature warps, multi-pendulum dynamics, and automated geometric integrity gates implemented in PSD2Live.

---

## Table of Contents

- [Deformer Topology & Coordinate Hierarchy](#deformer-topology--coordinate-hierarchy)
  - [1. Deformer Tree Topology](#1-deformer-tree-topology)
  - [2. Coordinate Spaces & Transformations](#2-coordinate-spaces--transformations)
- [Nine-Pose Facial Lattice Mathematics](#nine-pose-facial-lattice-mathematics)
  - [1. Axis Definitions & Standard Keyforms](#1-axis-definitions--standard-keyforms)
  - [2. C1-Continuous Horizontal Roll Profile](#2-c1-continuous-horizontal-roll-profile)
  - [3. Vertical Pitch Curvature (V / ^)](#3-vertical-pitch-curvature-v--)
  - [4. Diagonal Corner Interaction ($C_{xy}$)](#4-diagonal-corner-interaction-c_xy)
- [Decoupled Secondary Feature Warps](#decoupled-secondary-feature-warps)
  - [1. Perceived Depth Scale](#1-perceived-depth-scale)
  - [2. Eye & Brow Perspective Plane Constraint](#2-eye--brow-perspective-plane-constraint)
  - [3. Eyelash Alpha-Weighted Centerline Closed Arc](#3-eyelash-alpha-weighted-centerline-closed-arc)
  - [4. Mouth Cylindrical Warping & Seam Closure](#4-mouth-cylindrical-warping--seam-closure)
  - [5. Ear Occlusion & Attenuation](#5-ear-occlusion--attenuation)
- [Body Kinematics & Breathing Physics](#body-kinematics--breathing-physics)
  - [1. Body Yaw (BodyAngleX Bell-Curve Envelope)](#1-body-yaw-bodyanglex-bell-curve-envelope)
  - [2. Body Pitch (BodyAngleY S-Curve Latitude Shift)](#2-body-pitch-bodyangley-s-curve-latitude-shift)
  - [3. Body Lean & Breathing (BodyAngleZ & Breath)](#3-body-lean--breathing-bodyanglez--breath)
- [Physics & Dynamic Oscillators](#physics--dynamic-oscillators)
  - [1. Hair Multi-Pendulum & Root Pinning](#1-hair-multi-pendulum--root-pinning)
  - [2. Blink-Driven Eye Jelly Dynamics](#2-blink-driven-eye-jelly-dynamics)
- [Standard Cubism Parameter Mapping](#standard-cubism-parameter-mapping)
- [Automated Geometric & Symmetry Verification](#automated-geometric--symmetry-verification)

---

## Deformer Topology & Coordinate Hierarchy

### 1. Deformer Tree Topology

PSD2Live completely decouples **Skull Follow** from **Facial Surface Warping**:

```text
Root (Canvas Space)
 └─ DeformBodyXY [Warp 4×6] (ParamBodyAngleX, ParamBodyAngleY)
     └─ DeformBodyZBreath [Warp 4×6] (ParamBodyAngleZ, ParamBreath)
         └─ DeformHeadRotation [Rotation] (ParamAngleZ)
             └─ DeformHeadContainer [Warp 4×5] (ParamAngleX, ParamAngleY Skull Follow)
                 ├─ DeformFaceNinePose [Warp 8×8] (ParamAngleX, ParamAngleY 9-Pose Lattice)
                 │   ├─ DeformEyeShapeL / R [Warp 4×3] (Eye Perspective Warp)
                 │   │   └─ DeformIrisPreserveL / R [Warp 4×3] (Iris Shape Preservation)
                 │   │       └─ DeformEyeGazeL / R [Warp 2×2] (ParamEyeBallX, ParamEyeBallY)
                 │   ├─ DeformBrowShapeL / R [Warp 3×3] (Brow Perspective Warp)
                 │   ├─ DeformNoseShape [Warp 3×4] (Nose 3D Depth Warp)
                 │   ├─ DeformMouthShape [Warp 4×3] (Mouth Cylindrical Warp)
                 │   ├─ DeformEarOcclusionL / R [Warp 3×3] (Ear Occlusion Fade)
                 │   └─ FaceDetails & Head Accessories
                 ├─ DeformHairFrontFollow [Warp 3×4] (Front Hair Parallax Follow)
                 │   └─ DeformHairFrontPhysics [Warp 3×4] (ParamHairFront Pinned Multi-Pendulum)
                 ├─ DeformHairBackFollow [Warp 3×4] (Back Hair Parallax Follow)
                 │   └─ DeformHairBackPhysics [Warp 3×6] (ParamHairBack Pinned Multi-Pendulum)
                 └─ HeadAccessories
```

### 2. Coordinate Spaces & Transformations

- **Canvas Space**: Origin at top-left, X right, Y down. Root `DeformBodyXY` control points reside in absolute pixel space.
- **Normalized Parent Space**: Child Warps and Rest Meshes inside parent Warps use normalized coordinates $[0, 1] \times [0, 1]$.
- **Rotation Pivot Space**: `DeformHeadRotation` pivot resides in normalized parent space; its direct child `DeformHeadContainer` uses scaled pixel offsets relative to this pivot.
- **CMO3 / MOC3 Invariants**: Rest meshes are transformed to canvas space prior to project serialization via `restMeshesToCanvasSpace`, while keyform delta vectors maintain normalized parent bounds.

---

## Nine-Pose Facial Lattice Mathematics

PSD2Live utilizes an **$8 \times 8$ facial latitude/longitude lattice**:

### 1. Axis Definitions & Standard Keyforms
- **Yaw**: `ParamAngleX` $\in [-45^\circ, 0^\circ, +45^\circ]$
- **Pitch**: `ParamAngleY` $\in [-30^\circ, 0^\circ, +30^\circ]$
- **Nine Standard Poses**:
  $$\{ \text{AngleX}_{-45}, \text{AngleX}_0, \text{AngleX}_{+45} \} \times \{ \text{AngleY}_{-30}, \text{AngleY}_0, \text{AngleY}_{+30} \}$$

> [!NOTE]
> **Initial Head Roll & Origin Alignment**:
> The pipeline estimates authored head roll ($\text{initialAngleZ}$) from bilateral facial landmarks and aligns the `DeformHeadRotation` reference frame to it. The 9-pose lattice and planar rotation (`ParamAngleZ` $\in [-30^\circ, +30^\circ]$) operate in a roll-aligned coordinate space (`HeadCoordinateSpace`), treating the initial pose as the neutral origin to ensure symmetric rotation range without snapping the head upright.

### 2. C1-Continuous Horizontal Roll Profile

On head yaw (e.g. $+X$ right turn):
- Near side executes a short-span reveal translation;
- Near eye region maintains a **Broad Plateau** preserving eye geometry and character identity;
- Far side smoothly compresses along a $C^1$-continuous smoothstep curve.

$$\operatorname{Roll}(x_{\text{dir}}) = \begin{cases} 
S\left(\frac{x_{\text{dir}} + 1}{-0.72 + 1}\right), & x_{\text{dir}} < -0.72 \\
1.0, & -0.72 \le x_{\text{dir}} \le 0.08 \\
1.0 - S\left(\frac{x_{\text{dir}} - 0.08}{1.0 - 0.08}\right), & x_{\text{dir}} > 0.08
\end{cases}$$
where $S(t) = t^2 (3 - 2t)$.

### 3. Vertical Pitch Curvature (V / ^)
- **Look Down ($\text{AngleY} < 0$)**: Latitudes converge toward chin, forming a characteristic **V-shape curve**.
- **Look Up ($\text{AngleY} > 0$)**: Latitudes expand upward, forming an **$\wedge$-shape curve**.

$$\Delta y_{\text{pitch}} = -\text{pitch} \cdot R_y \cdot 0.018 + \text{pitch} \cdot y \cdot R_y \cdot 0.062 \cdot (0.78 + 0.22 \cdot \text{arch}_x) - \text{pitch} \cdot \text{arch}_x \cdot R_y \cdot 0.040$$
where $\text{arch}_x = \max(0, 1 - x^2)^{1.30}$.

### 4. Diagonal Corner Interaction ($C_{xy}$)
To prevent jaw skew and facial collapse on diagonal poses, PSD2Live injects signed cross terms $C_{xy} = \text{yaw} \cdot \text{pitch}$:
$$\Delta x_{\text{corner}} = C_{xy} \cdot R_x \cdot \text{arch}_x \cdot (0.012 + 0.018 \cdot y_{\text{lower}})$$
$$\Delta y_{\text{corner}} = C_{xy} \cdot R_y \cdot x \cdot \text{arch}_x \cdot 0.028 - |\text{yaw}| \cdot \text{pitch} \cdot R_y \cdot \text{arch}_x \cdot (0.007 + 0.008 \cdot y_{\text{lower}})$$

---

## Decoupled Secondary Feature Warps

### 1. Perceived Depth Scale
$$\text{Depth}(\text{Nose Tip}) > \text{Depth}(\text{Nose Bridge}) > \text{Depth}(\text{Mouth}) > \text{Depth}(\text{Eye}) > \text{Depth}(\text{Face Surface}) > \text{Depth}(\text{Ear})$$

### 2. Eye & Brow Perspective Plane Constraint
- Left/right eyes and brows share a projective slope:
  $$\text{Slope}_{\text{proj}} = \text{yaw} \cdot \text{pitch} \cdot 0.050$$
  Strictly zero when $\text{AngleY}=0$; maintains parallel alignment across diagonal poses.
- **Iris Preservation (`IrisPreserve`)**: Compensates iris position on the far side to prevent cross-eyed distortion.

### 3. Eyelash Alpha-Weighted Centerline Closed Arc
1. Extract column-wise alpha centroid: $Y_{\text{center}}(x) = \frac{\sum y \cdot \alpha(x,y)}{\sum \alpha(x,y)}$;
2. Fit target closed U-curve:
   $$Y_{\text{closed}}(x) = Y_{\text{edge}} + \max(1.5, H_{\text{white}} \cdot 0.38) \cdot (1 - \hat{x}^2)$$
3. Vertices smoothly interpolate along $Y_{\text{closed}}$ at $88\%$ thickness on closure.

### 4. Mouth Cylindrical Warping & Seam Closure
- `mouth` represents maximum open state ($\text{ParamMouthOpenY}=1$);
- Compresses toward central seam upon closing:
  $$\text{Seam}_Y = Y_{\text{top}} + H_{\text{mouth}} \cdot 0.48$$
  $$\text{Scale}_Y = \text{Scale}_{\text{closed}} + \text{open}^2(3 - 2\text{open}) \cdot (1 - \text{Scale}_{\text{closed}})$$

### 5. Ear Occlusion & Attenuation
Far-side ear opacity fades smoothly down to $\approx 52\%$ via `ChannelGrids.OPACITY` to simulate cranial occlusion.

---

## Body Kinematics & Breathing Physics

> [!IMPORTANT]
> **Upright Body Assumption & Posture Restrictions**:
> Body kinematics and breathing deformers are formulated on a vertical canvas coordinate frame (e.g., chest breathing expands along normalized height $v \approx 0.42$). Character source artwork must maintain a predominantly upright torso. Heavily tilted, sideways, or reclining postures produce lateral distortion and shear tearing, and are unsupported.

### 1. Body Yaw (BodyAngleX Bell-Curve Envelope)
$\sin(\pi v)$ longitudinal envelope; row width remains invariant; exact mirror symmetry across positive and negative yaw.

### 2. Body Pitch (BodyAngleY S-Curve Latitude Shift)
$\sin(2\pi v)$ latitude redistribution; total torso height remains strictly invariant.

### 3. Body Lean & Breathing (BodyAngleZ & Breath)
Gaussian chest breathing expansion centered at $v \approx 0.42$:
$$\text{Chest}(v) = \exp\left(-\frac{(v - 0.42)^2}{0.035}\right)$$

---

## Physics & Dynamic Oscillators

### 1. Hair Multi-Pendulum & Root Pinning
- Front hair: length 3.0, delay 0.9, scale 1.522;
- Back hair: length 15.0, delay 0.8, scale 2.061;
- Root locked, $v^3$ tip sway and curl lift:
  $$\Delta x_{\text{tip}} = \text{swing} \cdot \text{Sway} \cdot v^3, \quad \Delta y_{\text{lift}} = \text{swing}^2 \cdot \text{Curl} \cdot v^3$$

### 2. Blink-Driven Eye Jelly Dynamics
Second-order damped harmonic oscillator driven by eyelid velocity:
$$\dot{v} = 86.0 \cdot (\text{Drive} - \text{Form}) - 10.5 \cdot v, \quad \dot{\text{Form}} = v$$
Produces organic pupil squash on blink and stretch on overshoot (`ParamEyeBallForm`).

---

## Standard Cubism Parameter Mapping

| Parameter ID | Display Name | Range | Default | Driver / Physics |
| :--- | :--- | :---: | :---: | :--- |
| `ParamAngleX` | Angle X | `[-45, +45]` | `0` | Mouse X / 9-Pose Facial Lattice |
| `ParamAngleY` | Angle Y | `[-30, +30]` | `0` | Mouse Y / Pitch Curvature |
| `ParamAngleZ` | Angle Z | `[-30, +30]` | `0` | Idle Motion / Head Rotation |
| `ParamEyeLOpen` / `ROpen` | Left / Right Eye Open | `[0, 1]` | `1` | Idle Blink / Eyelash & Eye-White Closure |
| `ParamEyeBallX` / `Y` | EyeBall X / Y | `[-1, +1]` | `0` | Mouse Tracking Gaze |
| `ParamEyeBallForm` | EyeBall Form | `[-1, +1]` | `0` | Blink-Driven 2nd Order Jelly Dynamics |
| `ParamBrowLY` / `RY` | Brow Left / Right Y | `[-1, +1]` | `0` | Brow Vertical Offset |
| `ParamMouthForm` | Mouth Form | `[-1, +1]` | `0` | Corner Curvature & Width |
| `ParamMouthOpenY` | Mouth Open | `[0, 1]` | `0` | Full Open -> Center Seam Closure |
| `ParamBodyAngleX` | Body Angle X | `[-10, +10]` | `0` | Torso Yaw Roll (Invariant Row Width) |
| `ParamBodyAngleY` | Body Angle Y | `[-10, +10]` | `0` | Torso Pitch Shift (Invariant Height) |
| `ParamBodyAngleZ` | Body Angle Z | `[-10, +10]` | `0` | Torso Lean |
| `ParamBreath` | Breath | `[0, 1]` | `0` | Gaussian Chest Expansion |
| `ParamHairFront` / `Back` | Hair Front / Back | `[-1, +1]` | `0` | Multi-Pendulum Hair Physics |

---

## Automated Geometric & Symmetry Verification

1. **Neutral Pose Fidelity (`validateNeutralPose`)**: World bounds within $\le 4\%$ of PSD source; coordinates are finite (no NaN / Inf).
2. **Extreme Head Pose Integrity (`validateHeadAnglePoses`)**: Bounds within $8\% \sim 400\%$ scale on $\text{AngleX}=\pm 45^\circ, \text{AngleY}=\pm 30^\circ$; visible opacity $> 0$.
3. **Directional Warp Lattice Symmetry (`validateDirectionalWarpDimensions`)**: Exact mirror symmetry of row widths and column heights under opposite angle parameters.

