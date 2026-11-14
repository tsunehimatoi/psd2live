use crate::eye_rig::{
    closed_line_grid, decode_eye_inputs, decode_eye_inputs_from, drawing_opacity,
    eye_artwork_fitting_fit, eye_fitting_fit, form_param, iris_grid, lid_grid, lid_shapes,
    mesh_of, opacity_grid, open_param, paired_lid_grid, pupil_shapes, smile_param, squint_param,
    Bounds, DecodedEyeInputs, EyeArtwork, EyeFit, EyeOptions, EyeRig, FittedEyeMotion, Side,
};
use crate::lowering;
use crate::output_id;
use crate::rig_ir::{
    decode_object_deformer, decode_object_frame, decode_object_mesh, encode_object_deformer,
    encode_object_frame, encode_object_mesh, grid_ref, objects_response, residual_channel_grid,
    set_channel, BlendBinding, ChannelValue, Deformer, GroupLabel, KeyCell, KeyGrid,
    LatticeShape, Mesh, MeshShape, ObjectCache, Parameter, ParameterLink, ParameterNode,
    PhysicsGroup, PhysicsInput, PhysicsNormalization, PhysicsOutput, PhysicsSegment, Rgb, RigIR,
    CHANNEL_DRAW_ORDER, CHANNEL_MULTIPLY_COLOR, CHANNEL_OPACITY, CHANNEL_SCREEN_COLOR,
};
use crate::space::{collect_deformer_branch, geometry_residual, DrawableSpaceMapping};
use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};

fn ok_objects(items: Vec<(String, Vec<u8>)>) -> Response {
    objects_response(items.into_iter().collect(), Vec::new(), Vec::new())
}

fn obj_get<'a>(entries: &'a [(String, Json)], key: &str) -> Option<&'a Json> {
    entries.iter().find(|(k, _)| k == key).map(|(_, v)| v)
}

fn rest_channels(
    mesh: &Mesh,
    parameters: &[Parameter],
    open_rest_is_one: bool,
) -> (f32, f32, Rgb, Rgb) {
    let defaults: HashMap<String, f32> = parameters
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();
    let order = crate::rig_ir::eval_scalar_channel(
        &mesh.channels,
        CHANNEL_DRAW_ORDER,
        mesh.draw_order,
        &defaults,
    );
    let rest: HashMap<String, f32> = if open_rest_is_one {
        [
            ("ParamEyeLOpen".to_string(), 1.0),
            ("ParamEyeROpen".to_string(), 1.0),
        ]
        .into_iter()
        .collect()
    } else {
        defaults
    };
    let opacity =
        crate::rig_ir::eval_scalar_channel(&mesh.channels, CHANNEL_OPACITY, mesh.opacity, &rest);
    let multiply = crate::rig_ir::eval_color_channel(
        &mesh.channels,
        CHANNEL_MULTIPLY_COLOR,
        &mesh.multiply,
        &rest,
    );
    let screen = crate::rig_ir::eval_color_channel(
        &mesh.channels,
        CHANNEL_SCREEN_COLOR,
        &mesh.screen,
        &rest,
    );
    (order, opacity, multiply, screen)
}

fn knob(values: &Json, id: &str, default: f32, min: f32, max: f32) -> Result<f32, String> {
    let val = values
        .get(id)
        .and_then(Json::as_f64)
        .map(|v| v as f32)
        .unwrap_or(default);
    if !val.is_finite() || val < min || val > max {
        return Err(format!("Invalid eye value: {id}"));
    }
    Ok(val / 100.0)
}

fn provider_matches_cap(cap: &Json, name: &str, alt: Option<&str>) -> bool {
    let c = cap.get("capability").and_then(Json::as_str);
    let t = cap.get("type").and_then(Json::as_str);
    c == Some(name) || alt.is_some_and(|a| t == Some(a) || c == Some(a))
}

fn provider_has_all_targets(cap: &Json, targets: &HashSet<&str>) -> bool {
    let cap_targets: HashSet<&str> = cap
        .get("targets")
        .and_then(Json::as_arr)
        .map(|arr| {
            arr.iter()
                .filter_map(|t| t.get("id").and_then(Json::as_str))
                .collect()
        })
        .unwrap_or_default();
    targets.iter().all(|t| cap_targets.contains(t))
}

fn provider_has_target(cap: &Json, target_id: &str) -> bool {
    cap.get("targets")
        .and_then(Json::as_arr)
        .is_some_and(|arr| {
            arr.iter()
                .any(|t| t.get("id").and_then(Json::as_str) == Some(target_id))
        })
}

fn preceding_for<'a>(
    upstream: &'a [Json],
    scaffold: &Json,
    targets: &HashSet<&str>,
) -> Vec<&'a Json> {
    upstream
        .iter()
        .filter(|p| {
            p.get("config").and_then(|c| c.get("scaffold")) == Some(scaffold)
                && p.get("targets")
                    .and_then(Json::as_arr)
                    .is_some_and(|arr| {
                        arr.iter()
                            .filter_map(|t| t.get("id").and_then(Json::as_str))
                            .any(|id| targets.contains(id))
                    })
        })
        .collect()
}

fn last_preceding<'a>(
    preceding: &[&'a Json],
    cap: &str,
    alt: Option<&str>,
    target_id: &str,
) -> Option<&'a Json> {
    preceding
        .iter()
        .copied()
        .rfind(|p| provider_matches_cap(p, cap, alt) && provider_has_target(p, target_id))
}

fn target_ids(req: &Request) -> Result<Vec<String>, String> {
    let mut out = Vec::new();
    for t in req.targets() {
        if t.get("kind").and_then(Json::as_str) != Some("mesh") {
            return Err("Eye targets must be meshes".into());
        }
        let id = t
            .get("id")
            .and_then(Json::as_str)
            .ok_or("Missing target id")?;
        out.push(id.to_string());
    }
    Ok(out)
}

fn original_positions(raw: &[f32], parent: Option<&str>, frame: Bounds) -> Vec<f32> {
    let w = frame.width().max(1e-4);
    let h = frame.height().max(1e-4);
    let mut out = Vec::with_capacity(raw.len());
    for i in 0..raw.len() {
        let v = raw[i];
        if parent.is_none() {
            out.push(v);
        } else if i % 2 == 0 {
            out.push((v - frame.left) / w);
        } else {
            out.push((v - frame.top) / h);
        }
    }
    out
}

fn approx_eq_slice(a: &[f32], b: &[f32], eps: f32) -> bool {
    a.len() == b.len() && a.iter().zip(b.iter()).all(|(&x, &y)| (x - y).abs() <= eps)
}

fn is_zero_offsets(offsets: &Option<KeyGrid<Vec<f32>>>) -> bool {
    match offsets {
        None => true,
        Some(g) => {
            g.axes.is_empty()
                && g.cells.len() == 1
                && g.cells[0].form.iter().all(|&v| v == 0.0)
        }
    }
}

fn fits_and_artwork(
    inputs: &DecodedEyeInputs,
) -> (HashMap<Side, EyeFit>, HashMap<Side, EyeArtwork>) {
    let raw_fits = eye_fitting_fit(inputs);
    let mut fits = HashMap::with_capacity(raw_fits.len());
    for (side, fit) in raw_fits {
        let moved = match inputs.moves.get(&side) {
            Some(m) => fit.moved(m),
            None => fit,
        };
        fits.insert(side, moved);
    }
    let artwork = if fits.is_empty() {
        HashMap::new()
    } else {
        eye_artwork_fitting_fit(inputs, &fits)
    };
    (fits, artwork)
}

fn build_rigs(
    fits: &HashMap<Side, EyeFit>,
    artwork: &HashMap<Side, EyeArtwork>,
    lower: f32,
    roundness: f32,
    squash: f32,
    options: &EyeOptions,
) -> HashMap<Side, EyeRig> {
    let mut out = HashMap::with_capacity(fits.len());
    for (&side, fit) in fits {
        let mut f = fit.clone();
        f.artwork = artwork.get(&side).cloned();
        out.insert(
            side,
            EyeRig::new(
                f,
                lower,
                roundness,
                squash,
                options.smile_arc,
                options.smile_push,
                options.wide_lift,
            ),
        );
    }
    out
}

fn append_to_parameter_group(
    nodes: &[ParameterNode],
    group_id: &str,
    additions: &[ParameterNode],
) -> (Vec<ParameterNode>, bool) {
    let mut found = false;
    let mut out = Vec::with_capacity(nodes.len());
    for node in nodes {
        match node {
            ParameterNode::Param(id) => out.push(ParameterNode::Param(id.clone())),
            ParameterNode::Group {
                id,
                name,
                open,
                children,
                label,
            } => {
                if id == group_id {
                    found = true;
                    let mut next_children = children.clone();
                    next_children.extend(additions.iter().cloned());
                    out.push(ParameterNode::Group {
                        id: id.clone(),
                        name: name.clone(),
                        open: *open,
                        children: next_children,
                        label: label.clone(),
                    });
                } else {
                    let (next_children, child_found) =
                        append_to_parameter_group(children, group_id, additions);
                    if child_found {
                        found = true;
                    }
                    out.push(ParameterNode::Group {
                        id: id.clone(),
                        name: name.clone(),
                        open: *open,
                        children: next_children,
                        label: label.clone(),
                    });
                }
            }
        }
    }
    (out, found)
}

fn append_folder_tree(
    frame: &mut RigIR,
    group_id: &str,
    group_name: &str,
    desired_order: &[String],
    added_ids: &HashSet<String>,
) {
    if added_ids.is_empty() {
        return;
    }
    let root: Vec<ParameterNode> = if frame.parameter_tree.is_empty() {
        frame
            .parameters
            .iter()
            .filter(|p| !added_ids.contains(&p.id))
            .map(|p| ParameterNode::Param(p.id.clone()))
            .collect()
    } else {
        frame.parameter_tree.clone()
    };
    let children_to_append: Vec<ParameterNode> = desired_order
        .iter()
        .filter(|id| added_ids.contains(*id))
        .map(|id| ParameterNode::Param(id.clone()))
        .collect();
    let (mut next, found) = append_to_parameter_group(&root, group_id, &children_to_append);
    if !found {
        next.push(ParameterNode::Group {
            id: group_id.to_string(),
            name: group_name.to_string(),
            open: true,
            children: desired_order
                .iter()
                .map(|id| ParameterNode::Param(id.clone()))
                .collect(),
            label: GroupLabel::None,
        });
    }
    frame.parameter_tree = next;
}

fn neutralized_shapes(
    shapes: Vec<BlendBinding<MeshShape>>,
    mesh: &Mesh,
    parameters: &[Parameter],
    open_rest_is_one: bool,
) -> Vec<BlendBinding<MeshShape>> {
    let mut defaults: HashMap<String, f32> = parameters
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();
    let order = crate::rig_ir::eval_scalar_channel(
        &mesh.channels,
        CHANNEL_DRAW_ORDER,
        mesh.draw_order,
        &defaults,
    );
    if open_rest_is_one {
        let rest: HashMap<String, f32> = [
            ("ParamEyeLOpen".to_string(), 1.0),
            ("ParamEyeROpen".to_string(), 1.0),
        ]
        .into_iter()
        .collect();
        let opacity =
            crate::rig_ir::eval_scalar_channel(&mesh.channels, CHANNEL_OPACITY, mesh.opacity, &rest);
        let multiply = crate::rig_ir::eval_color_channel(
            &mesh.channels,
            CHANNEL_MULTIPLY_COLOR,
            &mesh.multiply,
            &rest,
        );
        let screen = crate::rig_ir::eval_color_channel(
            &mesh.channels,
            CHANNEL_SCREEN_COLOR,
            &mesh.screen,
            &rest,
        );
        shapes
            .into_iter()
            .map(|b| BlendBinding {
                parameter: b.parameter,
                keys: b.keys,
                neutral_index: b.neutral_index,
                limits: b.limits,
                shapes: b
                    .shapes
                    .into_iter()
                    .map(|f| {
                        f.map(|s| MeshShape {
                            deltas: s.deltas,
                            draw_order: order,
                            opacity,
                            multiply: multiply.clone(),
                            screen: screen.clone(),
                        })
                    })
                    .collect(),
            })
            .collect()
    } else {
        let opacity = crate::rig_ir::eval_scalar_channel(
            &mesh.channels,
            CHANNEL_OPACITY,
            mesh.opacity,
            &defaults,
        );
        let multiply = crate::rig_ir::eval_color_channel(
            &mesh.channels,
            CHANNEL_MULTIPLY_COLOR,
            &mesh.multiply,
            &defaults,
        );
        let screen = crate::rig_ir::eval_color_channel(
            &mesh.channels,
            CHANNEL_SCREEN_COLOR,
            &mesh.screen,
            &defaults,
        );
        // keep defaults mutable-free warning quiet
        let _ = &mut defaults;
        shapes
            .into_iter()
            .map(|b| BlendBinding {
                parameter: b.parameter,
                keys: b.keys,
                neutral_index: b.neutral_index,
                limits: b.limits,
                shapes: b
                    .shapes
                    .into_iter()
                    .map(|f| {
                        f.map(|s| MeshShape {
                            deltas: s.deltas,
                            draw_order: order,
                            opacity,
                            multiply: multiply.clone(),
                            screen: screen.clone(),
                        })
                    })
                    .collect(),
            })
            .collect()
    }
}

fn shapes_match(a: &[BlendBinding<MeshShape>], b: &[BlendBinding<MeshShape>]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b.iter()).all(|(ba, bb)| {
        ba.parameter == bb.parameter
            && ba.keys == bb.keys
            && ba.neutral_index == bb.neutral_index
            && ba.limits == bb.limits
            && ba.shapes.len() == bb.shapes.len()
            && ba.shapes.iter().zip(bb.shapes.iter()).all(|(sa, sb)| match (sa, sb) {
                (None, None) => true,
                (Some(sa), Some(sb)) => {
                    approx_eq_slice(&sa.deltas, &sb.deltas, 1e-3)
                        && (sa.draw_order - sb.draw_order).abs() <= 1e-3
                        && (sa.opacity - sb.opacity).abs() <= 1e-3
                        && sa.multiply == sb.multiply
                        && sa.screen == sb.screen
                }
                _ => false,
            })
    })
}

fn expected_grid_matches(
    actual: &Option<KeyGrid<Vec<f32>>>,
    expected: &Option<KeyGrid<Vec<f32>>>,
) -> bool {
    if actual == expected {
        return true;
    }
    let Some(exp) = expected else {
        return false;
    };
    let Some(act) = actual else {
        return false;
    };
    exp.axes
        .iter()
        .all(|ea| act.axes.iter().any(|aa| aa.parameter == ea.parameter))
}

fn check_neutral_world(
    mesh: &Mesh,
    branch: &[Deformer],
    defaults: &HashMap<String, f32>,
    raw_positions: &[f32],
    inputs: &DecodedEyeInputs,
    target_id: &str,
) -> Result<DrawableSpaceMapping, String> {
    let mapping = DrawableSpaceMapping::for_mesh(mesh, branch, defaults, defaults)
        .ok_or_else(|| format!("Missing drawable space mapping: {target_id}"))?;
    let geom = mesh
        .geometry
        .as_ref()
        .ok_or_else(|| format!("Missing mesh geometry: {target_id}"))?;
    let world = mapping.local_to_world(&geom.positions);
    for i in (0..world.len()).step_by(2) {
        let x = raw_positions[i];
        let y = raw_positions[i + 1];
        let (cx, cy) = match &inputs.alignment {
            Some(a) => a.canvas(x, y),
            None => (x, y),
        };
        if (world[i] - cx).abs() > 0.5 || (-world[i + 1] - cy).abs() > 0.5 {
            return Err(format!("Cannot migrate edited neutral lid frame: {target_id}"));
        }
    }
    Ok(mapping)
}

fn compute_lid_shapes(
    lid: crate::eye_rig::EyeMesh,
    side: Side,
    layer_id: &str,
    positions: &[f32],
    frame: Bounds,
    motion: &FittedEyeMotion,
    options: &EyeOptions,
    rest: (f32, f32, &Rgb, &Rgb),
) -> Vec<BlendBinding<MeshShape>> {
    let (rest_order, rest_opacity, rest_mul, rest_scr) = rest;
    if side == Side::NONE {
        let n = positions.len() / 2;
        let owner: Vec<Option<Side>> = (0..n)
            .map(|i| motion.side_at(layer_id, positions[i * 2], positions[i * 2 + 1]))
            .collect();
        let mut sides: Vec<Side> = motion.rigs.keys().copied().collect();
        sides.sort();
        let mut out = Vec::new();
        for eye_side in sides {
            let eye = &motion.rigs[&eye_side];
            for mut shape in lid_shapes(
                lid,
                eye_side,
                positions,
                frame,
                eye,
                options,
                Some(layer_id),
                rest_order,
                rest_opacity,
                rest_mul.clone(),
                rest_scr.clone(),
            ) {
                for f in &mut shape.shapes {
                    if let Some(s) = f {
                        for i in 0..s.deltas.len() {
                            if owner[i / 2] != Some(eye_side) {
                                s.deltas[i] = 0.0;
                            }
                        }
                    }
                }
                out.push(shape);
            }
        }
        out
    } else if let Some(eye) = motion.rigs.get(&side) {
        lid_shapes(
            lid,
            side,
            positions,
            frame,
            eye,
            options,
            Some(layer_id),
            rest_order,
            rest_opacity,
            rest_mul.clone(),
            rest_scr.clone(),
        )
    } else {
        Vec::new()
    }
}

fn compute_lid_or_paired_grid(
    lid: crate::eye_rig::EyeMesh,
    side: Side,
    layer_id: &str,
    positions: &[f32],
    frame: Bounds,
    motion: &FittedEyeMotion,
) -> Option<KeyGrid<Vec<f32>>> {
    if side == Side::NONE {
        paired_lid_grid(lid, layer_id, positions, frame, motion)
    } else {
        motion.rigs.get(&side).map(|eye| {
            lid_grid(
                lid,
                side,
                positions,
                frame,
                eye,
                &motion.options,
                Some(layer_id),
            )
        })
    }
}

fn eye_wide_geometry(
    layer: &crate::eye_rig::SourceLayer,
    material: &crate::eye_rig::EyeTargetMaterial,
    positions: &[f32],
    motion: &FittedEyeMotion,
    options: &EyeOptions,
    inputs: &DecodedEyeInputs,
) -> Option<KeyGrid<Vec<f32>>> {
    let side = Side::from_str(&layer.side).ok()?;
    let frame = material.frame;
    let eye = motion.rigs.get(&side);
    let lid = mesh_of(&layer.tag, layer.role.as_deref());
    if let Some(lid) = lid {
        if side == Side::NONE {
            return paired_lid_grid(lid, &layer.id, positions, frame, motion);
        } else if let Some(eye) = eye {
            return Some(lid_grid(
                lid,
                side,
                positions,
                frame,
                eye,
                options,
                Some(&layer.id),
            ));
        }
    }
    if layer.tag == "IRIDES" && options.squash {
        if let Some(eye) = eye {
            let mut bottom = f32::NEG_INFINITY;
            for i in (1..positions.len()).step_by(2) {
                if positions[i] > bottom {
                    bottom = positions[i];
                }
            }
            return iris_grid(
                side,
                positions,
                frame,
                bottom,
                eye,
                options,
                material.centroid_x,
                material.centroid_y,
                inputs.jelly_squash,
                inputs.jelly_stretch,
            );
        }
    }
    None
}

fn eye_wide_opacity(
    layer: &crate::eye_rig::SourceLayer,
    material: &crate::eye_rig::EyeTargetMaterial,
    rigs: &HashMap<Side, EyeRig>,
    options: &EyeOptions,
) -> Option<KeyGrid<ChannelValue>> {
    if !material.automatic_opacity {
        return None;
    }
    let side = Side::from_str(&layer.side).ok()?;
    let eye = rigs.get(&side);
    let swap = eye.is_some_and(|e| e.fit.closed.is_some());
    let max = if options.wide { 2.0 } else { 1.0 };
    let drawing = if material.preset && layer.tag == "EYE_CLOSE" {
        Some(drawing_opacity(side, true, swap, max))
    } else if material.preset
        && layer.tag == "EYELASH"
        && swap
        && (layer.role.is_none() || layer.role.as_deref() == Some("LOWER"))
    {
        Some(drawing_opacity(side, false, true, max))
    } else {
        None
    };
    drawing.or_else(|| eye.and_then(|e| opacity_grid(&layer.tag, layer.role.as_deref(), side, e, options)))
}

fn run_geometry_residual(
    mesh: &Mesh,
    material_parent: Option<&str>,
    material_frame: Bounds,
    raw_geom: &crate::rig_ir::MeshGeometry,
    before: &Option<KeyGrid<Vec<f32>>>,
    after: &KeyGrid<Vec<f32>>,
    cache: &ObjectCache,
    live_defaults: &HashMap<String, f32>,
) -> Result<Option<KeyGrid<Vec<f32>>>, String> {
    let geom = mesh
        .geometry
        .as_ref()
        .ok_or("Missing mesh geometry")?;
    if geom.uvs != raw_geom.uvs || geom.indices != raw_geom.indices {
        return Err("Requires captured eye topology".into());
    }
    if mesh.parent.as_deref() != material_parent {
        return Err("Requires fitted eye's parent frame".into());
    }
    let original = original_positions(&raw_geom.positions, material_parent, material_frame);
    let unchanged = if let Some(b) = before {
        mesh.offsets.as_ref() == Some(b)
    } else {
        is_zero_offsets(&mesh.offsets)
    };
    if geom.positions == original && unchanged {
        return Ok(Some(after.clone()));
    }
    let branch = collect_deformer_branch(mesh.parent.as_deref(), cache)?;
    let mut canonical = mesh.clone();
    canonical.geometry = Some(crate::rig_ir::MeshGeometry {
        positions: original,
        uvs: geom.uvs.clone(),
        indices: geom.indices.clone(),
    });
    canonical.offsets = None;
    canonical.shapes = Vec::new();
    canonical.channels = Vec::new();
    let empty_grid = KeyGrid {
        axes: Vec::new(),
        cells: vec![KeyCell {
            coordinate: Vec::new(),
            form: vec![0.0; raw_geom.positions.len()],
        }],
    };
    let before_grid = before.as_ref().unwrap_or(&empty_grid);
    geometry_residual(
        mesh,
        &branch,
        live_defaults,
        &canonical,
        &branch,
        before_grid,
        after,
    )
}

pub fn handle_eye_wide(req: &Request, cache: &ObjectCache) -> Response {
    match eye_wide_impl(req, cache) {
        Ok(r) => r,
        Err(e) => Response::Error(e),
    }
}

fn eye_wide_impl(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    if req.header.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Unsupported eye.wide version".into());
    }
    let targets = target_ids(req)?;
    let target_set: HashSet<&str> = targets.iter().map(String::as_str).collect();
    let config = req.config();
    let scaffold = config.get("scaffold").ok_or("Missing scaffold")?;
    let scaffold_id = scaffold.as_str().ok_or("Invalid scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Missing base")?;
    let inputs = decode_eye_inputs(req, false)?;
    for tid in inputs.targets.keys() {
        if !target_set.contains(tid.as_str()) {
            return Err("Target mismatch in eye.wide inputs".into());
        }
    }
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    if !upstream.iter().any(|p| {
        provider_matches_cap(p, "rig.scaffold", None)
            && p.get("instanceId").and_then(Json::as_str) == Some(scaffold_id)
            && provider_has_all_targets(p, &target_set)
    }) {
        return Err("Missing eye widening scaffold provider".into());
    }
    let fitted = upstream
        .iter()
        .find(|p| {
            provider_matches_cap(p, "eye/fitted", None)
                && p.get("instanceId").and_then(Json::as_str) == Some(base_id)
                && provider_has_all_targets(p, &target_set)
        })
        .ok_or("Missing fitted eye provider")?;
    let preceding = preceding_for(upstream, scaffold, &target_set);
    if preceding
        .iter()
        .any(|p| provider_matches_cap(p, "eye.wide", Some("psd2live.native.core/eye-wide")))
    {
        return Err("Eye widening v2 cannot replace an earlier widening on the same mesh".into());
    }
    let coupled = [
        ("eye.gaze-lid", Some("psd2live.native.core/eye-gaze-lid")),
        ("eye.highlight", None),
        ("brow.blink", Some("psd2live.native.core/brow-blink")),
        ("brow.lid-coupling", Some("psd2live.native.core/brow-lid-coupling")),
        ("mouth.cheek-lid", None),
    ];
    if preceding
        .iter()
        .any(|p| coupled.iter().any(|(c, a)| provider_matches_cap(p, c, *a)))
    {
        return Err("Eye widening v2 cannot lower coupled lid, highlight or brow bindings".into());
    }
    let fitted_values = fitted
        .get("config")
        .and_then(|c| c.get("values"))
        .unwrap_or(&Json::Null);
    let wide_values = config.get("values").unwrap_or(&Json::Null);
    let lift = knob(wide_values, "lift", 10.0, 0.0, 80.0)?;
    let lower = knob(fitted_values, "lowerShare", 5.0, 0.0, 50.0)?;
    let roundness = knob(fitted_values, "roundness", 100.0, 40.0, 160.0)?;
    let squash = knob(fitted_values, "lashSquash", 60.0, 20.0, 100.0)?;

    let (fits, artwork) = fits_and_artwork(&inputs);
    if fits.is_empty()
        || inputs
            .layers
            .iter()
            .all(|l| l.tag != "EYELASH" || l.opaque_pixels <= 0)
    {
        return Ok(ok_objects(Vec::new()));
    }

    let frame_bytes = cache.get("frame")?;
    let mut frame = decode_object_frame(frame_bytes).map_err(|e| e.to_string())?;
    let openings = [open_param(Side::LEFT), open_param(Side::RIGHT)];
    let mut frame_changed = false;
    for pid in openings {
        let p = frame
            .parameters
            .iter_mut()
            .find(|p| p.id == pid)
            .ok_or_else(|| format!("Missing fitted eye opening parameter: {pid}"))?;
        if p.min != 0.0 || p.default != 1.0 || p.max < 1.0 || p.blend || p.repeat || p.keys.is_some()
        {
            return Err(format!("Incompatible eye opening parameter: {pid}"));
        }
        if p.max < 2.0 {
            p.max = 2.0;
            frame_changed = true;
        }
    }
    let live_defaults: HashMap<String, f32> = frame
        .parameters
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();

    let mut out_objects: Vec<(String, Vec<u8>)> = Vec::new();
    if frame_changed {
        out_objects.push(("frame".into(), encode_object_frame(&frame)));
    }

    let mut rigs_cache: HashMap<(u32, u32), FittedEyeMotion> = HashMap::new();
    for tid in &targets {
        let Some(material) = inputs.targets.get(tid) else {
            continue;
        };
        let smile_prov = last_preceding(
            &preceding,
            "eye.smile",
            Some("psd2live.native.core/eye-smile"),
            tid,
        );
        let smile_vals = smile_prov
            .and_then(|p| p.get("config"))
            .and_then(|c| c.get("values"))
            .unwrap_or(&Json::Null);
        let has_lid_fade = last_preceding(
            &preceding,
            "eye.lid-fade",
            Some("psd2live.native.core/eye-lid-fade"),
            tid,
        )
        .is_some();
        let has_squash = last_preceding(
            &preceding,
            "eye.squash",
            Some("psd2live.native.core/eye-squash"),
            tid,
        )
        .is_some();
        let smile_arc = 0.12 * knob(smile_vals, "arch", 100.0, 0.0, 200.0)?;
        let smile_push = knob(smile_vals, "push", 50.0, 0.0, 100.0)?;
        let mut prior = EyeOptions::defaults();
        prior.smile = smile_prov.is_some();
        prior.smile_arc = smile_arc;
        prior.smile_push = smile_push;
        prior.lid_fade = has_lid_fade;
        prior.squash = has_squash;
        let mut widened = prior;
        widened.wide = true;
        widened.wide_lift = lift;

        let motion = rigs_cache
            .entry((smile_arc.to_bits(), smile_push.to_bits()))
            .or_insert_with(|| FittedEyeMotion {
                rigs: build_rigs(&fits, &artwork, lower, roundness, squash, &prior),
                options: prior,
            });

        let layer = inputs.layer_for_target(tid)?;
        let raw = inputs
            .topology
            .get(&material.layer)
            .ok_or_else(|| format!("Missing topology for {}", material.layer))?;
        let mut mesh = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;

        let before = eye_wide_geometry(layer, material, &raw.positions, motion, &prior, &inputs);
        let after = eye_wide_geometry(layer, material, &raw.positions, motion, &widened, &inputs);
        let opacity_before = eye_wide_opacity(layer, material, &motion.rigs, &prior);
        let opacity_after = eye_wide_opacity(layer, material, &motion.rigs, &widened);
        if before == after && opacity_before == opacity_after {
            continue;
        }

        let has_head_turn = last_preceding(&preceding, "face/fitted", None, tid).is_some()
            || last_preceding(&preceding, "face.depth-turn", None, tid).is_some();
        if has_head_turn {
            let has_head_axis = mesh
                .offsets
                .as_ref()
                .is_some_and(|g| {
                    g.axes
                        .iter()
                        .any(|a| matches!(a.parameter.as_str(), "ParamAngleX" | "ParamAngleY" | "ParamAngleZ"))
                })
                || mesh
                    .shapes
                    .iter()
                    .any(|b| matches!(b.parameter.as_str(), "ParamAngleX" | "ParamAngleY" | "ParamAngleZ"));
            if mesh.parent.as_deref() != material.parent.as_deref() || has_head_axis {
                return Err("Eye widening v2 cannot lower fitted head-turn carriers".into());
            }
        }

        let next_offsets = if before == after {
            mesh.offsets.clone()
        } else {
            let after_grid = after.as_ref().ok_or("Missing widened geometry")?;
            run_geometry_residual(
                &mesh,
                material.parent.as_deref(),
                material.frame,
                raw,
                &before,
                after_grid,
                cache,
                &live_defaults,
            )?
        };

        let mut next_channels = mesh.channels.clone();
        if opacity_before != opacity_after {
            let after_op = opacity_after.as_ref().ok_or("Missing widened opacity")?;
            let actual = grid_ref(&mesh.channels, CHANNEL_OPACITY).cloned();
            let merged_op = if actual == opacity_before && (mesh.opacity - material.opacity).abs() <= 1e-6 {
                after_op.clone()
            } else {
                residual_channel_grid(
                    &live_defaults,
                    actual.as_ref(),
                    &ChannelValue::Scalar(mesh.opacity),
                    opacity_before.as_ref(),
                    &ChannelValue::Scalar(material.opacity),
                    Some(after_op),
                    &ChannelValue::Scalar(material.opacity),
                )
            };
            set_channel(&mut next_channels, CHANNEL_OPACITY, Some(merged_op));
        }

        if next_offsets != mesh.offsets || next_channels != mesh.channels {
            mesh.offsets = next_offsets;
            mesh.channels = next_channels;
            out_objects.push((format!("mesh:{tid}"), encode_object_mesh(&mesh)));
        }
    }

    Ok(ok_objects(out_objects))
}

pub fn handle_eye_smile(req: &Request, cache: &ObjectCache) -> Response {
    match eye_smile_impl(req, cache) {
        Ok(r) => r,
        Err(e) => Response::Error(e),
    }
}

fn eye_smile_impl(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    if req.header.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Unsupported eye.smile version".into());
    }
    let targets = target_ids(req)?;
    let target_set: HashSet<&str> = targets.iter().map(String::as_str).collect();
    let config = req.config();
    let scaffold = config.get("scaffold").ok_or("Missing scaffold")?;
    let scaffold_id = scaffold.as_str().ok_or("Invalid scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Missing base")?;
    let inputs = decode_eye_inputs(req, true)?;
    for tid in inputs.targets.keys() {
        if !target_set.contains(tid.as_str()) {
            return Err("Target mismatch in eye.smile inputs".into());
        }
    }
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    if !upstream.iter().any(|p| {
        provider_matches_cap(p, "rig.scaffold", None)
            && p.get("instanceId").and_then(Json::as_str) == Some(scaffold_id)
            && p.get("version").and_then(Json::as_i64) == Some(2)
            && provider_has_all_targets(p, &target_set)
    }) {
        return Err("Eye smile v2 requires a version 2 source scaffold".into());
    }
    let fitted = upstream
        .iter()
        .find(|p| {
            provider_matches_cap(p, "eye/fitted", None)
                && p.get("instanceId").and_then(Json::as_str) == Some(base_id)
                && provider_has_all_targets(p, &target_set)
        })
        .ok_or("Missing fitted eye provider")?;
    let fv = fitted.get("version").and_then(Json::as_i64).unwrap_or(0);
    if !(1..=4).contains(&fv) {
        return Err("Eye smile v2 requires fitted eyes v1..v4".into());
    }
    let preceding = preceding_for(upstream, scaffold, &target_set);
    if preceding
        .iter()
        .any(|p| provider_matches_cap(p, "eye.smile", Some("psd2live.native.core/eye-smile")))
    {
        return Err("Eye smile v2 cannot replace an earlier smile on the same mesh".into());
    }
    let coupled = [
        ("eye.gaze-lid", Some("psd2live.native.core/eye-gaze-lid")),
        ("eye.emotion", Some("psd2live.native.core/eye-emotion")),
        ("eye.aftershock", Some("psd2live.native.core/eye-aftershock")),
        ("brow.blink", Some("psd2live.native.core/brow-blink")),
        ("brow.lid-coupling", Some("psd2live.native.core/brow-lid-coupling")),
        ("mouth.cheek-lid", None),
    ];
    if preceding
        .iter()
        .any(|p| coupled.iter().any(|(c, a)| provider_matches_cap(p, c, *a)))
    {
        return Err("Eye smile v2 cannot lower preceding coupled lid or brow bindings".into());
    }

    let fitted_values = fitted
        .get("config")
        .and_then(|c| c.get("values"))
        .unwrap_or(&Json::Null);
    let lower = knob(fitted_values, "lowerShare", 5.0, 0.0, 50.0)?;
    let roundness = knob(fitted_values, "roundness", 100.0, 40.0, 160.0)?;
    let lash_squash = knob(fitted_values, "lashSquash", 60.0, 20.0, 100.0)?;
    let smile_values = config.get("values").unwrap_or(&Json::Null);
    let arch = 0.12 * knob(smile_values, "arch", 100.0, 0.0, 200.0)?;
    let push = knob(smile_values, "push", 50.0, 0.0, 100.0)?;

    let (fits, artwork) = fits_and_artwork(&inputs);
    if fits.is_empty() || inputs.layers.iter().all(|l| l.tag != "EYELASH") {
        return Ok(ok_objects(Vec::new()));
    }

    let declared: Vec<Parameter> = [Side::LEFT, Side::RIGHT]
        .into_iter()
        .map(|s| {
            let id = smile_param(s).to_string();
            let name = inputs
                .parameter_names
                .get(&id)
                .cloned()
                .ok_or_else(|| format!("Missing parameter name for {id}"))?;
            Ok(Parameter {
                id,
                name,
                min: 0.0,
                max: 1.0,
                default: 0.0,
                blend: false,
                repeat: false,
                keys: None,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;

    let frame_bytes = cache.get("frame")?;
    let mut frame = decode_object_frame(frame_bytes).map_err(|e| e.to_string())?;
    let mut added_params = Vec::new();
    let mut added_ids = HashSet::new();
    for p in &declared {
        if let Some(existing) = frame.parameters.iter().find(|e| e.id == p.id) {
            let mut cmp = existing.clone();
            cmp.name = p.name.clone();
            if &cmp != p {
                return Err(format!("Incompatible eye smile parameter: {}", p.id));
            }
        } else {
            added_params.push(p.clone());
            added_ids.insert(p.id.clone());
        }
    }
    frame.parameters.extend(added_params.iter().cloned());
    let live_defaults: HashMap<String, f32> = frame
        .parameters
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();

    let mut out_objects: Vec<(String, Vec<u8>)> = Vec::new();
    let mut motions: HashMap<(bool, u32, bool, u32, u32), FittedEyeMotion> = HashMap::new();

    for tid in &targets {
        let Some(material) = inputs.targets.get(tid) else {
            continue;
        };
        let layer = inputs.layer_for_target(tid)?;
        let side = Side::from_str(&layer.side)?;
        let wide_prov = last_preceding(
            &preceding,
            "eye.wide",
            Some("psd2live.native.core/eye-wide"),
            tid,
        );
        let wide_vals = wide_prov
            .and_then(|p| p.get("config"))
            .and_then(|c| c.get("values"))
            .unwrap_or(&Json::Null);
        let mut opts = EyeOptions::defaults();
        opts.wide = wide_prov.is_some();
        opts.wide_lift = knob(wide_vals, "lift", 10.0, 0.0, 80.0)?;
        let mut smile_opts = opts;
        smile_opts.smile = true;
        smile_opts.smile_arc = arch;
        smile_opts.smile_push = push;

        let raw = inputs
            .topology
            .get(&material.layer)
            .ok_or_else(|| format!("Missing topology for {}", material.layer))?;
        let lid = mesh_of(&layer.tag, layer.role.as_deref());

        let mut compute_grid = |o: EyeOptions| -> Option<KeyGrid<Vec<f32>>> {
            let key = (
                o.wide,
                o.wide_lift.to_bits(),
                o.smile,
                o.smile_arc.to_bits(),
                o.smile_push.to_bits(),
            );
            let m = motions.entry(key).or_insert_with(|| FittedEyeMotion {
                rigs: build_rigs(&fits, &artwork, lower, roundness, lash_squash, &o),
                options: o,
            });
            if let Some(lid) = lid {
                compute_lid_or_paired_grid(lid, side, &layer.id, &raw.positions, material.frame, m)
            } else if layer.tag == "EYE_CLOSE" {
                m.rigs
                    .get(&side)
                    .and_then(|eye| closed_line_grid(side, &raw.positions, material.frame, eye, &m.options))
            } else {
                None
            }
        };

        let after_opt = compute_grid(smile_opts);
        let Some(after_grid) = after_opt else {
            continue;
        };
        let before_grid = compute_grid(opts);

        let mut mesh = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        if mesh.parent.as_deref() != material.parent.as_deref() {
            return Err(format!("Eye smile v2 cannot migrate a fitted lid carrier: {tid}"));
        }
        let geom = mesh
            .geometry
            .as_ref()
            .ok_or_else(|| format!("Missing mesh geometry: {tid}"))?;
        if geom.uvs != raw.uvs || geom.indices != raw.indices {
            return Err(format!("Eye smile v2 requires the captured lid topology: {tid}"));
        }
        let original = original_positions(&raw.positions, material.parent.as_deref(), material.frame);
        let unchanged = if let Some(b) = &before_grid {
            mesh.offsets.as_ref() == Some(b)
        } else {
            is_zero_offsets(&mesh.offsets)
        };
        let next_offsets = if geom.positions == original && unchanged && mesh.shapes.is_empty() {
            Some(after_grid)
        } else {
            run_geometry_residual(
                &mesh,
                material.parent.as_deref(),
                material.frame,
                raw,
                &before_grid,
                &after_grid,
                cache,
                &live_defaults,
            )?
        };
        if next_offsets != mesh.offsets {
            mesh.offsets = next_offsets;
            out_objects.push((format!("mesh:{tid}"), encode_object_mesh(&mesh)));
        }
    }

    if !added_ids.is_empty() {
        let folder_name = inputs
            .folder_name
            .clone()
            .ok_or("Missing folderName in eye smile inputs")?;
        let desired_order: Vec<String> = [
            "ParamEyeLOpen",
            "ParamEyeROpen",
            "ParamEyeBallX",
            "ParamEyeBallY",
            "ParamEyeBallForm",
        ]
        .into_iter()
        .map(String::from)
        .chain(declared.iter().map(|p| p.id.clone()))
        .collect();
        let group_id = output_id(req, "parameter-folder:ParamGroupEyes");
        append_folder_tree(&mut frame, &group_id, &folder_name, &desired_order, &added_ids);
        out_objects.push(("frame".into(), encode_object_frame(&frame)));
    }

    Ok(ok_objects(out_objects))
}

pub fn handle_eye_emotion(req: &Request, cache: &ObjectCache) -> Response {
    match eye_emotion_impl(req, cache) {
        Ok(r) => r,
        Err(e) => Response::Error(e),
    }
}

fn eye_emotion_impl(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    if req.header.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Unsupported eye.emotion version".into());
    }
    let targets = target_ids(req)?;
    let target_set: HashSet<&str> = targets.iter().map(String::as_str).collect();
    let config = req.config();
    let scaffold = config.get("scaffold").ok_or("Missing scaffold")?;
    let scaffold_id = scaffold.as_str().ok_or("Invalid scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Missing base")?;
    let inputs = decode_eye_inputs(req, true)?;
    for tid in inputs.targets.keys() {
        if !target_set.contains(tid.as_str()) {
            return Err("Target mismatch in eye.emotion inputs".into());
        }
    }
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    if !upstream.iter().any(|p| {
        provider_matches_cap(p, "rig.scaffold", None)
            && p.get("instanceId").and_then(Json::as_str) == Some(scaffold_id)
            && p.get("version").and_then(Json::as_i64) == Some(2)
            && provider_has_all_targets(p, &target_set)
    }) {
        return Err("Eye emotion v2 requires a version 2 source scaffold".into());
    }
    let fitted = upstream
        .iter()
        .find(|p| {
            provider_matches_cap(p, "eye/fitted", None)
                && p.get("instanceId").and_then(Json::as_str) == Some(base_id)
                && provider_has_all_targets(p, &target_set)
        })
        .ok_or("Missing fitted eye provider")?;
    let fv = fitted.get("version").and_then(Json::as_i64).unwrap_or(0);
    if !(1..=4).contains(&fv) {
        return Err("Eye emotion v2 requires fitted eyes v1..v4".into());
    }
    let preceding = preceding_for(upstream, scaffold, &target_set);
    if preceding
        .iter()
        .any(|p| provider_matches_cap(p, "eye.emotion", Some("psd2live.native.core/eye-emotion")))
    {
        return Err("Eye emotion v2 cannot replace an earlier emotion on the same mesh".into());
    }
    let coupled = [
        ("eye.gaze-lid", Some("psd2live.native.core/eye-gaze-lid")),
        ("brow.lid-coupling", Some("psd2live.native.core/brow-lid-coupling")),
        ("mouth.cheek-lid", None),
    ];
    if preceding
        .iter()
        .any(|p| coupled.iter().any(|(c, a)| provider_matches_cap(p, c, *a)))
    {
        return Err("Eye emotion v2 cannot compose with preceding coupled lid bindings".into());
    }

    let (fits, artwork) = fits_and_artwork(&inputs);
    if fits.is_empty() || inputs.layers.iter().all(|l| l.tag != "EYELASH") {
        return Ok(ok_objects(Vec::new()));
    }
    let fitted_values = fitted
        .get("config")
        .and_then(|c| c.get("values"))
        .unwrap_or(&Json::Null);
    let lower = knob(fitted_values, "lowerShare", 5.0, 0.0, 50.0)?;
    let roundness = knob(fitted_values, "roundness", 100.0, 40.0, 160.0)?;
    let squash = knob(fitted_values, "lashSquash", 60.0, 20.0, 100.0)?;
    let strength = knob(config.get("values").unwrap_or(&Json::Null), "strength", 100.0, 0.0, 200.0)?;

    let mut declared = Vec::with_capacity(4);
    for (kind, min) in [("Form", -1.0f32), ("Squint", 0.0f32)] {
        for side in [Side::LEFT, Side::RIGHT] {
            let id = if kind == "Form" {
                form_param(side).to_string()
            } else {
                squint_param(side).to_string()
            };
            let name = inputs
                .parameter_names
                .get(&id)
                .cloned()
                .ok_or_else(|| format!("Missing parameter name for {id}"))?;
            let blend = fits.contains_key(&side);
            let keys = if !blend {
                None
            } else if min < 0.0 {
                Some(vec![min, 0.0, 1.0])
            } else {
                Some(vec![0.0, 1.0])
            };
            declared.push(Parameter {
                id,
                name,
                min,
                max: 1.0,
                default: 0.0,
                blend,
                repeat: false,
                keys,
            });
        }
    }

    let frame_bytes = cache.get("frame")?;
    let mut frame = decode_object_frame(frame_bytes).map_err(|e| e.to_string())?;
    let live_params = frame.parameters.clone();
    let live_defaults: HashMap<String, f32> = live_params
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();
    let mut added_params = Vec::new();
    let mut added_ids = HashSet::new();
    for p in &declared {
        if let Some(existing) = frame.parameters.iter().find(|e| e.id == p.id) {
            let mut cmp = existing.clone();
            cmp.name = p.name.clone();
            if &cmp != p {
                return Err(format!("Incompatible eye emotion parameter: {}", p.id));
            }
        } else {
            added_params.push(p.clone());
            added_ids.insert(p.id.clone());
        }
    }

    let mut out_objects: Vec<(String, Vec<u8>)> = Vec::new();
    for tid in &targets {
        let Some(material) = inputs.targets.get(tid) else {
            continue;
        };
        let layer = inputs.layer_for_target(tid)?;
        let Some(lid) = mesh_of(&layer.tag, layer.role.as_deref()) else {
            continue;
        };
        let raw = inputs
            .topology
            .get(&material.layer)
            .ok_or_else(|| format!("Missing topology for {}", material.layer))?;
        let side = Side::from_str(&layer.side)?;
        let wide_prov = last_preceding(
            &preceding,
            "eye.wide",
            Some("psd2live.native.core/eye-wide"),
            tid,
        );
        let smile_prov = last_preceding(
            &preceding,
            "eye.smile",
            Some("psd2live.native.core/eye-smile"),
            tid,
        );
        let aftershock_prov = last_preceding(
            &preceding,
            "eye.aftershock",
            Some("psd2live.native.core/eye-aftershock"),
            tid,
        );
        let wide_vals = wide_prov
            .and_then(|p| p.get("config"))
            .and_then(|c| c.get("values"))
            .unwrap_or(&Json::Null);
        let smile_vals = smile_prov
            .and_then(|p| p.get("config"))
            .and_then(|c| c.get("values"))
            .unwrap_or(&Json::Null);
        let aftershock_vals = aftershock_prov
            .and_then(|p| p.get("config"))
            .and_then(|c| c.get("values"))
            .unwrap_or(&Json::Null);

        let mut lid_opts = EyeOptions::defaults();
        lid_opts.emotion = true;
        lid_opts.emotion_strength = strength;
        lid_opts.aftershock = aftershock_prov.is_some();
        lid_opts.softness = knob(aftershock_vals, "softness", 100.0, 0.0, 200.0)?;
        lid_opts.wide = wide_prov.is_some();
        lid_opts.wide_lift = knob(wide_vals, "lift", 10.0, 0.0, 80.0)?;
        lid_opts.smile = smile_prov.is_some();
        lid_opts.smile_arc = 0.12 * knob(smile_vals, "arch", 100.0, 0.0, 200.0)?;
        lid_opts.smile_push = knob(smile_vals, "push", 50.0, 0.0, 100.0)?;

        let motion = FittedEyeMotion {
            rigs: build_rigs(&fits, &artwork, lower, roundness, squash, &lid_opts),
            options: lid_opts,
        };
        let mut mesh = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let (r_ord, r_op, r_mul, r_scr) = rest_channels(&mesh, &live_params, true);
        let bindings = compute_lid_shapes(
            lid,
            side,
            &layer.id,
            &raw.positions,
            material.frame,
            &motion,
            &lid_opts,
            (r_ord, r_op, &r_mul, &r_scr),
        );
        if bindings.is_empty() {
            continue;
        }
        if mesh.parent.as_deref() != material.parent.as_deref() {
            return Err(format!("Eye emotion v2 cannot migrate a fitted lid carrier: {tid}"));
        }
        let geom = mesh
            .geometry
            .as_ref()
            .ok_or_else(|| format!("Missing mesh geometry: {tid}"))?;
        let original = original_positions(&raw.positions, material.parent.as_deref(), material.frame);
        if !approx_eq_slice(&geom.positions, &original, 1e-4)
            || geom.uvs != raw.uvs
            || geom.indices != raw.indices
        {
            return Err(format!(
                "Eye emotion v2 requires the captured lid topology and positions: {tid}"
            ));
        }
        let expected = compute_lid_or_paired_grid(
            lid,
            side,
            &layer.id,
            &raw.positions,
            material.frame,
            &motion,
        );
        if !expected_grid_matches(&mesh.offsets, &expected) {
            return Err(format!(
                "Eye emotion v2 cannot migrate edited or lowered lid geometry: {tid}"
            ));
        }

        let branch = collect_deformer_branch(mesh.parent.as_deref(), cache)?;
        check_neutral_world(&mesh, &branch, &live_defaults, &raw.positions, &inputs, tid)?;

        let mut no_emo_opts = lid_opts;
        no_emo_opts.emotion = false;
        let prev_shapes = compute_lid_shapes(
            lid,
            side,
            &layer.id,
            &raw.positions,
            material.frame,
            &motion,
            &no_emo_opts,
            (r_ord, r_op, &r_mul, &r_scr),
        );
        if !shapes_match(&prev_shapes, &mesh.shapes) {
            return Err(format!(
                "Eye emotion v2 cannot migrate edited or lowered lid shapes: {tid}"
            ));
        }

        mesh.shapes = bindings;
        out_objects.push((format!("mesh:{tid}"), encode_object_mesh(&mesh)));
    }

    if !added_ids.is_empty() {
        frame.parameters.extend(added_params);
        let folder_name = inputs
            .folder_name
            .clone()
            .ok_or("Missing folderName in eye emotion inputs")?;
        let desired_order: Vec<String> = [
            "ParamEyeLOpen",
            "ParamEyeROpen",
            "ParamEyeBallX",
            "ParamEyeBallY",
            "ParamEyeBallForm",
            "ParamEyeLSmile",
            "ParamEyeRSmile",
        ]
        .into_iter()
        .map(String::from)
        .chain(declared.iter().map(|p| p.id.clone()))
        .collect();
        let group_id = output_id(req, "parameter-folder:ParamGroupEyes");
        append_folder_tree(&mut frame, &group_id, &folder_name, &desired_order, &added_ids);
        out_objects.push(("frame".into(), encode_object_frame(&frame)));
    }

    Ok(ok_objects(out_objects))
}

pub fn handle_eye_aftershock(req: &Request, cache: &ObjectCache) -> Response {
    match eye_aftershock_impl(req, cache) {
        Ok(r) => r,
        Err(e) => Response::Error(e),
    }
}

fn eye_aftershock_impl(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    if req.header.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Unsupported eye.aftershock version".into());
    }
    let ctx = req.context_values().ok_or("Missing eye.aftershock context")?;
    if ctx.get("schema").and_then(Json::as_str) != Some("eye-aftershock/1") {
        return Err("Unsupported blink aftershock input schema".into());
    }
    let source_ctx = ctx.get("source").ok_or("Missing source context")?;
    let folder_title = ctx
        .get("folder")
        .and_then(Json::as_str)
        .ok_or("Missing folder")?;
    let physics_names: Vec<String> = ctx
        .get("physicsNames")
        .and_then(Json::as_arr)
        .ok_or("Missing physicsNames")?
        .iter()
        .filter_map(|v| v.as_str().map(String::from))
        .collect();
    let inputs = decode_eye_inputs_from(source_ctx, req, true)?;

    let targets = target_ids(req)?;
    let target_set: HashSet<&str> = targets.iter().map(String::as_str).collect();
    let config = req.config();
    let scaffold = config.get("scaffold").ok_or("Missing scaffold")?;
    let scaffold_id = scaffold.as_str().ok_or("Invalid scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Missing base")?;
    for tid in inputs.targets.keys() {
        if !target_set.contains(tid.as_str()) {
            return Err("Target mismatch in eye.aftershock inputs".into());
        }
    }
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    if !upstream.iter().any(|p| {
        provider_matches_cap(p, "rig.scaffold", None)
            && p.get("instanceId").and_then(Json::as_str) == Some(scaffold_id)
            && p.get("version").and_then(Json::as_i64) == Some(2)
            && provider_has_all_targets(p, &target_set)
    }) {
        return Err("Blink aftershock v2 requires a version 2 source scaffold".into());
    }
    let fitted = upstream
        .iter()
        .find(|p| {
            provider_matches_cap(p, "eye/fitted", None)
                && p.get("instanceId").and_then(Json::as_str) == Some(base_id)
                && provider_has_all_targets(p, &target_set)
        })
        .ok_or("Missing fitted eye provider")?;
    let fv = fitted.get("version").and_then(Json::as_i64).unwrap_or(0);
    if !(1..=4).contains(&fv) {
        return Err("Blink aftershock v2 requires fitted eyes v1..v4".into());
    }
    let preceding = preceding_for(upstream, scaffold, &target_set);
    if preceding.iter().any(|p| {
        provider_matches_cap(
            p,
            "eye.aftershock",
            Some("psd2live.native.core/eye-aftershock"),
        )
    }) {
        return Err("Blink aftershock v2 cannot replace an earlier aftershock on the same mesh".into());
    }
    let coupled = [
        ("eye.gaze-lid", Some("psd2live.native.core/eye-gaze-lid")),
        ("eye.emotion", Some("psd2live.native.core/eye-emotion")),
        ("brow.lid-coupling", Some("psd2live.native.core/brow-lid-coupling")),
        ("mouth.cheek-lid", None),
    ];
    if preceding
        .iter()
        .any(|p| coupled.iter().any(|(c, a)| provider_matches_cap(p, c, *a)))
    {
        return Err("Blink aftershock v2 cannot compose with preceding coupled lid bindings".into());
    }

    let (fits, artwork) = fits_and_artwork(&inputs);
    if fits.is_empty() || inputs.layers.iter().all(|l| l.tag != "EYELASH") {
        return Ok(ok_objects(Vec::new()));
    }
    let fitted_values = fitted
        .get("config")
        .and_then(|c| c.get("values"))
        .unwrap_or(&Json::Null);
    let lower = knob(fitted_values, "lowerShare", 5.0, 0.0, 50.0)?;
    let roundness = knob(fitted_values, "roundness", 100.0, 40.0, 160.0)?;
    let squash = knob(fitted_values, "lashSquash", 60.0, 20.0, 100.0)?;
    let softness = knob(config.get("values").unwrap_or(&Json::Null), "softness", 100.0, 0.0, 200.0)?;

    let declared: Vec<Parameter> = [(Side::LEFT, "L"), (Side::RIGHT, "R")]
        .into_iter()
        .map(|(side, code)| {
            let id = format!("ParamEye{code}Bounce");
            let name = inputs
                .parameter_names
                .get(&id)
                .cloned()
                .ok_or_else(|| format!("Missing parameter name for {id}"))?;
            let blend = fits.contains_key(&side);
            Ok(Parameter {
                id,
                name,
                min: -1.0,
                max: 1.0,
                default: 0.0,
                blend,
                repeat: false,
                keys: if blend { Some(vec![-1.0, 0.0, 1.0]) } else { None },
            })
        })
        .collect::<Result<Vec<_>, String>>()?;

    let frame_bytes = cache.get("frame")?;
    let mut frame = decode_object_frame(frame_bytes).map_err(|e| e.to_string())?;
    let live_params = frame.parameters.clone();
    let live_defaults: HashMap<String, f32> = live_params
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();
    let mut added_params = Vec::new();
    let mut added_ids = HashSet::new();
    for p in &declared {
        if let Some(existing) = frame.parameters.iter().find(|e| e.id == p.id) {
            let mut cmp = existing.clone();
            cmp.name = p.name.clone();
            if &cmp != p {
                return Err(format!("Incompatible blink aftershock parameter: {}", p.id));
            }
        } else {
            added_params.push(p.clone());
            added_ids.insert(p.id.clone());
        }
    }

    let mut out_objects: Vec<(String, Vec<u8>)> = Vec::new();
    for tid in &targets {
        let Some(material) = inputs.targets.get(tid) else {
            continue;
        };
        let layer = inputs.layer_for_target(tid)?;
        let Some(lid) = mesh_of(&layer.tag, layer.role.as_deref()) else {
            continue;
        };
        let raw = inputs
            .topology
            .get(&material.layer)
            .ok_or_else(|| format!("Missing topology for {}", material.layer))?;
        let side = Side::from_str(&layer.side)?;
        let wide_prov = last_preceding(
            &preceding,
            "eye.wide",
            Some("psd2live.native.core/eye-wide"),
            tid,
        );
        let smile_prov = last_preceding(
            &preceding,
            "eye.smile",
            Some("psd2live.native.core/eye-smile"),
            tid,
        );
        let wide_vals = wide_prov
            .and_then(|p| p.get("config"))
            .and_then(|c| c.get("values"))
            .unwrap_or(&Json::Null);
        let smile_vals = smile_prov
            .and_then(|p| p.get("config"))
            .and_then(|c| c.get("values"))
            .unwrap_or(&Json::Null);

        let mut opts = EyeOptions::defaults();
        opts.aftershock = true;
        opts.softness = softness;
        opts.wide = wide_prov.is_some();
        opts.wide_lift = knob(wide_vals, "lift", 10.0, 0.0, 80.0)?;
        opts.smile = smile_prov.is_some();
        opts.smile_arc = 0.12 * knob(smile_vals, "arch", 100.0, 0.0, 200.0)?;
        opts.smile_push = knob(smile_vals, "push", 50.0, 0.0, 100.0)?;

        let motion = FittedEyeMotion {
            rigs: build_rigs(&fits, &artwork, lower, roundness, squash, &opts),
            options: opts,
        };
        let mut mesh = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let (r_ord, r_op, r_mul, r_scr) = rest_channels(&mesh, &live_params, true);
        let mut base_opts = EyeOptions::defaults();
        base_opts.aftershock = true;
        base_opts.softness = softness;
        let shapes = compute_lid_shapes(
            lid,
            side,
            &layer.id,
            &raw.positions,
            material.frame,
            &motion,
            &base_opts,
            (r_ord, r_op, &r_mul, &r_scr),
        );
        if shapes.is_empty() {
            continue;
        }
        let branch = collect_deformer_branch(mesh.parent.as_deref(), cache)?;
        let lowered_carrier = mesh.parent.as_deref() != material.parent.as_deref();
        if lowered_carrier {
            let parent_ok = branch
                .first()
                .is_some_and(|d| match d {
                    Deformer::Warp { parent, .. } | Deformer::Rotation { parent, .. } => {
                        parent.as_deref() == material.parent.as_deref()
                    }
                });
            if !parent_ok {
                return Err(format!(
                    "Blink aftershock v2 cannot migrate a fitted lid carrier: {tid}"
                ));
            }
        }
        let geom = mesh
            .geometry
            .as_ref()
            .ok_or_else(|| format!("Missing mesh geometry: {tid}"))?;
        if !lowered_carrier {
            let original =
                original_positions(&raw.positions, material.parent.as_deref(), material.frame);
            if !approx_eq_slice(&geom.positions, &original, 1e-4)
                || geom.uvs != raw.uvs
                || geom.indices != raw.indices
            {
                return Err(format!(
                    "Blink aftershock v2 requires the captured lid topology and positions: {tid}"
                ));
            }
            let expected = compute_lid_or_paired_grid(
                lid,
                side,
                &layer.id,
                &raw.positions,
                material.frame,
                &motion,
            );
            if !expected_grid_matches(&mesh.offsets, &expected) {
                return Err(format!(
                    "Blink aftershock v2 cannot migrate edited or lowered lid geometry: {tid}"
                ));
            }
        } else if geom.positions.len() != raw.positions.len()
            || geom.uvs != raw.uvs
            || geom.indices != raw.indices
        {
            return Err(format!(
                "Blink aftershock v2 requires the captured lid topology and positions: {tid}"
            ));
        }

        let mapping =
            check_neutral_world(&mesh, &branch, &live_defaults, &raw.positions, &inputs, tid)?;
        let scaled_shapes = if !lowered_carrier {
            shapes
        } else {
            let corners = mapping.local_to_world(&[0.0, 0.0, 1.0, 1.0]);
            let cw = (corners[2] - corners[0]).abs().max(1e-4);
            let ch = (corners[3] - corners[1]).abs().max(1e-4);
            let rw = if material.parent.is_none() {
                1.0
            } else {
                material.frame.width()
            };
            let rh = if material.parent.is_none() {
                1.0
            } else {
                material.frame.height()
            };
            let sx = rw / cw;
            let sy = rh / ch;
            shapes
                .into_iter()
                .map(|b| BlendBinding {
                    parameter: b.parameter,
                    keys: b.keys,
                    neutral_index: b.neutral_index,
                    limits: b.limits,
                    shapes: b
                        .shapes
                        .into_iter()
                        .map(|f| {
                            f.map(|s| MeshShape {
                                deltas: s
                                    .deltas
                                    .iter()
                                    .enumerate()
                                    .map(|(k, &v)| v * if k % 2 == 0 { sx } else { sy })
                                    .collect(),
                                draw_order: s.draw_order,
                                opacity: s.opacity,
                                multiply: s.multiply,
                                screen: s.screen,
                            })
                        })
                        .collect(),
                })
                .collect()
        };

        let generated = neutralized_shapes(scaled_shapes, &mesh, &live_params, true);
        mesh.shapes.extend(generated);
        out_objects.push((format!("mesh:{tid}"), encode_object_mesh(&mesh)));
    }

    let available: HashSet<String> = live_params
        .iter()
        .chain(added_params.iter())
        .map(|p| p.id.clone())
        .collect();
    let mut frame_dirty = false;
    if !added_ids.is_empty() {
        frame.parameters.extend(added_params);
        let desired: Vec<String> = declared.iter().map(|p| p.id.clone()).collect();
        let group_id = output_id(req, "parameter-folder:ParamGroupPhysics");
        append_folder_tree(&mut frame, &group_id, folder_title, &desired, &added_ids);
        frame_dirty = true;
    }

    if config
        .get("physicsEnabled")
        .and_then(Json::as_bool)
        .unwrap_or(true)
    {
        let overrides = config
            .get("physics")
            .and_then(Json::as_obj)
            .unwrap_or(&[]);
        let sides = ["L", "R"];
        let mut settings: Vec<(String, PhysicsGroup)> = Vec::new();
        for (idx, side_code) in sides.iter().enumerate() {
            let in_param = format!("ParamEye{side_code}Open");
            let out_param = format!("ParamEye{side_code}Bounce");
            if !available.contains(&in_param) || !available.contains(&out_param) {
                continue;
            }
            let rule_id = format!("PhysicsEye{side_code}Bounce");
            let name = physics_names
                .get(idx)
                .cloned()
                .ok_or("Missing physics name")?;
            let group = if let Some(ov) = obj_get(overrides, &rule_id) {
                parse_physics_override(&output_id(req, &format!("physics:{rule_id}")), ov, &available)?
            } else {
                PhysicsGroup {
                    id: output_id(req, &format!("physics:{rule_id}")),
                    name,
                    inputs: vec![PhysicsInput {
                        parameter: in_param,
                        weight: 100.0,
                        source: 0,
                        reflect: false,
                    }],
                    outputs: vec![PhysicsOutput {
                        parameter: out_param,
                        vertex: 2,
                        scale: 1.4,
                        weight: 100.0,
                        source: 0,
                        reflect: false,
                    }],
                    segments: vec![
                        PhysicsSegment {
                            length: 1.0,
                            mobility: 0.9,
                            delay: 0.6,
                            acceleration: 1.6,
                        },
                        PhysicsSegment {
                            length: 1.2,
                            mobility: 0.85,
                            delay: 0.45,
                            acceleration: 1.8,
                        },
                    ],
                    normalization: PhysicsNormalization {
                        position_min: -1.0,
                        position_default: 0.0,
                        position_max: 1.0,
                        angle_min: -10.0,
                        angle_default: 0.0,
                        angle_max: 10.0,
                    },
                }
            };
            settings.push((rule_id, group));
        }
        for (k, _) in overrides {
            if !settings.iter().any(|(id, _)| id == k) {
                return Err("Unavailable blink aftershock physics slot".into());
            }
        }
        for (_, group) in settings {
            let compatible = frame.physics.groups.iter().any(|g| {
                let mut cmp = g.clone();
                cmp.id = group.id.clone();
                cmp.name = group.name.clone();
                cmp == group
            });
            if compatible {
                continue;
            }
            let out_params: HashSet<&str> =
                group.outputs.iter().map(|o| o.parameter.as_str()).collect();
            if frame.physics.groups.iter().any(|g| {
                g.id == group.id
                    || g.outputs
                        .iter()
                        .any(|o| out_params.contains(o.parameter.as_str()))
            }) {
                return Err(format!("Conflicting blink aftershock physics group: {}", group.id));
            }
            frame.physics.groups.push(group);
            frame_dirty = true;
        }
    }

    if frame_dirty {
        out_objects.push(("frame".into(), encode_object_frame(&frame)));
    }
    Ok(ok_objects(out_objects))
}

fn parse_physics_override(
    id: &str,
    v: &Json,
    available: &HashSet<String>,
) -> Result<PhysicsGroup, String> {
    if v.as_obj().is_none() {
        return Err("Invalid physics override".into());
    }
    let name = v
        .get("name")
        .and_then(Json::as_str)
        .ok_or("Missing physics name")?
        .to_string();
    let parse_src = |s: Option<&str>| -> u32 {
        match s {
            Some("Y") => 1,
            Some("ANGLE") => 2,
            _ => 0,
        }
    };
    let mut inputs = Vec::new();
    for iv in v.get("inputs").and_then(Json::as_arr).ok_or("inputs")? {
        if iv.as_obj().is_none() {
            return Err("input".into());
        }
        let param = iv
            .get("parameter")
            .and_then(Json::as_str)
            .ok_or("input.parameter")?
            .to_string();
        if !available.contains(&param) {
            return Err("Invalid blink aftershock physics inputs or outputs".into());
        }
        inputs.push(PhysicsInput {
            parameter: param,
            weight: iv.get("weight").and_then(Json::as_f64).unwrap_or(100.0) as f32,
            source: parse_src(iv.get("source").and_then(Json::as_str)),
            reflect: iv.get("reflect").and_then(Json::as_bool).unwrap_or(false),
        });
    }
    let mut outputs = Vec::new();
    let mut out_set = HashSet::new();
    for ov in v.get("outputs").and_then(Json::as_arr).ok_or("outputs")? {
        if ov.as_obj().is_none() {
            return Err("output".into());
        }
        let param = ov
            .get("parameter")
            .and_then(Json::as_str)
            .ok_or("output.parameter")?
            .to_string();
        if !available.contains(&param) {
            return Err("Invalid blink aftershock physics inputs or outputs".into());
        }
        out_set.insert(param.clone());
        outputs.push(PhysicsOutput {
            parameter: param,
            vertex: ov.get("vertex").and_then(Json::as_i64).unwrap_or(2) as i32,
            scale: ov.get("scale").and_then(Json::as_f64).unwrap_or(1.4) as f32,
            weight: ov.get("weight").and_then(Json::as_f64).unwrap_or(100.0) as f32,
            source: parse_src(ov.get("source").and_then(Json::as_str)),
            reflect: ov.get("reflect").and_then(Json::as_bool).unwrap_or(false),
        });
    }
    if inputs.is_empty()
        || outputs.is_empty()
        || inputs.iter().any(|i| out_set.contains(&i.parameter))
    {
        return Err("Invalid blink aftershock physics inputs or outputs".into());
    }
    let mut segments = Vec::new();
    for sv in v.get("segments").and_then(Json::as_arr).ok_or("segments")? {
        if sv.as_obj().is_none() {
            return Err("segment".into());
        }
        segments.push(PhysicsSegment {
            length: sv.get("length").and_then(Json::as_f64).ok_or("length")? as f32,
            mobility: sv.get("mobility").and_then(Json::as_f64).ok_or("mobility")? as f32,
            delay: sv.get("delay").and_then(Json::as_f64).ok_or("delay")? as f32,
            acceleration: sv
                .get("acceleration")
                .and_then(Json::as_f64)
                .ok_or("acceleration")? as f32,
        });
    }
    let no = v.get("normalization").ok_or("normalization")?;
    if no.as_obj().is_none() {
        return Err("normalization".into());
    }
    let normalization = PhysicsNormalization {
        position_min: no
            .get("positionMin")
            .and_then(Json::as_f64)
            .ok_or("positionMin")? as f32,
        position_default: no
            .get("positionDefault")
            .and_then(Json::as_f64)
            .ok_or("positionDefault")? as f32,
        position_max: no
            .get("positionMax")
            .and_then(Json::as_f64)
            .ok_or("positionMax")? as f32,
        angle_min: no
            .get("angleMin")
            .and_then(Json::as_f64)
            .ok_or("angleMin")? as f32,
        angle_default: no
            .get("angleDefault")
            .and_then(Json::as_f64)
            .ok_or("angleDefault")? as f32,
        angle_max: no
            .get("angleMax")
            .and_then(Json::as_f64)
            .ok_or("angleMax")? as f32,
    };
    Ok(PhysicsGroup {
        id: id.to_string(),
        name,
        inputs,
        outputs,
        segments,
        normalization,
    })
}

pub fn handle_eye_gaze_lid(req: &Request, cache: &ObjectCache) -> Response {
    match eye_gaze_lid_impl(req, cache) {
        Ok(r) => r,
        Err(e) => Response::Error(e),
    }
}

fn eye_gaze_lid_impl(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    if req.header.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Unsupported eye.gaze-lid version".into());
    }
    let ctx = req.context_values().ok_or("Missing eye.gaze-lid context")?;
    if ctx.get("schema").and_then(Json::as_str) != Some("eye-gaze-lid/1") {
        return Err("Unsupported lid gaze input schema".into());
    }
    let source_ctx = ctx.get("source").ok_or("Missing source context")?;
    let carriers_obj = ctx
        .get("carriers")
        .and_then(Json::as_obj)
        .ok_or("Missing carriers")?;
    let shape_name = ctx
        .get("shapeName")
        .and_then(Json::as_str)
        .ok_or("Missing shapeName")?;
    let inputs = decode_eye_inputs_from(source_ctx, req, true)?;

    let targets = target_ids(req)?;
    let target_set: HashSet<&str> = targets.iter().map(String::as_str).collect();
    let config = req.config();
    let scaffold = config.get("scaffold").ok_or("Missing scaffold")?;
    let scaffold_id = scaffold.as_str().ok_or("Invalid scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Missing base")?;
    for tid in inputs.targets.keys() {
        if !target_set.contains(tid.as_str()) {
            return Err("Target mismatch in eye.gaze-lid inputs".into());
        }
    }
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    if !upstream.iter().any(|p| {
        provider_matches_cap(p, "rig.scaffold", None)
            && p.get("instanceId").and_then(Json::as_str) == Some(scaffold_id)
            && p.get("version").and_then(Json::as_i64) == Some(2)
            && provider_has_all_targets(p, &target_set)
    }) {
        return Err("Lid gaze v2 requires a version 2 source scaffold".into());
    }
    let fitted = upstream
        .iter()
        .find(|p| {
            provider_matches_cap(p, "eye/fitted", None)
                && p.get("instanceId").and_then(Json::as_str) == Some(base_id)
                && provider_has_all_targets(p, &target_set)
        })
        .ok_or("Missing fitted eye provider")?;
    let fv = fitted.get("version").and_then(Json::as_i64).unwrap_or(0);
    if !(1..=4).contains(&fv) {
        return Err("Lid gaze v2 requires fitted eyes v1..v4".into());
    }
    let preceding = preceding_for(upstream, scaffold, &target_set);
    if preceding
        .iter()
        .any(|p| provider_matches_cap(p, "eye.gaze-lid", Some("psd2live.native.core/eye-gaze-lid")))
    {
        return Err("Lid gaze v2 cannot replace an earlier gaze binding on the same mesh".into());
    }
    let coupled = [
        ("eye.aftershock", Some("psd2live.native.core/eye-aftershock")),
        ("brow.lid-coupling", Some("psd2live.native.core/brow-lid-coupling")),
        ("mouth.cheek-lid", None),
    ];
    if preceding
        .iter()
        .any(|p| coupled.iter().any(|(c, a)| provider_matches_cap(p, c, *a)))
    {
        return Err("Lid gaze v2 cannot compose with preceding coupled lid bindings".into());
    }

    let (fits, artwork) = fits_and_artwork(&inputs);
    if fits.is_empty() || inputs.layers.iter().all(|l| l.tag != "EYELASH") {
        return Ok(ok_objects(Vec::new()));
    }
    let fitted_cfg = fitted.get("config").unwrap_or(&Json::Null);
    let fitted_values = fitted_cfg.get("values").unwrap_or(&Json::Null);
    let lower = knob(fitted_values, "lowerShare", 5.0, 0.0, 50.0)?;
    let roundness = knob(fitted_values, "roundness", 100.0, 40.0, 160.0)?;
    let squash = knob(fitted_values, "lashSquash", 60.0, 20.0, 100.0)?;
    let follow = knob(
        config.get("values").unwrap_or(&Json::Null),
        "follow",
        100.0,
        0.0,
        200.0,
    )?;

    let declared: Vec<Parameter> = ["LX", "LY", "RX", "RY"]
        .into_iter()
        .map(|s| {
            let id = format!("ParamEyeBall{s}");
            let name = inputs
                .parameter_names
                .get(&id)
                .cloned()
                .ok_or_else(|| format!("Missing parameter name for {id}"))?;
            Ok(Parameter {
                id,
                name,
                min: -1.0,
                max: 1.0,
                default: 0.0,
                blend: false,
                repeat: false,
                keys: None,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;
    let declared_ids: HashSet<String> = declared.iter().map(|p| p.id.clone()).collect();

    let frame_bytes = cache.get("frame")?;
    let mut frame = decode_object_frame(frame_bytes).map_err(|e| e.to_string())?;
    let live_params = frame.parameters.clone();
    let live_defaults: HashMap<String, f32> = live_params
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();

    let mut lids: Vec<(Mesh, Mesh)> = Vec::new();
    for tid in &targets {
        let Some(material) = inputs.targets.get(tid) else {
            continue;
        };
        let layer = inputs.layer_for_target(tid)?;
        let Some(lid) = mesh_of(&layer.tag, layer.role.as_deref()) else {
            continue;
        };
        let raw = inputs
            .topology
            .get(&material.layer)
            .ok_or_else(|| format!("Missing topology for {}", material.layer))?;
        let side = Side::from_str(&layer.side)?;
        let wide_prov = last_preceding(&preceding, "eye.wide", Some("psd2live.native.core/eye-wide"), tid);
        let smile_prov = last_preceding(&preceding, "eye.smile", Some("psd2live.native.core/eye-smile"), tid);
        let emo_prov = last_preceding(&preceding, "eye.emotion", Some("psd2live.native.core/eye-emotion"), tid);
        let wide_vals = wide_prov.and_then(|p| p.get("config")).and_then(|c| c.get("values")).unwrap_or(&Json::Null);
        let smile_vals = smile_prov.and_then(|p| p.get("config")).and_then(|c| c.get("values")).unwrap_or(&Json::Null);
        let emo_vals = emo_prov.and_then(|p| p.get("config")).and_then(|c| c.get("values")).unwrap_or(&Json::Null);

        let mut lid_opts = EyeOptions::defaults();
        lid_opts.gaze = true;
        lid_opts.gaze_follow = follow;
        lid_opts.emotion = emo_prov.is_some();
        lid_opts.emotion_strength = knob(emo_vals, "strength", 100.0, 0.0, 200.0)?;
        lid_opts.wide = wide_prov.is_some();
        lid_opts.wide_lift = knob(wide_vals, "lift", 10.0, 0.0, 80.0)?;
        lid_opts.smile = smile_prov.is_some();
        lid_opts.smile_arc = 0.12 * knob(smile_vals, "arch", 100.0, 0.0, 200.0)?;
        lid_opts.smile_push = knob(smile_vals, "push", 50.0, 0.0, 100.0)?;

        let motion = FittedEyeMotion {
            rigs: build_rigs(&fits, &artwork, lower, roundness, squash, &lid_opts),
            options: lid_opts,
        };
        let mesh = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let (r_ord, r_op, r_mul, r_scr) = rest_channels(&mesh, &live_params, true);
        let gaze = compute_lid_shapes(
            lid,
            side,
            &layer.id,
            &raw.positions,
            material.frame,
            &motion,
            &lid_opts,
            (r_ord, r_op, &r_mul, &r_scr),
        );
        if gaze.is_empty() {
            continue;
        }
        if mesh.parent.as_deref() != material.parent.as_deref() {
            return Err(format!("Lid gaze v2 cannot migrate a fitted lid carrier: {tid}"));
        }
        let geom = mesh
            .geometry
            .as_ref()
            .ok_or_else(|| format!("Missing mesh geometry: {tid}"))?;
        let original = original_positions(&raw.positions, material.parent.as_deref(), material.frame);
        if !approx_eq_slice(&geom.positions, &original, 1e-4)
            || geom.uvs != raw.uvs
            || geom.indices != raw.indices
        {
            return Err(format!(
                "Lid gaze v2 requires the captured lid topology and positions: {tid}"
            ));
        }
        let expected = compute_lid_or_paired_grid(lid, side, &layer.id, &raw.positions, material.frame, &motion);
        if !expected_grid_matches(&mesh.offsets, &expected) {
            return Err(format!(
                "Lid gaze v2 cannot migrate edited or lowered lid geometry: {tid}"
            ));
        }
        let branch = collect_deformer_branch(mesh.parent.as_deref(), cache)?;
        check_neutral_world(&mesh, &branch, &live_defaults, &raw.positions, &inputs, tid)?;
        let mut no_gaze_opts = lid_opts;
        no_gaze_opts.gaze = false;
        let prev_shapes = compute_lid_shapes(
            lid,
            side,
            &layer.id,
            &raw.positions,
            material.frame,
            &motion,
            &no_gaze_opts,
            (r_ord, r_op, &r_mul, &r_scr),
        );
        if !shapes_match(&prev_shapes, &mesh.shapes) {
            return Err(format!(
                "Lid gaze v2 cannot migrate edited or lowered lid shapes: {tid}"
            ));
        }
        let mut next_mesh = mesh.clone();
        next_mesh.shapes = gaze;
        lids.push((mesh, next_mesh));
    }

    let mut frame_dirty = false;
    for s in ["L", "R"] {
        let link = ParameterLink {
            horizontal: format!("ParamEyeBall{s}X"),
            vertical: format!("ParamEyeBall{s}Y"),
        };
        if frame.parameter_links.iter().any(|l| {
            l != &link
                && (l.horizontal == link.horizontal
                    || l.horizontal == link.vertical
                    || l.vertical == link.horizontal
                    || l.vertical == link.vertical)
        }) {
            return Err(format!(
                "Incompatible gaze parameter link for {} and {}",
                link.horizontal, link.vertical
            ));
        }
        if !frame.parameter_links.contains(&link) {
            frame.parameter_links.push(link);
            frame_dirty = true;
        }
    }

    let tuning = fitted_cfg.get("tuning").unwrap_or(&Json::Null);
    let gaze_x = tuning.get("gazeX").and_then(Json::as_f64).unwrap_or(10.0) as f32;
    let gaze_y = tuning.get("gazeY").and_then(Json::as_f64).unwrap_or(8.5) as f32;
    if !(0.0..=30.0).contains(&gaze_x) || !(0.0..=30.0).contains(&gaze_y) {
        return Err("Invalid iris gaze tuning".into());
    }
    let iris_points = |gx: f32, gy: f32| -> Vec<f32> {
        (0..18)
            .map(|idx| {
                let v = idx / 2;
                if idx % 2 == 0 {
                    (v % 3) as f32 / 2.0 + gx * gaze_x / 100.0
                } else {
                    (v / 3) as f32 / 2.0 - gy * gaze_y / 100.0
                }
            })
            .collect()
    };

    let mut seen_branch_ids = HashSet::new();
    let mut carrier_updates: HashMap<String, Deformer> = HashMap::new();
    for tid in &targets {
        let m = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let branch = collect_deformer_branch(m.parent.as_deref(), cache)?;
        for carrier in branch {
            let cid = match &carrier {
                Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.clone(),
            };
            if !seen_branch_ids.insert(cid.clone()) {
                continue;
            }
            let Some(side_str) = obj_get(carriers_obj, &cid).and_then(Json::as_str) else {
                continue;
            };
            if side_str == "Both" {
                continue;
            }
            if side_str != "L" && side_str != "R" {
                return Err(format!("Invalid carrier side: {side_str}"));
            }
            let Deformer::Warp {
                id,
                name,
                parent,
                part,
                rows,
                columns,
                bilinear,
                lattice,
                channels,
                opacity,
                multiply,
                screen,
                selectable,
                visible,
                enabled,
                shapes,
            } = carrier
            else {
                return Err("Expected Warp carrier for independent gaze".into());
            };
            let keys = vec![-1.0, 0.0, 1.0];
            let valid_lattice = lattice.as_ref().is_some_and(|l| {
                l.axes.len() == 2
                    && l.axes[0].parameter == "ParamEyeBallX"
                    && l.axes[0].keys == keys
                    && l.axes[1].parameter == "ParamEyeBallY"
                    && l.axes[1].keys == keys
                    && l.cells.len() == 9
                    && l.cells.iter().all(|c| {
                        c.coordinate.len() == 2
                            && c.coordinate.iter().all(|&idx| (0..3).contains(&idx))
                            && c.form
                                == iris_points(
                                    keys[c.coordinate[0] as usize],
                                    keys[c.coordinate[1] as usize],
                                )
                    })
            });
            if rows != 2 || columns != 2 || !bilinear || !shapes.is_empty() || !valid_lattice {
                return Err(format!(
                    "Lid gaze v2 requires the fitted iris gaze lattice: {id}"
                ));
            }
            let new_shapes: Vec<BlendBinding<LatticeShape>> = ["X", "Y"]
                .into_iter()
                .map(|axis| BlendBinding {
                    parameter: format!("ParamEyeBall{side_str}{axis}"),
                    keys: keys.clone(),
                    neutral_index: 1,
                    limits: Vec::new(),
                    shapes: keys
                        .iter()
                        .map(|&v| {
                            if v == 0.0 {
                                None
                            } else {
                                Some(LatticeShape {
                                    points: iris_points(
                                        if axis == "X" { v } else { 0.0 },
                                        if axis == "Y" { v } else { 0.0 },
                                    ),
                                    opacity: 1.0,
                                    multiply: Rgb {
                                        r: 1.0,
                                        g: 1.0,
                                        b: 1.0,
                                    },
                                    screen: Rgb {
                                        r: 1.0,
                                        g: 1.0,
                                        b: 1.0,
                                    },
                                })
                            }
                        })
                        .collect(),
                })
                .collect();
            carrier_updates.insert(
                cid,
                Deformer::Warp {
                    id,
                    name,
                    parent,
                    part,
                    rows,
                    columns,
                    bilinear,
                    lattice,
                    channels,
                    opacity,
                    multiply,
                    screen,
                    selectable,
                    visible,
                    enabled,
                    shapes: new_shapes,
                },
            );
        }
    }

    let mut context_deformer_ids: Vec<String> = Vec::new();
    let mut seen_ctx_def = HashSet::new();
    for (k, _) in carriers_obj {
        if seen_ctx_def.insert(k.clone()) && cache.get(&format!("deformer:{k}")).is_ok() {
            context_deformer_ids.push(k.clone());
        }
    }
    for (m, _) in &lids {
        if let Some(pid) = &m.parent {
            if seen_ctx_def.insert(pid.clone()) && cache.get(&format!("deformer:{pid}")).is_ok() {
                context_deformer_ids.push(pid.clone());
            }
        }
    }
    let mut context_deformers = Vec::with_capacity(context_deformer_ids.len());
    for cid in &context_deformer_ids {
        if let Some(updated) = carrier_updates.get(cid) {
            context_deformers.push(updated.clone());
        } else {
            let d = cache
                .get(&format!("deformer:{cid}"))
                .and_then(|b| decode_object_deformer(b).map_err(|e| e.to_string()))?;
            context_deformers.push(d);
        }
    }

    let mut lowering_params: Vec<Parameter> = live_params
        .iter()
        .filter(|p| !declared_ids.contains(&p.id))
        .cloned()
        .collect();
    lowering_params.extend(declared.iter().cloned());

    let mut model = RigIR::default();
    model.canvas = frame.canvas.clone();
    model.parameters = lowering_params;
    model.deformers = context_deformers;
    model.meshes = lids.iter().map(|(_, next)| next.clone()).collect();

    let lowered = lowering::lawful(&model, |_, name| shape_name.replace("{meshName}", name));
    let new_carriers = &lowered.deformers[context_deformer_ids.len()..];
    let mut remap: HashMap<String, String> = HashMap::new();
    for c in new_carriers {
        let orig_id = match c {
            Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.as_str(),
        };
        remap.insert(
            orig_id.to_string(),
            output_id(req, &format!("shared/deformer:{orig_id}")),
        );
    }

    let mut out_objects: Vec<(String, Vec<u8>)> = Vec::new();
    for (_, updated_carrier) in carrier_updates {
        let cid = match &updated_carrier {
            Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.clone(),
        };
        out_objects.push((format!("deformer:{cid}"), encode_object_deformer(&updated_carrier)));
    }
    for c in new_carriers {
        let mut remapped = c.clone();
        match &mut remapped {
            Deformer::Warp { id, parent, .. } | Deformer::Rotation { id, parent, .. } => {
                *id = remap[id.as_str()].clone();
                if let Some(p) = parent {
                    if let Some(np) = remap.get(p.as_str()) {
                        *p = np.clone();
                    }
                }
            }
        }
        let cid = match &remapped {
            Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.clone(),
        };
        out_objects.push((format!("deformer:{cid}"), encode_object_deformer(&remapped)));
    }

    for ((before, _), mut after) in lids.into_iter().zip(lowered.meshes.into_iter()) {
        if let Some(p) = &mut after.parent {
            if let Some(np) = remap.get(p.as_str()) {
                *p = np.clone();
            }
        }
        if after != before {
            out_objects.push((format!("mesh:{}", before.id), encode_object_mesh(&after)));
        }
    }

    let mut added_params = Vec::new();
    let mut added_ids = HashSet::new();
    for p in lowered.parameters {
        if !declared_ids.contains(&p.id) {
            continue;
        }
        if let Some(existing) = frame.parameters.iter().find(|e| e.id == p.id) {
            let mut cmp = existing.clone();
            cmp.name = p.name.clone();
            if cmp != p {
                return Err(format!("Incompatible lid gaze parameter: {}", p.id));
            }
        } else {
            added_ids.insert(p.id.clone());
            added_params.push(p);
        }
    }

    if !added_ids.is_empty() {
        frame.parameters.extend(added_params);
        let folder_name = inputs
            .folder_name
            .clone()
            .ok_or("Missing folderName in eye gaze inputs")?;
        let desired_order: Vec<String> = [
            "ParamEyeLOpen",
            "ParamEyeROpen",
            "ParamEyeBallX",
            "ParamEyeBallY",
            "ParamEyeBallForm",
            "ParamEyeLSmile",
            "ParamEyeRSmile",
        ]
        .into_iter()
        .map(String::from)
        .chain(declared.iter().map(|p| p.id.clone()))
        .chain(
            [
                "ParamEyeLForm",
                "ParamEyeRForm",
                "ParamEyeLSquint",
                "ParamEyeRSquint",
            ]
            .into_iter()
            .map(String::from),
        )
        .collect();
        let group_id = output_id(req, "parameter-folder:ParamGroupEyes");
        append_folder_tree(&mut frame, &group_id, &folder_name, &desired_order, &added_ids);
        frame_dirty = true;
    }

    if frame_dirty {
        out_objects.push(("frame".into(), encode_object_frame(&frame)));
    }
    Ok(ok_objects(out_objects))
}

pub fn handle_brow_lid_coupling(req: &Request, cache: &ObjectCache) -> Response {
    match brow_lid_coupling_impl(req, cache) {
        Ok(r) => r,
        Err(e) => Response::Error(e),
    }
}

fn brow_lid_coupling_impl(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    if req.header.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Unsupported brow.lid-coupling version".into());
    }
    let targets = target_ids(req)?;
    let target_set: HashSet<&str> = targets.iter().map(String::as_str).collect();
    let config = req.config();
    let scaffold = config.get("scaffold").ok_or("Missing scaffold")?;
    let scaffold_id = scaffold.as_str().ok_or("Invalid scaffold")?;
    let brow_base = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Missing brow base")?;
    let eye_base = config
        .get("dependencies")
        .and_then(|d| d.get("eye/fitted"))
        .and_then(Json::as_str)
        .ok_or("Missing eye/fitted dependency")?;

    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);

    let find_prov = |cap: &str, id: &str| -> Result<&Json, String> {
        upstream
            .iter()
            .find(|p| {
                provider_matches_cap(p, cap, None)
                    && p.get("instanceId").and_then(Json::as_str) == Some(id)
                    && provider_has_all_targets(p, &target_set)
                    && (cap == "rig.scaffold"
                        || p.get("config").and_then(|c| c.get("scaffold")) == Some(scaffold))
            })
            .ok_or_else(|| format!("Missing brow and lid provider: {cap}"))
    };
    let scaf_prov = find_prov("rig.scaffold", scaffold_id)?;
    if scaf_prov.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Brow and lid v2 requires v2 scaffold".into());
    }
    let brow_prov = find_prov("brow/fitted", brow_base)?;
    if !matches!(brow_prov.get("version").and_then(Json::as_i64), Some(1 | 2)) {
        return Err("Brow and lid v2 requires fitted brows v1 or v2".into());
    }
    let eye_prov = find_prov("eye/fitted", eye_base)?;
    if !matches!(eye_prov.get("version").and_then(Json::as_i64), Some(1..=4)) {
        return Err("Brow and lid v2 requires fitted eyes v1..v4".into());
    }
    let preceding = preceding_for(upstream, scaffold, &target_set);
    let unsupported = [
        (
            "brow.lid-coupling",
            Some("psd2live.native.core/brow-lid-coupling"),
        ),
        ("face/fitted", None),
        ("face.depth-turn", None),
        ("eye.gaze-lid", Some("psd2live.native.core/eye-gaze-lid")),
        ("mouth.cheek-lid", None),
    ];
    if preceding
        .iter()
        .any(|p| unsupported.iter().any(|(c, a)| provider_matches_cap(p, c, *a)))
    {
        return Err(
            "Brow and lid v2 requires eye meshes without previously lowered lid coupling or depth turn"
                .into(),
        );
    }

    let ctx = req
        .context_values()
        .ok_or("Missing brow.lid-coupling context")?;
    let names = ctx
        .get("carrier_names")
        .and_then(Json::as_obj)
        .ok_or("Missing carrier_names")?;
    let native_ids = ctx
        .get("native_ids")
        .and_then(Json::as_obj)
        .ok_or("Missing native_ids")?;
    let brow_ids: Vec<String> = ctx
        .get("brow_meshes")
        .and_then(Json::as_arr)
        .ok_or("Missing brow_meshes")?
        .iter()
        .filter_map(|v| v.as_str().map(String::from))
        .collect();
    let cover = ctx
        .get("brow_cover")
        .and_then(Json::as_f64)
        .ok_or("Missing brow_cover")? as f32;
    if !(0.0..=1.0).contains(&cover) {
        return Err("Invalid brow_cover".into());
    }
    let inputs = decode_eye_inputs(req, false)?;
    for tid in inputs.targets.keys() {
        if !target_set.contains(tid.as_str()) {
            return Err("Target mismatch in brow.lid-coupling".into());
        }
    }

    let mut brow_meshes = Vec::new();
    for bid in &brow_ids {
        if let Ok(b) = cache.get(&format!("mesh:{bid}")) {
            let mut bm = decode_object_mesh(b).map_err(|e| e.to_string())?;
            bm.shapes.clear();
            brow_meshes.push(bm);
        }
    }

    let frame_bytes = cache.get("frame")?;
    let frame = decode_object_frame(frame_bytes).map_err(|e| e.to_string())?;
    let live_defaults: HashMap<String, f32> = frame
        .parameters
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();

    let (fits, artwork) = fits_and_artwork(&inputs);
    let eye_vals = eye_prov
        .get("config")
        .and_then(|c| c.get("values"))
        .unwrap_or(&Json::Null);
    let lower = knob(eye_vals, "lowerShare", 5.0, 0.0, 50.0)?;
    let roundness = knob(eye_vals, "roundness", 100.0, 40.0, 160.0)?;
    let squash = knob(eye_vals, "lashSquash", 60.0, 20.0, 100.0)?;
    let strength = knob(
        config.get("values").unwrap_or(&Json::Null),
        "strength",
        100.0,
        0.0,
        200.0,
    )? * (1.0 + 0.6 * cover);
    let mut options = EyeOptions::defaults();
    options.brow_coupling = true;
    options.brow_strength = strength;
    let motion = FittedEyeMotion {
        rigs: build_rigs(&fits, &artwork, lower, roundness, squash, &options),
        options,
    };

    let mut carriers: HashMap<String, Deformer> = HashMap::new();
    let mut out_objects: Vec<(String, Vec<u8>)> = Vec::new();

    for tid in &targets {
        let Some(material) = inputs.targets.get(tid) else {
            continue;
        };
        let layer = inputs.layer_for_target(tid)?;
        let side = Side::from_str(&layer.side)?;
        let raw = inputs
            .topology
            .get(&material.layer)
            .ok_or_else(|| format!("Missing topology for {}", material.layer))?;
        let positions = &raw.positions;
        let lid = mesh_of(&layer.tag, layer.role.as_deref());

        let mesh = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let (r_ord, r_op, r_mul, r_scr) = rest_channels(&mesh, &frame.parameters, false);
        let shapes: Vec<BlendBinding<MeshShape>> = if let Some(lid) = lid {
            compute_lid_shapes(
                lid,
                side,
                &layer.id,
                positions,
                material.frame,
                &motion,
                &options,
                (r_ord, r_op, &r_mul, &r_scr),
            )
        } else if layer.tag == "IRIDES"
            && layer.role.as_deref() != Some("HIGHLIGHT")
            && motion.rigs.contains_key(&side)
        {
            let iris_bounds = motion.rigs[&side].fit.iris.unwrap_or(layer.bounds);
            pupil_shapes(
                side,
                positions,
                material.frame,
                iris_bounds,
                &options,
                r_ord,
                r_op,
                r_mul,
                r_scr,
            )
        } else {
            Vec::new()
        };
        if shapes.is_empty() {
            continue;
        }
        let geom = mesh
            .geometry
            .as_ref()
            .ok_or_else(|| format!("Missing mesh geometry: {tid}"))?;
        let branch = collect_deformer_branch(mesh.parent.as_deref(), cache)?;
        let lowered_carrier = mesh.parent.as_deref() != material.parent.as_deref();
        let parent_ok = !lowered_carrier
            || (mesh.parent.is_some()
                && (material.parent.is_none()
                    || branch.iter().any(|d| match d {
                        Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => {
                            Some(id.as_str()) == material.parent.as_deref()
                        }
                    })));
        let shape_overlap = mesh
            .shapes
            .iter()
            .any(|b| shapes.iter().any(|s| s.parameter == b.parameter));
        if !parent_ok
            || geom.positions.len() != positions.len()
            || geom.uvs != raw.uvs
            || geom.indices != raw.indices
            || shape_overlap
        {
            return Err(format!(
                "Brow and lid v2 requires the fitted eye source mesh and parent frame: {tid}"
            ));
        }
        if !lowered_carrier {
            let expected_pos =
                original_positions(positions, material.parent.as_deref(), material.frame);
            if !approx_eq_slice(&geom.positions, &expected_pos, 1e-4) {
                return Err(format!(
                    "Brow and lid v2 requires the fitted eye source mesh and parent frame: {tid}"
                ));
            }
        }
        for s in &shapes {
            let param = frame.parameters.iter().find(|p| p.id == s.parameter);
            match param {
                None => {
                    if !s.parameter.ends_with("Angle") {
                        return Err("Brow and lid v2 requires ordinary fitted brow axes".into());
                    }
                }
                Some(p) => {
                    let on_brow = brow_meshes.iter().any(|bm| {
                        bm.offsets
                            .as_ref()
                            .is_some_and(|g| g.axes.iter().any(|a| a.parameter == s.parameter))
                    });
                    if p.blend
                        || p.default != 0.0
                        || p.min != -1.0
                        || p.max != 1.0
                        || (!s.parameter.ends_with("Angle") && !on_brow)
                    {
                        return Err("Brow and lid v2 requires ordinary fitted brow axes".into());
                    }
                }
            }
        }

        let scaled_shapes = if !lowered_carrier {
            shapes
        } else {
            let mapping =
                check_neutral_world(&mesh, &branch, &live_defaults, positions, &inputs, tid)?;
            let corners = mapping.local_to_world(&[0.0, 0.0, 1.0, 1.0]);
            let cw = (corners[2] - corners[0]).abs().max(1e-4);
            let ch = (corners[3] - corners[1]).abs().max(1e-4);
            let rw = if material.parent.is_none() {
                1.0
            } else {
                material.frame.width()
            };
            let rh = if material.parent.is_none() {
                1.0
            } else {
                material.frame.height()
            };
            let sx = rw / cw;
            let sy = rh / ch;
            shapes
                .into_iter()
                .map(|b| BlendBinding {
                    parameter: b.parameter,
                    keys: b.keys,
                    neutral_index: b.neutral_index,
                    limits: b.limits,
                    shapes: b
                        .shapes
                        .into_iter()
                        .map(|f| {
                            f.map(|s| MeshShape {
                                deltas: s
                                    .deltas
                                    .iter()
                                    .enumerate()
                                    .map(|(k, &v)| v * if k % 2 == 0 { sx } else { sy })
                                    .collect(),
                                draw_order: s.draw_order,
                                opacity: s.opacity,
                                multiply: s.multiply,
                                screen: s.screen,
                            })
                        })
                        .collect(),
                })
                .collect()
        };

        let native_id = obj_get(native_ids, tid)
            .and_then(Json::as_str)
            .ok_or_else(|| format!("Missing native_id for {tid}"))?;
        let carrier_label = obj_get(names, tid)
            .and_then(Json::as_str)
            .ok_or_else(|| format!("Missing carrier_name for {tid}"))?;

        let mut model = RigIR::default();
        model.canvas = frame.canvas.clone();
        model.parameters = frame.parameters.clone();
        model.deformers = branch;
        let mut head_mesh = mesh.clone();
        head_mesh.id = native_id.to_string();
        head_mesh
            .shapes
            .extend(neutralized_shapes(scaled_shapes, &mesh, &frame.parameters, false));
        model.meshes.push(head_mesh);
        model.meshes.extend(brow_meshes.clone());

        let lowered = lowering::lawful(&model, |_, _| carrier_label.to_string());
        let added = &lowered.deformers[model.deformers.len()..];
        let mut remap: HashMap<String, String> = HashMap::new();
        for c in added {
            let orig_id = match c {
                Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.as_str(),
            };
            remap.insert(
                orig_id.to_string(),
                output_id(req, &format!("shared/deformer:{orig_id}")),
            );
        }
        for c in added {
            let mut remapped = c.clone();
            match &mut remapped {
                Deformer::Warp { id, parent, .. } | Deformer::Rotation { id, parent, .. } => {
                    *id = remap[id.as_str()].clone();
                    if let Some(p) = parent {
                        if let Some(np) = remap.get(p.as_str()) {
                            *p = np.clone();
                        }
                    }
                }
            }
            let cid = match &remapped {
                Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.clone(),
            };
            if let Some(existing) = carriers.get(&cid) {
                if existing != &remapped {
                    return Err(format!("Incompatible shared eye carrier: {cid}"));
                }
            } else {
                carriers.insert(cid, remapped);
            }
        }

        let mut after = lowered.meshes.into_iter().next().unwrap();
        after.id = mesh.id.clone();
        if let Some(p) = &mut after.parent {
            if let Some(np) = remap.get(p.as_str()) {
                *p = np.clone();
            }
        }
        if after != mesh {
            out_objects.push((format!("mesh:{tid}"), encode_object_mesh(&after)));
        }
    }

    for (cid, carrier) in carriers {
        out_objects.push((format!("deformer:{cid}"), encode_object_deformer(&carrier)));
    }
    Ok(ok_objects(out_objects))
}
