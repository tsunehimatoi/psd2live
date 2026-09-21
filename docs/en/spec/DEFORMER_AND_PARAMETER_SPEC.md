# Deformer and parameter reference

[Docs](../../README.md) · [PSD](PSD_LAYER_SPEC.md)

This page describes generated defaults and editing conventions. Actual objects depend on artwork, configuration and edits. Read the hierarchy, parameter panel or MCP inspect output for the current project.

## Structure

Body XY and body Z / breath sit above head rotation and the head-follow container. Facial lattice, contour and feature displacement organize eyes, brows, nose, mouth and ears. Front/back hair have independent follow and physics branches. Region groups, iris preservation/gaze and optional lips add further nodes; the live hierarchy is authoritative.

## Coordinates and keyforms

Canvas coordinates start at the top left, with X right and Y down. Geometry is evaluated in parent-local space; normalized Warp coordinates and Rotation-local coordinates differ. Rest meshes and keyforms also have conversion-specific conventions, handled by restMeshesToCanvasSpace and format conversion.

Direct bindings define an object’s own axes; ancestor motion is inherited. A keyform is a shape at parameter coordinates, not an animation time frame. Motions and physics drive parameter values. A newly created parameter needs bindings and shapes to produce motion.

## Generated deformation

Head X/Y endpoint and midpoint combinations form nine poses, with estimated initial head tilt as a local reference. A facial lattice and regional corrections handle features, iris preservation, masks and mouth/ear behavior. Body XY, tilt and breath are separated. Hair follows the head independently of strong facial deformation; blink physics may drive iris form.

These are rigging presets, not general 3D reconstruction. Layering, anchor estimates and extreme angles need inspection. Exact curve constants and lattice divisions belong to the implementation and should be checked there rather than copied into an unversioned mathematical promise.

## Default parameters

The table follows RigParameters. Mesh-only mode, disabled deformers or missing parts may change the actual set. User parameters and variants can extend it.

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

## Validation

Inspect neutral, endpoints, combined angles and intermediate values, including parent/local interactions. Pipeline geometry diagnostics and export readback are checks, not proof of all poses or identical editor behavior. Target versions may require feature reduction.

[RigBuilder / RigParameters](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [PuppetModel](../../../src/main/kotlin/org/umamo/runtime/model/PuppetModel.kt) · [Architecture (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
