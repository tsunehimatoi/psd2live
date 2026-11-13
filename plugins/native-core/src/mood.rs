use crate::output_id;
use crate::rig_ir::{
    channel_static, decode_object_frame, decode_object_mesh, decode_rig_binary, encode_object_frame,
    encode_object_mesh, grid_ref, objects_response, residual_channel_grid, set_channel,
    sync_parameters_from_tree, ChannelValue, GroupLabel, KeyAxis, KeyCell, KeyGrid, Mesh, ObjectCache,
    Parameter, ParameterNode, Rgb, RigIR, CHANNEL_MULTIPLY_COLOR, CHANNEL_OPACITY,
};
use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};

const PARAM_CHEEK: &str = "ParamCheek";
const PARAM_GLOOM: &str = "ParamGloom";

fn cheek_kind_warmth(kind: &str) -> Result<(f32, bool), String> {
    match kind {
        "FACE" | "NOSE" => Ok((0.06, true)),
        "EARS" => Ok((0.05, true)),
        "BLUSH" => Ok((0.3, true)),
        "FACE_DETAIL" => Ok((0.0, true)),
        "OTHER" => Ok((0.0, false)),
        _ => Err(format!("Unknown cheek layer kind: {kind}")),
    }
}

fn mood_channels_apply(
    warmth: f32,
    darkness: f32,
    highlight: bool,
    cheek: bool,
    gloom: bool,
) -> (Option<KeyGrid<ChannelValue>>, Option<KeyGrid<ChannelValue>>) {
    if !cheek && !gloom {
        return (None, None);
    }
    let mut axes = Vec::new();
    if cheek && warmth > 0.0 {
        axes.push(KeyAxis {
            parameter: PARAM_CHEEK.to_string(),
            keys: vec![0.0, 1.0],
        });
    }
    if gloom && darkness > 0.0 {
        axes.push(KeyAxis {
            parameter: PARAM_GLOOM.to_string(),
            keys: vec![0.0, 1.0],
        });
    }
    let multiply = if axes.is_empty() {
        None
    } else {
        let mut cells = Vec::new();
        let has_cheek = axes.first().map(|a| a.parameter.as_str()) == Some(PARAM_CHEEK);
        let has_gloom = axes.last().map(|a| a.parameter.as_str()) == Some(PARAM_GLOOM);
        fn visit(
            index: usize,
            axes_len: usize,
            coord: &mut Vec<i32>,
            warmth: f32,
            darkness: f32,
            has_cheek: bool,
            has_gloom: bool,
            cells: &mut Vec<KeyCell<ChannelValue>>,
        ) {
            if index < axes_len {
                for k in 0..=1 {
                    coord[index] = k;
                    visit(index + 1, axes_len, coord, warmth, darkness, has_cheek, has_gloom, cells);
                }
            } else {
                let c = if has_cheek { coord[0] as f32 * warmth } else { 0.0 };
                let g = if has_gloom { *coord.last().unwrap() as f32 } else { 0.0 } * darkness * 0.13;
                cells.push(KeyCell {
                    coordinate: coord.clone(),
                    form: ChannelValue::Color(Rgb {
                        r: (1.0 - g).clamp(0.0, 1.0),
                        g: (1.0 - c - g).clamp(0.0, 1.0),
                        b: (1.0 - c - 0.6 * g).clamp(0.0, 1.0),
                    }),
                });
            }
        }
        let mut coord = vec![0i32; axes.len()];
        visit(0, axes.len(), &mut coord, warmth, darkness, has_cheek, has_gloom, &mut cells);
        Some(KeyGrid { axes, cells })
    };
    let opacity = if gloom && highlight {
        Some(KeyGrid {
            axes: vec![KeyAxis {
                parameter: PARAM_GLOOM.to_string(),
                keys: vec![0.0, 1.0],
            }],
            cells: vec![
                KeyCell { coordinate: vec![0], form: ChannelValue::Scalar(1.0) },
                KeyCell { coordinate: vec![1], form: ChannelValue::Scalar(1.0 - 0.85) },
            ],
        })
    } else {
        None
    };
    (multiply, opacity)
}

fn ensure_mood_parameter(
    frame: &mut RigIR,
    id: &str,
    name: &str,
    folder_id: &str,
    folder_name: &str,
) -> Result<bool, String> {
    if let Some(existing) = frame.parameters.iter().find(|p| p.id == id) {
        if existing.min != 0.0
            || existing.max != 1.0
            || existing.default != 0.0
            || existing.blend
            || existing.repeat
            || existing.keys.is_some()
        {
            return Err(format!("Incompatible mood parameter {id}"));
        }
        return Ok(false);
    }
    frame.parameters.push(Parameter {
        id: id.to_string(),
        name: name.to_string(),
        min: 0.0,
        max: 1.0,
        default: 0.0,
        blend: false,
        repeat: false,
        keys: None,
    });
    let mut tree = if frame.parameter_tree.is_empty() {
        frame.parameters.iter().map(|p| ParameterNode::Param(p.id.clone())).collect()
    } else {
        std::mem::take(&mut frame.parameter_tree)
    };
    fn insert(nodes: &mut [ParameterNode], folder_id: &str, pid: &str) -> bool {
        for node in nodes.iter_mut() {
            if let ParameterNode::Group { id, children, .. } = node {
                if id == folder_id {
                    children.push(ParameterNode::Param(pid.to_string()));
                    return true;
                }
                if insert(children, folder_id, pid) {
                    return true;
                }
            }
        }
        false
    }
    if !insert(&mut tree, folder_id, id) {
        tree.push(ParameterNode::Group {
            id: folder_id.to_string(),
            name: folder_name.to_string(),
            open: true,
            children: vec![ParameterNode::Param(id.to_string())],
            label: GroupLabel::None,
        });
    }
    frame.parameter_tree = tree;
    sync_parameters_from_tree(frame);
    Ok(true)
}

pub fn handle_cheek(req: &Request, cache: &ObjectCache) -> Response {
    let Some(ctx) = req.context_values() else {
        return Response::Error("Missing cheek source measurements".into());
    };
    if ctx.get("schema").and_then(Json::as_str) != Some("expression-cheek/1") {
        return Response::Error("Missing cheek source measurements".into());
    }
    let has_face = ctx.get("has_face").and_then(Json::as_bool).unwrap_or(false);
    let param_name = ctx.get("parameter_name").and_then(Json::as_str).unwrap_or("Cheek");
    let folder_name = ctx.get("folder_name").and_then(Json::as_str).unwrap_or("Face");
    let Some(ctx_targets) = ctx.get("targets").and_then(Json::as_obj) else {
        return Response::Error("Missing cheek targets".into());
    };

    let strength = req
        .config()
        .get("values")
        .and_then(|v| v.get("strength"))
        .and_then(Json::as_f64)
        .unwrap_or(100.0) as f32;
    if !strength.is_finite() || !(0.0..=200.0).contains(&strength) {
        return Response::Error("Cheek strength must be within 0..200".into());
    }

    let targets: Vec<String> = req
        .header
        .get("targets")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(str::to_string))
        .collect();
    for tid in &targets {
        if !ctx_targets.iter().any(|(k, _)| k == tid) {
            return Response::Error("Missing cheek target measurement".into());
        }
    }

    let mesh_order: Vec<String> = req.mesh_order().iter().filter_map(Json::as_str).map(str::to_string).collect();
    let deformer_order: Vec<String> = req
        .header
        .get("deformer_order")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(Json::as_str)
        .map(str::to_string)
        .collect();
    if !has_face {
        return objects_response(HashMap::new(), mesh_order, deformer_order);
    }

    let mut frame = match cache.get("frame").and_then(|b| decode_object_frame(b).map_err(|e| e.to_string())) {
        Ok(f) => f,
        Err(e) => return Response::Error(e),
    };
    let folder_id = output_id(req, "parameter-folder:ParamGroupFace");
    let frame_changed = match ensure_mood_parameter(&mut frame, PARAM_CHEEK, param_name, &folder_id, folder_name) {
        Ok(c) => c,
        Err(e) => return Response::Error(e),
    };
    let live: HashMap<String, f32> = frame.parameters.iter().map(|p| (p.id.clone(), p.default)).collect();

    let mut changed_blobs = HashMap::new();
    for tid in targets {
        let Some(layer) = ctx_targets.iter().find(|(k, _)| k == &tid).map(|(_, v)| v) else { continue };
        let generated_multiply = layer.get("generated_multiply_track").and_then(Json::as_bool).unwrap_or(false);
        let kind = layer.get("kind").and_then(Json::as_str).unwrap_or("");
        let (warmth_factor, skin) = match cheek_kind_warmth(kind) {
            Ok(v) => v,
            Err(e) => return Response::Error(e),
        };
        if generated_multiply || warmth_factor == 0.0 {
            continue;
        }
        let prior_cheek = layer.get("prior_cheek").and_then(Json::as_f64).map(|v| v as f32);
        let prior_gloom = layer.get("prior_gloom").and_then(Json::as_f64).map(|v| v as f32);
        let dark = (if skin { 1.0 } else { 0.0 }) * prior_gloom.unwrap_or(0.0) / 100.0;

        let (before_grid, _) = mood_channels_apply(
            warmth_factor * prior_cheek.unwrap_or(0.0) / 100.0,
            dark,
            false,
            prior_cheek.is_some(),
            prior_gloom.is_some(),
        );
        let (after_grid, _) = mood_channels_apply(
            warmth_factor * strength / 100.0,
            dark,
            false,
            true,
            prior_gloom.is_some(),
        );
        if before_grid == after_grid {
            continue;
        }
        let mut mesh = match cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))
        {
            Ok(m) => m,
            Err(e) => return Response::Error(e),
        };
        let actual_grid = grid_ref(&mesh.channels, CHANNEL_MULTIPLY_COLOR).cloned();
        let white = Rgb { r: 1.0, g: 1.0, b: 1.0 };
        let next_grid = if actual_grid == before_grid && mesh.multiply == white {
            after_grid
        } else {
            let white_val = ChannelValue::Color(white);
            Some(residual_channel_grid(
                &live,
                actual_grid.as_ref(),
                &ChannelValue::Color(mesh.multiply.clone()),
                before_grid.as_ref(),
                &white_val,
                after_grid.as_ref(),
                &white_val,
            ))
        };
        set_channel(&mut mesh.channels, CHANNEL_MULTIPLY_COLOR, next_grid);
        changed_blobs.insert(format!("mesh:{tid}"), encode_object_mesh(&mesh));
    }
    if frame_changed {
        changed_blobs.insert("frame".into(), encode_object_frame(&frame));
    }
    objects_response(changed_blobs, mesh_order, deformer_order)
}

fn require_compatible_lowering(mesh: &Mesh, channels: &[(u32, KeyGrid<ChannelValue>)]) -> Result<(), String> {
    let Some(ref geom) = mesh.geometry else { return Ok(()) };
    let Some(ref offsets) = mesh.offsets else { return Ok(()) };
    if offsets.axes.is_empty() {
        return Ok(());
    }
    let collect_axes = |ch: &[(u32, KeyGrid<ChannelValue>)]| -> Vec<(String, Vec<f32>)> {
        let mut seen = HashSet::new();
        let mut out = Vec::new();
        for a in offsets.axes.iter().chain(ch.iter().flat_map(|(_, g)| g.axes.iter())) {
            if seen.insert(a.parameter.clone()) {
                out.push((a.parameter.clone(), a.keys.clone()));
            }
        }
        out
    };
    let before = collect_axes(&mesh.channels);
    let after = collect_axes(channels);
    if before == after {
        return Ok(());
    }
    const MAX_CELLS: usize = 256;
    const MAX_VALUES: usize = 120_000;
    let limit = MAX_CELLS.min(MAX_VALUES / geom.positions.len().max(1));
    let mut cells = 1usize;
    for (_, keys) in &after {
        cells = (cells * keys.len().max(1)).min(MAX_CELLS + 1);
    }
    if cells > limit {
        return Err(format!("Gloom v2 would exceed the keyform budget for {}; use Gloom v1 for this binding", mesh.id));
    }
    Ok(())
}

pub fn handle_gloom(req: &Request, cache: &ObjectCache) -> Response {
    let version = req.header.get("version").and_then(Json::as_i64).unwrap_or(1);
    let Some(ctx) = req.context_values() else {
        return Response::Error("Missing gloom author inputs".into());
    };
    if ctx.get("schema").and_then(Json::as_str) != Some("expression.gloom/1") {
        return Response::Error("Missing gloom author inputs".into());
    }
    let applies = ctx.get("applies").and_then(Json::as_bool).unwrap_or(false);
    let param_name = ctx.get("parameter_name").and_then(Json::as_str).unwrap_or("Gloom");
    let group_name = ctx.get("group_name").and_then(Json::as_str).unwrap_or("Face");
    let Some(ctx_layers) = ctx.get("layers").and_then(Json::as_obj) else {
        return Response::Error("Missing gloom layers".into());
    };
    let eye_highlights: HashSet<String> = ctx
        .get("eye_highlights")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(Json::as_str)
        .map(str::to_string)
        .collect();
    let neutral_bytes = req
        .header
        .get("context")
        .and_then(|c| c.get("resources"))
        .and_then(|r| r.get("neutral_channels"))
        .and_then(|n| n.get("inline"))
        .and_then(Json::as_str)
        .and_then(|k| req.blobs.get(k));
    let Some(neutral_bytes) = neutral_bytes else {
        return Response::Error("Missing gloom neutral_channels resource".into());
    };
    let neutral_rig = match decode_rig_binary(neutral_bytes) {
        Ok(r) => r,
        Err(e) => return Response::Error(format!("Failed to decode gloom neutral_channels: {e}")),
    };
    let neutral_by_id: HashMap<String, Mesh> = neutral_rig.meshes.into_iter().map(|m| (m.id.clone(), m)).collect();

    let mesh_order: Vec<String> = req.mesh_order().iter().filter_map(Json::as_str).map(str::to_string).collect();
    let deformer_order: Vec<String> = req
        .header
        .get("deformer_order")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(Json::as_str)
        .map(str::to_string)
        .collect();
    if !applies {
        return objects_response(HashMap::new(), mesh_order, deformer_order);
    }

    let strength = req
        .config()
        .get("values")
        .and_then(|v| v.get("strength"))
        .and_then(Json::as_f64)
        .unwrap_or(100.0) as f32;
    if !strength.is_finite() || !(0.0..=200.0).contains(&strength) {
        return Response::Error("Gloom strength must be within 0..200".into());
    }

    let mut frame = match cache.get("frame").and_then(|b| decode_object_frame(b).map_err(|e| e.to_string())) {
        Ok(f) => f,
        Err(e) => return Response::Error(e),
    };
    let folder_id = output_id(req, "parameter-folder:ParamGroupFace");
    let frame_changed = match ensure_mood_parameter(&mut frame, PARAM_GLOOM, param_name, &folder_id, group_name) {
        Ok(c) => c,
        Err(e) => return Response::Error(e),
    };
    let live: HashMap<String, f32> = frame.parameters.iter().map(|p| (p.id.clone(), p.default)).collect();

    let targets: Vec<String> = req
        .header
        .get("targets")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(str::to_string))
        .collect();

    let mut changed_blobs = HashMap::new();
    for tid in targets {
        let Some(layer) = ctx_layers.iter().find(|(k, _)| k == &tid).map(|(_, v)| v) else {
            return Response::Error(format!("Missing gloom measurement: {tid}"));
        };
        let Some(neutral) = neutral_by_id.get(&tid) else {
            return Response::Error(format!("Missing gloom neutral mesh: {tid}"));
        };
        let mut mesh = match cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))
        {
            Ok(m) => m,
            Err(e) => return Response::Error(e),
        };
        let skin = layer.get("skin").and_then(Json::as_bool).unwrap_or(false);
        let highlight = layer.get("highlight").and_then(Json::as_bool).unwrap_or(false);
        let cheek_warmth = layer.get("cheek_warmth").and_then(Json::as_f64).unwrap_or(0.0) as f32;
        let prior_cheek = layer.get("preceding_cheek_strength").and_then(Json::as_f64).map(|v| v as f32);
        let prior_gloom = layer.get("preceding_gloom_strength").and_then(Json::as_f64).map(|v| v as f32);

        let warmth = cheek_warmth * prior_cheek.unwrap_or(0.0) / 100.0;
        let dark_prev = (if skin { 1.0 } else { 0.0 }) * prior_gloom.unwrap_or(0.0) / 100.0;
        let dark_next = (if skin { 1.0 } else { 0.0 }) * strength / 100.0;

        let (prev_mul, prev_op) = mood_channels_apply(warmth, dark_prev, highlight, prior_cheek.is_some(), prior_gloom.is_some());
        let (des_mul, des_op) = mood_channels_apply(warmth, dark_next, highlight, prior_cheek.is_some(), true);

        let mut before_ch: Vec<(u32, KeyGrid<ChannelValue>)> = neutral.channels.clone();
        if before_ch.iter().all(|(c, _)| *c != CHANNEL_MULTIPLY_COLOR) {
            if let Some(g) = prev_mul {
                before_ch.push((CHANNEL_MULTIPLY_COLOR, g));
            }
        }
        if before_ch.iter().all(|(c, _)| *c != CHANNEL_OPACITY) {
            if let Some(g) = prev_op {
                before_ch.push((CHANNEL_OPACITY, g));
            }
        }
        let mut after_ch: Vec<(u32, KeyGrid<ChannelValue>)> = neutral.channels.clone();
        if after_ch.iter().all(|(c, _)| *c != CHANNEL_MULTIPLY_COLOR) {
            if let Some(g) = des_mul {
                after_ch.push((CHANNEL_MULTIPLY_COLOR, g));
            }
        }
        if after_ch.iter().all(|(c, _)| *c != CHANNEL_OPACITY) {
            if let Some(g) = des_op {
                after_ch.push((CHANNEL_OPACITY, g));
            }
        }
        if before_ch == after_ch {
            continue;
        }

        let computed = if mesh.channels == before_ch
            && mesh.opacity == neutral.opacity
            && mesh.draw_order == neutral.draw_order
            && mesh.multiply == neutral.multiply
            && mesh.screen == neutral.screen
        {
            after_ch
        } else {
            let mut ch_ids: Vec<u32> = Vec::new();
            for (c, _) in mesh.channels.iter().chain(before_ch.iter()).chain(after_ch.iter()) {
                if !ch_ids.contains(c) {
                    ch_ids.push(*c);
                }
            }
            let mut out = Vec::with_capacity(ch_ids.len());
            for ch in ch_ids {
                let sa = channel_static(&mesh, ch);
                let sp = channel_static(neutral, ch);
                let g = residual_channel_grid(
                    &live,
                    grid_ref(&mesh.channels, ch),
                    &sa,
                    grid_ref(&before_ch, ch),
                    &sp,
                    grid_ref(&after_ch, ch),
                    &sp,
                );
                out.push((ch, g));
            }
            out
        };

        let next_channels = if version >= 2 {
            let eye_owns_opacity = eye_highlights.contains(&tid)
                && grid_ref(&mesh.channels, CHANNEL_OPACITY)
                    .is_some_and(|g| g.axes.iter().any(|a| a.parameter == "ParamEyeLOpen" || a.parameter == "ParamEyeROpen"));
            let mut owned = HashSet::new();
            if skin && grid_ref(&neutral.channels, CHANNEL_MULTIPLY_COLOR).is_none() {
                owned.insert(CHANNEL_MULTIPLY_COLOR);
            }
            if highlight && prior_gloom.is_none() && grid_ref(&neutral.channels, CHANNEL_OPACITY).is_none() && !eye_owns_opacity {
                owned.insert(CHANNEL_OPACITY);
            }
            let mut merged: Vec<(u32, KeyGrid<ChannelValue>)> = mesh
                .channels
                .iter()
                .filter(|(c, _)| !owned.contains(c))
                .cloned()
                .collect();
            let has_generated_mul = mesh.multiply == neutral.multiply && {
                let (gen_mul, _) = mood_channels_apply(warmth, dark_prev, false, prior_cheek.is_some(), prior_gloom.is_some());
                let mut gen_grid = grid_ref(&neutral.channels, CHANNEL_MULTIPLY_COLOR).cloned();
                if gen_grid.is_none() {
                    gen_grid = gen_mul;
                }
                gen_grid.as_ref() == grid_ref(&mesh.channels, CHANNEL_MULTIPLY_COLOR)
            };
            for (ch, mut grid) in computed {
                if !owned.contains(&ch) {
                    continue;
                }
                if ch == CHANNEL_MULTIPLY_COLOR
                    && grid.axes.len() == 2
                    && grid.axes[0].parameter == PARAM_CHEEK
                    && grid.axes[1].parameter == PARAM_GLOOM
                    && has_generated_mul
                {
                    let stride1 = grid.axes[1].keys.len();
                    grid.cells.sort_by_key(|c| c.coordinate[0] as usize * stride1 + c.coordinate[1] as usize);
                }
                merged.push((ch, grid));
            }
            if let Err(e) = require_compatible_lowering(&mesh, &merged) {
                return Response::Error(e);
            }
            merged
        } else {
            computed
        };

        if next_channels != mesh.channels {
            mesh.channels = next_channels;
            changed_blobs.insert(format!("mesh:{tid}"), encode_object_mesh(&mesh));
        }
    }
    if frame_changed {
        changed_blobs.insert("frame".into(), encode_object_frame(&frame));
    }
    objects_response(changed_blobs, mesh_order, deformer_order)
}
