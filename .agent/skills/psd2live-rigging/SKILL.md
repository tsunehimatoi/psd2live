---
name: psd2live-rigging
description: Edit PSD2Live model objects, parameter shapes, artwork variants and motion through MCP. Use for model editing, not ordinary repository programming.
---

# PSD2Live

Translate the requested result into object, artwork or motion edits. Basic naming and hierarchy requests use object_edit directly; they do not need a painting workflow.

- Geometry: [reference](references/rig-geometry.md), including units, selections and root-anchored sway.
- Artwork placement: [reference](references/assets.md).
- Painted expression/action switches: [reference](references/variants.md).
- Face poses: [reference](references/face.md).
- Hair: use hair-separation when relevant, or request the hair topic from agent_get_workflow.

rig_list_objects finds IDs; rig_inspect gives compact geometry; Views provide image/canvas mappings. Read only evidence useful to the edit. object_get supplies full keys when needed.

Edits require the current history head. Read state once and chain successful returned heads. Reconcile stale heads or uncertain commits before retrying. Optional task notes retain useful candidates, assumptions and evidence across long work.

Choose drawing methods for style and user preference. Visual judgment is fallible: report concrete changes and inspected poses, not unsupported quality claims. Prefer a useful assembled result over invisible refinement, within the user's budget.
