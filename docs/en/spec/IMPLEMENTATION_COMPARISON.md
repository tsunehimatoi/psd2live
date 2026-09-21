# Implementation overview and tradeoffs

[Docs](../../README.md) · [Third-party notices](../../../THIRD_PARTY_NOTICES.md)

This retains the IMPLEMENTATION_COMPARISON URL but avoids unsupported comparisons with generic alternatives. Third-party attribution belongs in the license notices.

| Stage | Implementation and tradeoff |
| --- | --- |
| Source | Read PSD pixels, order and properties; rasterize complex source effects when preparing artwork. |
| Classification | NFKC normalization, aliases, side and numeric suffix parsing; retain unknown layers for correction. |
| Mesh | Adaptive triangulation from alpha contours; balance density, fidelity and cost. |
| Textures | Atlas pages, edge padding and optional upscale; resolution changes do not redefine canvas coordinates. |
| Rig | Generate head/body/features/hair, then apply parameter, structure, shape and path edits. |
| Interaction | Select / Deform / Edit / Paint share the canvas; painting has a separate session. |
| History | Append-only branches and content-addressed assets in a portable archive. |
| Export | Convert CMO3 / MOC3 and assemble sidecars; readback and geometry checks produce diagnostics. |

## Invariants

- Image/canvas origin is top-left, X right and Y down; parent-local space depends on object type.
- Topology changes must update UVs, forms, Glue references and path bindings.
- Source pixels and replayable edits define project recovery; a render snapshot is not a replacement.
- Relative resource references must resolve inside the delivered bundle.
- Parsing, pass-through preservation, exposed editing and visual equivalence require separate evidence.

[Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [RigBuilder](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [CanvasEditor](../../../src/main/kotlin/io/github/psd2live/ui/CanvasEditor.kt) · [Runtime / export (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
