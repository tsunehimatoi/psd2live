<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/DEFORM_PATHS.md) · **English** · [한국어](../../ko/guide/DEFORM_PATHS.md) · [日本語](../../ja/guide/DEFORM_PATHS.md)

# Deform paths (experimental)

[Documentation](../README.md) · [Canvas editing](CANVAS_EDITOR.md)

A path is an editing aid attached to an ArtMesh. It is not a third deformer beside Warp and Rotation. Several paths on one ArtMesh are supported. One path does not control several ArtMeshes.

## Shortest steps

1. Select the ArtMesh and right-click it in the tree, then choose "Add deform path" (default `P` / `D`).
2. Click to place at least two control points. Enter confirms. Esc cancels.
3. In **Edit** mode, adjust the path's bind position, width, hardness, closure, and corner points. The image shape should stay unchanged at this stage.
4. Set the target pose in the parameter panel, switch to **Deform** mode, and drag the control points. The result is written into the ArtMesh shape.

Object mode only selects objects. Paths are split into an L2 group and an L3 group, and each group stores its own set (written into `.cmo3` as the path's edit level). This is separate from the mesh deformer's "Vertex / Bezier" levels. When a path is created, choose the group in the placement panel, or under "Path edit level" in "Tool details". An existing path is changed under "Edit level" in the inspector. The canvas shows and edits only the current group's paths. Guides follow the selected object and the edit-page view settings. The preview page does not show paths. Ordinary editing continues to use the main canvas history.

## Save and export

| File | What is saved |
| --- | --- |
| `.psd2live` | Path settings, bindings, and replayable shape edits |
| `.cmo3` | The native controller structure and the mesh keyforms |
| `.moc3` | Baked mesh keyforms. The runtime does not run the path algorithm |

The current algorithm uses curve sampling, moving least squares, and triangle binding. Being able to read and write the controller does not mean the drag result has been verified to match Cubism Editor. Width, hardness, large-angle deformation, and non-uniform parent scale need checking in particular.

Topology operations on the main canvas rebind the paths. A mesh that is too sparse, or constraints that conflict with each other, can still cause a deformation to be rejected. Full compatibility acceptance for opening, dragging, and re-saving in the official Editor is not recorded on this page yet.

Agents use `path_list` and `path_get` to view paths, `path_put` and `path_delete` to create, modify, or delete a path, `path_preview` to trial-drag control points, and `path_deform` to write the drag result as a keyform. `scope=paths` of `workspace_inspect` lists every path. Fields are in the [MCP interface](../agent/MCP_AUTHORING.md).
