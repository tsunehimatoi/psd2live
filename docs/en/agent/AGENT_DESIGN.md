<!-- lang: zh en ko ja -->
> [中文](../../zh/agent/AGENT_DESIGN.md) · **English** · [한국어](../../ko/agent/AGENT_DESIGN.md) · [日本語](../../ja/agent/AGENT_DESIGN.md)

# Agent design and acceptance

[Documentation](../README.md) · [MCP interface](MCP_AUTHORING.md) · [Measured capabilities](../STATUS.md) · [Roadmap](../ROADMAP.md)

This page describes the design boundaries and the acceptance method for Agent/MCP integration. The concrete interface contract is the [MCP interface](MCP_AUTHORING.md) and the service runtime schema.

---

## 1. Roles

| Role | Responsibility |
| --- | --- |
| **External Agent** | Understand the user's intent, choose target objects, plan tool calls, and observe results (image generation is provided by the host) |
| **PSD2Live** | Run deterministic artwork, mesh, parameter, physics, simulation, and branch-history operations |
| **Modeler** | Decide art style, effect trade-offs, and final acceptance |

---

## 2. Core design boundaries

- **One command channel**: The GUI and MCP share the same application-layer commands (`WorkspaceHost`). Both prepare a candidate on the captured starting `state` and submit a single history node with CAS. An edit that only changes the render snapshot is lost on rebuild or reopen.
- **Stable identity references**: Every object is referenced by a deterministic ID. A display name is not an identifier.
- **Concurrency and history**: `state` is the optimistic concurrency control token. History is append-only. Checking out an old node and continuing to edit forms a branch.
- **Authored pose stays separate**: Preview pose (`preview_set/reset`, slider drags, and auto keyframes) goes through the `WorkspacePoseCommands` auxiliary CAS and does not enter rig edit history.
- **Artwork and splits share one source**:
  - Image import and registration are prepared in a separate session and submitted as an artwork manifest.
  - Connected-component / polygon splits (`source_split_components` / `source_split_polygon`) and depth-occlusion splits (`source_split_depth`) share the same application-layer commands as the GUI. A preset ID can be specified so the new layers can be edited further in the same atomic batch.
- **Strict output contract and errors**: Every public operation declares `request` / `outputSchema` (a background job also declares a terminal `jobResultSchema`). Failure returns a structured error code and conflict context. A successful job query does not mean the operation succeeded; check the terminal `status`.
- **Summary-first observation**: A query returns a summary first, then expands by target and page. Preview screenshots use a fixed canvas camera and distinguish source artwork, the composite preview, and the runtime model.

---

## 3. Task acceptance checklist

1. **Preconditions**: Confirm the project `state`, the target object IDs, and the dependent layers.
2. **Batch and dry run**: For several related edits, dry-run with `workspace_preview_edits`, then commit them atomically with `workspace_apply_edits`.
3. **Replay and persistence**: After the edit, confirm undo and redo, and that saving and reopening the `.psd2live` project reproduces the result.
4. **Visual and export review**: Check multi-parameter poses with preview screenshots, export the target format, and inspect the `.psd2live.json` loss report.
