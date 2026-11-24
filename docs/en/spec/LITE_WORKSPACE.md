<!-- lang: zh en ko ja -->
> [中文](../../zh/spec/LITE_WORKSPACE.md) · **English** · [한국어](../../ko/spec/LITE_WORKSPACE.md) · [日本語](../../ja/spec/LITE_WORKSPACE.md)

# Lite workspace and mobile design (in design)

[Documentation](../README.md) · [Roadmap](../ROADMAP.md) · [MCP usage and interface](../agent/MCP_AUTHORING.md) · [Depth-split tutorial](../guide/DEPTH_SPLIT.md)

> **Status**: This document is a design **in progress**. It is not implemented in code yet. The lower-level capabilities it cites — `WorkspaceHost`, `AgentMcpService`, `source_split_depth`, `settings_update`, `layer_classify`, and the rest — are modules the repository already implements.

## 1. Background and goals

The current desktop workspace is built for a professional Live2D modeling flow. It has seven canvas modes, dozens of panels, a shortcut system, and a large set of project concepts. For a beginner who only wants to “turn an illustration into a model that moves,” the learning cost is close to that of a traditional professional tool.

The goal of the **Lite workspace** is a **zero-barrier, touch-first entry that a phone can also operate**:

- **No dense descriptions or extra buttons**: Drop professional terms, dense parameter tables, multiple canvases, and complex menus. Complete an action with a direct gesture and a wizard of at most one or two steps.
- **Keep only the tools that automatic generation cannot replace**: For example, **depth split** for parts that interleave in front and behind, feature-fit nudges, and part-role corrections.
- **Reach an external Agent through MCP**: The user does not operate complex panels in the lite interface. Advanced edits go to an AI Agent over MCP.
- **Fully interchangeable with the full project**: The bottom layer keeps the same `.psd2live` v2 project and the `WorkspaceHost` command channel. Switching between the lite edition and the full desktop edition, and reopening, loses nothing.

---

## 2. Scope and trade-offs (what stays and what is hidden)

| Area | Kept in the lite workspace | Hidden (left to the full edition or MCP) |
| --- | --- | --- |
| Import and generation | Import a PSD / image or open a project, and generate automatically with the `recommended` preset | Manual mesh-density edits, per-module switch tables, modifier-stack details |
| Preview and try-out | One-finger drag to follow, two-finger zoom and pan, expression/motion capsules, an overall amount slider | Timeline keyframes and curve editing, per-axis numeric edits in the parameter table, the physics pendulum node graph |
| Manual cleanup | **Depth-split wizard**, feature-point handle nudges, layer role / ignore marks, undo / redo | Adding mesh points / deleting edges / triangulation, the general deform brush, bone binding and weight painting, the full paint suite |
| AI collaboration | **One-click MCP start, stop, and connection settings**, operation-status hints, and one-click undo | Complex batch-transaction debugging, the low-level schema browser |
| Export | One-click export of common formats (a VTube Studio package / `.p2lrt` / a preview animation) | Per-target downgrade switches, the export loss-report detail table |

---

## 3. Core workflow (a four-step loop)

The lite workspace is organized as one main flow. The user does not need the hierarchy “mesh — deformer — parameter — keyform”:

1. **Import and generate automatically**:
   - Choose a local PSD / image, or an existing `.psd2live` project.
   - Call `project_import_psd` (the `recommended` preset by default) to generate a movable model directly, and enter the live preview.
2. **Try motion with gestures, and adjust the whole model**:
   - Drag on the canvas to preview head turns, eye tracking, breathing, and hair / clothing sway.
   - The bottom offers a few common expression / motion switches and one overall “motion amount” slider (mapped to `settings_update.rigTuning`). There is no need to adjust parameter sliders one by one.
3. **The manual tools that are still required**:
   - **Depth split (the core tool that stays)**: Fixes interleaving where one part of a layer is in front and another part is behind (a collar and the neck, hair and a shoulder ornament). Underneath it reuses `source_split_depth` and the front-layer eraser paint session, but a three-step touch wizard replaces right-click multi-select and mode switching:
     1. Tap the layer to split (for example the top);
     2. Tap the layer that should sit in between (for example the neck);
     3. The front and back layers are copied automatically and the erase state starts. Rub the front layer with a finger or stylus to erase the region that should hide behind, watch the front/back occlusion live, and tap “Done” to submit one history step.
   - **Feature-point nudge**: When an auto-fitted eye corner, brow peak, or mouth corner is slightly off, the canvas shows a large touch handle (corresponding to `settings_update.moves`). Drag it to refit.
   - **Quick role correction**: After tapping a part, correct its classification with a direct role icon (for example “this is the left eye / front hair / ignore”), corresponding to `layer_classify` and regeneration.
4. **One-click export**:
   - Choose the intended use (for example “for VTube Studio” or “export `.p2lrt`”). Export runs in the background. A short notice appears only when a blocking problem exists.

---

## 4. Phone and touch-first interface

So the interface stays usable in phone portrait, tablet landscape, and a narrow desktop window, it follows these constraints:

- **The canvas fills the main viewport**: More than 85% of the area is the model canvas. There is no persistent left or right sidebar and no stacked top menu bar.
- **A minimal top bar**: Only four core entries — `工程/返回`, `撤销 / 重做` (the `history` branch history), `MCP 状态`, and `导出`.
- **A single-layer contextual bottom bar**:
  - The default state offers three main tabs: `试动`, `修整`, and `MCP`.
  - When a specific tool is active (for example the erase stage of “depth split”), the bottom bar is replaced in place by that tool’s minimum control strip (brush-size slider, undo stroke, cancel / done). It does not open a floating window over the canvas.
- **No hover, no right-click, no shortcut dependency**:
  - Mouse-hover tips are not the only explanation. There is no right-click context menu, and multi-select does not depend on `Shift` / `Ctrl`. Every multi-select becomes an explicit “tap step.”
  - Every touch target keeps a large enough hit area (suggested ≥ 44–48 dp). Slider gestures and canvas-drag gestures are mutually exclusive, so a drag does not move the model by accident.

---

## 5. MCP integration

The lite edition sends complex editing to an external Agent over MCP. The interface stays small, and the ceiling stays open:

- **Reuse the existing service and contract**:
  - Reuse the **reduced tool set** of `AgentMcpService` (Streamable HTTP + Bearer Token). An external Agent can call the full application-layer capabilities, including `workspace_inspect`, `workspace_apply_edits`, `source_split_depth`, `settings_update`, and `author_axis`.
  - It shares `WorkspaceHost`’s `state` optimistic concurrency control and single-history-node submit semantics with the lite UI. Each change the Agent makes is visible on the lite canvas immediately, and the user can undo it in one step.
- **Connection experience on the lite side**:
  - The `MCP` panel shows only three things: the service switch, the current connection state (not connected / connected / the Agent is editing), and “copy connection settings / install prompt.”
  - When the lite edition runs on a phone or tablet, the user can set an externally reachable host address, or join an Agent host on the same LAN with a pairing token.

---

## 6. Technical reuse and phased delivery

### 6.1 Architecture reuse

The repository already separates the desktop UI from the application layer (`application` does not depend on `ui` or Compose; `LayerDependencyTest` guards the boundary):

- **No rewrite of the application layer or the core**: The lite workspace is a light front end on `WorkspaceHost` (the same role as the desktop `DesktopPresentation`). Generation, editing, history, MCP, and export all use the existing command channel.
- **Project and runtime compatibility**: A `.psd2live` saved by the lite edition can continue to be refined in the full desktop edition. A project saved by the full edition can be tried, depth-split, or exported in the lite edition.

### 6.2 Phased delivery

1. **Phase 1 (lite mode inside the desktop app)**: Add a “lite workspace” view switch in the existing desktop app. Validate the four-step main flow, the depth-split wizard, and the MCP shortcut first. Cross-platform runtime differences are out of this phase.
2. **Phase 2 (narrow screens and touch-only interaction)**: Finish the interaction that needs no right-click and no shortcuts, the portrait single-layer bottom bar, feature-point touch handles, and quick role correction.
3. **Phase 3 (mobile runtime form)**: Evaluate how to run the Compose UI on a phone or tablet. Prefer a “mobile lite front end + a remote or desktop `WorkspaceHost` service (preview stream and MCP),” or a local runtime that combines the `.p2lrt` runtime with a trimmed core.
