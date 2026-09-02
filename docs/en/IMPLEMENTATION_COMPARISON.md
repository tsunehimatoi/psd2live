# Technical Implementation and Design Comparison

[中文](../zh/IMPLEMENTATION_COMPARISON.md) | [日本語](../ja/IMPLEMENTATION_COMPARISON.md)

This document outlines the architectural decisions, mathematical algorithms, and pipeline stage comparisons for AutoLive2D.

---

## Pipeline Stage Comparison

| Pipeline Stage | Conventional / Baseline Approach | AutoLive2D Algorithm & Architecture |
| :--- | :--- | :--- |
| **1. PSD Decoding** | Lightweight decoders with incomplete layer blend modes | Unified `PsdReader` pipeline extracting cropped RGBA, layer order, opacity, visibility, group hierarchies, clipping masks, and blend modes in a single pass. |
| **2. Semantic Tagging** | Static English string matching without multilingual aliases or fault tolerance | Comprehensive normalization (NFKC, lowercase, copy suffix removal, longest prefix matching, and L/R suffix resolution) supporting 31 semantic tags while retaining unmapped layers. |
| **3. Bilateral Splitting** | Simple geometric bounding-box cuts without connected component topology | 8-connected BFS component extraction filtering sub-12px noise, isolating the two largest components across the facial center line, and assigning discrete virtual IDs. |
| **4. Mouth Handling** | Multiframe manual mouth states or missing inner component clipping | Integrated aperture interpolation: `mouth` serves as maximum open reference, collapsing toward the center seam upon closure; optional teeth and tongue components automatically clip against the mouth mesh. |
| **5. Spatial Anchors** | Rough overall bounding box heuristics | Alpha-weighted union combined with semantic hierarchy to determine face center, chin, shoulder, and hip anchors, logging any fallbacks to diagnostic output. |
| **6. Mesh Generation** | Uniform lattice or raw Delaunay, causing jagged contours and degenerate triangles | Separable Gaussian alpha pre-filtering with 95th-percentile adaptive binarization; periodic cubic Bézier fitting with physical support window corner detection; curvature-weighted adaptive resampling (up to 12x density); constrained Delaunay triangulation with topology-convergent Lawson edge flips and overlong internal edge bisection. |
| **7. Atlas Packing** | Fixed single-page allocations or excessive whitespace gaps | Deterministic multi-page Shelf Packer maintaining 1:1 pixel fidelity, 2px safety padding, and dynamic page dimension expansion. |
| **8. Deformer Hierarchy** | Direct parenting of hair and accessories under the face mesh, causing distortion | Decoupled hierarchy: `BodyXY` → `BodyZ_Breath` → `HeadRotation` → `HeadContainer`. Skull-follow and facial 9-pose deformations are isolated, parenting front and back hair independently. |
| **9. Nine-Pose Face Rig** | Naive 3D sphere projection, distorting the near eye under wide angles | $8 \times 8$ latitude/longitude lattice; $C^1$-continuous horizontal Roll profile (near-side reveal, broad identity plateau, far-side compression); vertical $V/\wedge$ pitch curvature; corner $C_{xy} = \text{yaw} \times \text{pitch}$ cross-correction terms. |
| **10. Body Kinematics** | Linear shears or global scaling artifacts | `BodyAngleX` longitudinal bell-curve envelope Roll (invariant row width); `BodyAngleY` $S$-curve latitude redistribution (invariant total height); `Breath` Gaussian chest expansion; exact mirror symmetry across opposite angles. |
| **11. Feature Warps** | Uniform offset without depth hierarchy | Perceived depth scale (Nose > Mouth > Eye > Ear); eye/brow shared projective slope constraint; iris shape preservation with counter-translation; eyelash alpha-weighted centerline closed U-curve; far ear opacity attenuation (~52%). |
| **12. Masking System** | Manual mask assignment, prone to iris distortion on blink | Iris meshes automatically reference the ipsilateral eye-white as `maskedBy`; shrinking eye-white geometry organically clips the pupil on closure; oral internals clip against mouth drawables. |
| **13. Physics System** | Simple single pendulums or missing facial dynamics | Root-pinned hair pendulums with $v^3$ cubic tip sway and lift gradients; eyelid closure velocity driving second-order damped oscillation for pupil jelly dynamics (`ParamEyeBallForm`). |
| **14. Idle Motion** | Missing default animations or external dependencies | Automated generation of a 6-second seamless looping `idle.motion3.json` covering breathing, subtle head/body sway, and natural eye blinks. |
| **15. Parameter Wiring** | Non-standard parameter naming and flat grouping | Conformance to standard Cubism parameter IDs; structured parameter display groups; combined XY parameter links exported to `.cdi3.json`. |
| **16. Export & Auditing** | Single format output without post-export verification | Dual format export (`.cmo3` editable project + `.moc3` runtime family); three-stage geometric integrity gates (neutral pose validation, 4 extreme pose checks, and warp lattice symmetry verification). |

---

## Coordinate Spaces and Invariants

1. **Raster and Atlas Space**: Origin at top-left, Y-axis pointing downward, UV coordinate V-axis non-inverted.
2. **Deformer Local Spaces**: Root Warp operates in canvas absolute space; child Warps and Rest Meshes operate in normalized parent space $[0, 1] \times [0, 1]$.
3. **CMO3 Conversion Invariant**: Rest meshes are transformed to canvas space prior to project serialization, while keyform delta absolutes retain normalized parent constraints.
4. **Manifest Closure**: All relative file references declared in `model3.json` resolve entirely within the output bundle directory.
5. **Integrity Verification Bounds**: Neutral pose evaluated world bounds must stay within $\le 4\%$ deviation from PSD source bounds; extreme angle poses must maintain drawable scales within $8\% \sim 400\%$ without opacity vanishing.

