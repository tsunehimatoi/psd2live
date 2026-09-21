# Project format v1

[中文](../../zh/spec/PROJECT_FORMAT.md) · [Documentation](../../README.md) · [User guide](../guide/USER_GUIDE.md)

`.psd2live` is an unencrypted ZIP containing UTF-8 JSON and PNG raster resources. A saved project carries its source artwork and history without relying on the original PSD path. Export reports named `.psd2live.json` are not projects.

## Archive layout

| Path | Content |
| --- | --- |
| `manifest.json` | Format, version, project UUID and SHA-256 inventory |
| `source/original.psd` | Original imported source |
| `workspace.json` | Durable UI layout, camera, selection, parameter preview, annotations and logs |
| `images/<hash>.png` | Log images |
| `workspace/<projectId>/HEAD.json` | Current node and insertion order |
| `workspace/<projectId>/history/nodes/` | Immutable parent-linked nodes and metadata |
| `workspace/<projectId>/history/snapshots/` | Source layers, settings, structure and edit overlays |
| `workspace/<projectId>/blobs/` | Deduplicated RGBA rasters encoded as PNG |
| `workspace/<projectId>/assets/` | Staged artwork metadata |
| `workspace/<projectId>/views/`, `view-images/` | Spatial references and rendered images |
| `workspace/<projectId>/workflow/` | Reference packages, registrations and placement records |
| `workspace/<projectId>/tasks.json` | Agent task records and events |

Internal filenames may hash logical IDs rather than display names. PNG resources retain RGB under transparent alpha. Snapshots share rasters; auxiliary entries depend on features used.

## Save and recovery

Saves capture immutable state and serialize writes. The writer builds and validates a temporary archive beside the destination, then replaces the destination atomically. Unsupported atomic replacement fails while keeping the previous project. Later edits remain unsaved after an earlier capture completes.

Ordinary saves do not append a history node when content already matches HEAD. Explicit checkpoints may record unchanged content. A failed save is not durable completion; inspect the error state.

All branches are retained. Undo follows the parent, redo selects a successor, and editing from an old node creates a branch. Renaming, annotating or hiding branches does not rewrite original nodes or remove assets.

Runtime previews rebuild from source, settings and replayable edits. Native handles, connections, active jobs and animation clocks are not serialized. Agent task records do not automatically restart execution.

## Validation

Opening validates version, inventory, hashes, rasters, history references and HEAD. Duplicate entries, escaping paths and unsupported versions are rejected. Extraction limits are 1,000,000 entries and 64 GiB of actual decompressed bytes, not declared ZIP sizes.

Manual edits must preserve references and update inventory hashes. Use the UI for ordinary editing and history work. Legacy `.rgba.gz` recovery resources remain a compatibility read path, not the primary write format.

## Entry points

- Import PSD: `Ctrl+Shift+O`; open project: `Ctrl+O`.
- Save / save as: `Ctrl+S` / `Ctrl+Shift+S`.
- MCP: `revision` modes `save/checkpoint/list/restore`. Select the save destination in the UI first. Public summaries do not expose every internal persistence field; see the [MCP contract](../../zh/agent/MCP_AUTHORING.md).

[ProjectArchive](../../../src/main/kotlin/io/github/psd2live/project/ProjectArchive.kt) · [ProjectSession](../../../src/main/kotlin/io/github/psd2live/project/ProjectSession.kt) · [AgentWorkspaceStore](../../../src/main/kotlin/io/github/psd2live/agent/AgentWorkspaceStore.kt)
