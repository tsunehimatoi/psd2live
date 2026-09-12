# Work with objects and intent

Choose the smallest useful capability:

| Intent | Tools |
|---|---|
| Find objects | project_get_state, rig_list_objects, object_get |
| Rename, visibility, hierarchy | object_edit (ordered batch, one commit) |
| Shape at a parameter pose | rig_inspect, rig_preview, rig_transform, keyform_set/copy/delete |
| Pose/coverage evidence | view_render_poses, view_check_coverage |
| New artwork | asset reference, import, registration and composite tools |
| Motion over time | physics_list/put; output parameters need authored shapes |
| Save / recover | project_save, history_list/checkout; optional task notes |

Organizational Parts, drawing order and deformer inheritance are different relationships. Moving a mesh between Parts does not rebind its deformation. A local-space rebind retains local keys but changes inherited appearance; it does not promise world-space motion preservation.

Read optional topics with agent_get_workflow: geometry, hair, variants, face, assets. Basic edits need no painting workflow. Use summaries and selected views; dense points are useful only for exact edits. Chain returned history heads; reconcile uncertain writes before retrying.

For longer work, keep a compact note of intent, useful candidates, working assumptions, affected IDs and evidence. Choose tests based on the change. A name change needs a state check; motion needs posed evidence. Each correction should address an identified defect. When there is no visible or measured benefit, keep the useful result and report remaining uncertainty instead of polishing indefinitely.
