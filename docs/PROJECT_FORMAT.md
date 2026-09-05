# PSD2Live project format (version 1)

`.psd2live` is an unencrypted ZIP archive. A normal ZIP utility can extract it; JSON is UTF-8 and indented, and raster resources are lossless PNG files. There is no dependency on the original PSD path or another machine's recovery cache when opening a saved project.

## Contents

| Entry | Purpose |
| --- | --- |
| `manifest.json` | Format name, version, stable project UUID, and SHA-256 inventory of every payload file |
| `source/original.psd` | Original imported source bytes |
| `workspace.json` | Durable UI state: layout, camera, selections, parameter preview/locks, history annotations and logs |
| `images/<sha256>.png` | Images referenced by log entries |
| `workspace/<projectId>/HEAD.json` | Current history node and explicit node insertion order |
| `workspace/<projectId>/history/nodes/*.json` | Immutable parent-linked nodes: ID, revision, snapshot reference, original summary, actor, task and timestamp |
| `workspace/<projectId>/history/snapshots/*.json` | Editable source layers/groups, visibility, deletion flags, classification, hierarchy, parameter/keyform overlays and generation settings |
| `workspace/<projectId>/blobs/*-<width>x<height>.png` | Deduplicated RGBA layer/asset pixels, including RGB values under transparent alpha |
| `workspace/<projectId>/assets/*.json` | Staged PNG assets and their spatial placement |
| `workspace/<projectId>/views/*.json` | Coordinate mappings for previously rendered views |
| `workspace/<projectId>/view-images/*` | Rendered view images and view-ID-to-image records |
| `workspace/<projectId>/tasks.json` | Agent plans, status records and append-only task events |

Internal filenames for history records and raster references are SHA-256 keys of their logical IDs. A raster's `rgbaBlob` is the SHA-256 of its decoded RGBA bytes; the PNG filename uses the SHA-256 of that string and the raster dimensions. This preserves compatibility with the recovery store. Raster resources are shared across snapshots; saving repeatedly adds lightweight nodes without duplicating pixels.

## Saving and recovery

Every accepted save appends a checkpoint before writing the archive. Requests queue their immutable captures and serialize file replacement. The UI can continue editing; changes made after capture remain unsaved. The writer closes and flushes a temporary file in the destination directory, verifies its complete inventory, and atomically replaces the previous file. If atomic replacement is unsupported, saving reports a failure and keeps the previous project. A failed save retains its checkpoint and the dirty state.

The format preserves all branches without automatic pruning. Checkout changes HEAD; editing afterward appends a new child. UI titles, notes and hidden-branch flags live separately from original node records. Hiding a branch does not remove snapshots, assets or MCP visibility. Undo follows the parent; redo selects the only child or opens the tree to choose among branches.

Runtime previews are rebuilt from the saved editable source and each snapshot's own settings. Native SDK handles, sockets, active jobs and animation clocks are not serialized. Saved Agent tasks are records available for explicit continuation, not executable jobs.

Existing recovery stores can still read legacy `.rgba.gz` resources. Importing their original PSD migrates matching history and task data into a new UUID workspace, preserving the original cache. Old export JSON files are export reports, not complete projects.

## Validation and editing by hand

Opening validates format/version, file inventory, checksums, raster sizes/content, history IDs, parent links, HEAD and cycles before replacing the live workspace. Duplicate entries, traversal/absolute paths and unsupported versions are rejected. Extraction is limited to 1,000,000 entries and 64 GiB of actual uncompressed bytes; it does not trust ZIP size declarations.

JSON and PNGs are inspectable, but changing a package by hand requires updating the manifest checksums and maintaining all referenced IDs and snapshot hashes. Use the history UI for routine annotations and branching. Future incompatible formats must use a new manifest version rather than silently reinterpret this schema.

## UI and MCP entry points

- Import PSD: `Ctrl+Shift+O`; choose a custom directory, the PSD's directory, or the installation's `projects` directory. Confirming writes the first project immediately.
- Open project: `Ctrl+O`; save: `Ctrl+S`; save as: `Ctrl+Shift+S`.
- Undo: `Ctrl+Z`; redo/choose a branch: `Ctrl+Y` or `Ctrl+Shift+Z`.
- `project_save`: saves to the location selected in the application and returns its checkpoint node ID. It reports an error if no destination is selected.
- `history_checkpoint` with `summary`: explicitly appends a node, including when content is unchanged.
- `project_get_state`: additionally reports `projectFile`, `projectDirty`, `projectSaving`, and `projectSaveError`. Existing MCP mutation tools keep their expected-HEAD preconditions.

Model-changing MCP calls commit one node after successful validation/rebuild. Read-only calls do not append history. Staging assets and recording Agent task events are auxiliary records, included in project saves; adding staged pixels to the model creates the corresponding editable history node.
