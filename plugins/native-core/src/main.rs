mod brow;
mod eye_squash;
mod lid_fade;
mod mood;
mod rig_ir;
mod simulation;
mod space;
mod swing;

use psd2live_plugin_sdk::{load_dependencies, run, Json, Request, Response};
use std::collections::{HashMap, HashSet};

fn hex_encode(s: &str) -> String {
    let mut hex = String::with_capacity(s.len() * 2);
    for b in s.as_bytes() {
        use std::fmt::Write;
        let _ = write!(hex, "{:02x}", b);
    }
    hex
}

fn output_id(req: &Request, local_name: &str) -> String {
    if let Some(alias) = req
        .header
        .get("invocation")
        .and_then(|inv| inv.get("outputAliases"))
        .and_then(|aliases| aliases.get(local_name))
        .and_then(Json::as_str)
    {
        return alias.to_string();
    }
    let inst_id = req.header.get("instance").and_then(Json::as_str).unwrap_or("unknown");
    format!("modifier:{inst_id}:{}", hex_encode(local_name))
}

fn handle_physics(req: &Request) -> Response {
    let config = req.config();
    let Some(entries) = config.as_obj() else {
        return Response::Error("Physics config must be an object".into());
    };
    let inputs = config.get("inputs").and_then(Json::as_arr).unwrap_or(&[]);
    let outputs = config.get("outputs").and_then(Json::as_arr).unwrap_or(&[]);
    if inputs.is_empty() || outputs.is_empty() {
        return Response::Error("Physics inputs and outputs are required".into());
    }
    let available: HashSet<&str> = req
        .parameters()
        .iter()
        .filter_map(|p| p.get("id").and_then(Json::as_str))
        .collect();
    for item in inputs.iter().chain(outputs.iter()) {
        let Some(param) = item.get("parameter").and_then(Json::as_str) else {
            return Response::Error("Physics input/output parameter is required".into());
        };
        if !available.contains(param) {
            return Response::Error(format!("Physics parameter missing: {param}"));
        }
    }
    let mut updated: Vec<(String, Json)> = entries.iter().filter(|(k, _)| k != "id").cloned().collect();
    updated.push(("id".into(), Json::Str(output_id(req, "physics"))));
    Response::Commands(vec![Json::Obj(vec![
        ("op".into(), Json::Str("physics_group_add".into())),
        ("group".into(), Json::Obj(updated)),
    ])])
}

fn handle_motion(req: &Request) -> Response {
    let config = req.config();
    if config.get("enabled").and_then(Json::as_bool) == Some(false) {
        return Response::Error("Disable the modifier to disable its motion".into());
    }
    let curves = config.get("curves").and_then(Json::as_arr).unwrap_or(&[]);
    if curves.is_empty() {
        return Response::Error("Motion parameters are missing".into());
    }
    let available: HashSet<&str> = req
        .parameters()
        .iter()
        .filter_map(|p| p.get("id").and_then(Json::as_str))
        .collect();
    for curve in curves {
        let Some(pid) = curve.get("parameter").and_then(Json::as_str) else {
            return Response::Error("Motion curve missing parameter".into());
        };
        if !available.contains(pid) {
            return Response::Error(format!("Motion parameter missing: {pid}"));
        }
    }
    let inst_id = req.header.get("instance").and_then(Json::as_str).unwrap_or("unknown");
    let clip_id = output_id(req, "motion");
    let file = config
        .get("file")
        .and_then(Json::as_str)
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("modifier_{}", hex_encode(inst_id)));

    for existing in req.clips() {
        let eid = existing.get("id").and_then(Json::as_str).unwrap_or("");
        let efile = existing.get("file").and_then(Json::as_str).unwrap_or("");
        if eid == clip_id || efile.eq_ignore_ascii_case(&file) {
            return Response::Error(format!("Motion ID or file collision: {clip_id} / {file}"));
        }
    }

    if let Some(roles) = config.get("parameter_roles").and_then(Json::as_obj) {
        let existing_roles: HashMap<&str, Vec<&str>> = req
            .parameter_roles()
            .iter()
            .filter_map(|r| {
                let role = r.get("role")?.as_str()?;
                let params: Vec<&str> = r.get("parameters")?.as_arr()?.iter().filter_map(Json::as_str).collect();
                Some((role, params))
            })
            .collect();
        for (role, params_json) in roles {
            let Some(params_arr) = params_json.as_arr() else {
                return Response::Error(format!("Invalid parameters for role {role}"));
            };
            let mut params = Vec::with_capacity(params_arr.len());
            for p in params_arr {
                let Some(ps) = p.as_str() else {
                    return Response::Error(format!("Invalid parameter in role {role}"));
                };
                if !available.contains(ps) {
                    return Response::Error(format!("Motion role {role} references missing parameters"));
                }
                params.push(ps);
            }
            if let Some(prev) = existing_roles.get(role.as_str()) {
                if prev != &params {
                    return Response::Error(format!("Conflicting motion parameter role {role}"));
                }
            }
        }
    }

    Response::Commands(vec![Json::Obj(vec![
        ("op".into(), Json::Str("motion_clip_add".into())),
        ("id".into(), Json::Str(clip_id)),
        ("file".into(), Json::Str(file)),
        ("clip".into(), config.clone()),
    ])])
}

fn parse_bounds(val: &Json) -> Option<[f64; 4]> {
    let arr = val.as_arr()?;
    if arr.len() != 4 { return None; }
    let a = [arr[0].as_f64()?, arr[1].as_f64()?, arr[2].as_f64()?, arr[3].as_f64()?];
    if a.iter().all(|v| v.is_finite()) { Some(a) } else { None }
}

fn handle_skin_clip(req: &Request) -> Response {
    let Some(source) = req.context_values() else {
        return Response::Error("Missing skin clipping author inputs".into());
    };
    if source.get("schema").and_then(Json::as_str) != Some("skin-clip/1") {
        return Response::Error("Missing skin clipping author inputs".into());
    }
    let faces = source.get("faces").and_then(Json::as_arr).unwrap_or(&[]);
    if faces.is_empty() {
        return Response::Commands(Vec::new());
    }
    let mut masks = Vec::with_capacity(faces.len());
    let mut left = f64::INFINITY;
    let mut top = f64::INFINITY;
    let mut right = f64::NEG_INFINITY;
    let mut bottom = f64::NEG_INFINITY;
    for face in faces {
        let Some(id) = face.get("id").and_then(Json::as_str) else {
            return Response::Error("Invalid skin clipping face id".into());
        };
        let Some(b) = face.get("bounds").and_then(parse_bounds) else {
            return Response::Error("Invalid skin clipping bounds".into());
        };
        masks.push(id.to_string());
        left = left.min(b[0]);
        top = top.min(b[1]);
        right = right.max(b[2]);
        bottom = bottom.max(b[3]);
    }
    let Some(overlays) = source.get("overlays") else {
        return Response::Error("Missing skin clipping overlays".into());
    };
    let prior: HashSet<&str> = source
        .get("already_active")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(Json::as_str)
        .collect();
    let mesh_ids: HashSet<&str> = req.mesh_order().iter().filter_map(Json::as_str).collect();
    let mesh_by_id: HashMap<&str, &Json> = req
        .meshes()
        .iter()
        .filter_map(|m| Some((m.get("id")?.as_str()?, m)))
        .collect();

    let mut commands = Vec::new();
    for target in req.targets() {
        let Some(tid) = target.get("id").and_then(Json::as_str) else { continue; };
        if prior.contains(tid) { continue; }
        let Some(encoded) = overlays.get(tid) else { continue; };
        let Some(mesh) = mesh_by_id.get(tid) else { continue; };
        let Some(b) = parse_bounds(encoded) else {
            return Response::Error("Invalid skin clipping bounds".into());
        };
        let w = (b[2].min(right) - b[0].max(left)).max(0.0);
        let h = (b[3].min(bottom) - b[1].max(top)).max(0.0);
        if w * h < 0.85 * (b[2] - b[0]) * (b[3] - b[1]) {
            continue;
        }
        let invert = mesh.get("invertMask").and_then(Json::as_bool).unwrap_or(false);
        let existing_masks: Vec<&str> = mesh
            .get("maskedBy")
            .and_then(Json::as_arr)
            .unwrap_or(&[])
            .iter()
            .filter_map(Json::as_str)
            .collect();
        let mask_refs: Vec<&str> = masks.iter().map(String::as_str).collect();
        if invert || (!existing_masks.is_empty() && existing_masks != mask_refs) {
            return Response::Error(format!("Incompatible authored masks on {tid}"));
        }
        if !masks.iter().all(|m| mesh_ids.contains(m.as_str())) {
            return Response::Error("Skin clipping face mesh is missing".into());
        }
        if existing_masks != mask_refs {
            commands.push(Json::Obj(vec![
                ("op".into(), Json::Str("mesh_masked_by".into())),
                ("id".into(), Json::Str(tid.to_string())),
                ("maskedBy".into(), Json::Arr(masks.iter().cloned().map(Json::Str).collect())),
            ]));
        }
    }
    Response::Commands(commands)
}

fn f64_field(obj: &Json, key: &str, default: f64) -> f64 {
    obj.get(key).and_then(Json::as_f64).unwrap_or(default)
}

fn handle_see_through(req: &Request) -> Response {
    let Some(source) = req.context_values() else {
        return Response::Error("Missing see-through author inputs".into());
    };
    if source.get("schema").and_then(Json::as_str) != Some("see-through/1") {
        return Response::Error("Missing see-through author inputs".into());
    }
    let eyes = source.get("eyes").and_then(Json::as_bool).unwrap_or(false);
    let Some(pattern) = source.get("name_pattern").and_then(Json::as_str) else {
        return Response::Error("Invalid see-through name pattern".into());
    };
    if pattern.split("{meshName}").count() != 2 {
        return Response::Error("Invalid see-through name pattern".into());
    }
    let draw_order_step = f64_field(&source, "draw_order_step", 2.0);
    let inverted_masks = source.get("inverted_masks").and_then(Json::as_bool).unwrap_or(true);
    let opacity = req
        .config()
        .get("values")
        .and_then(|v| v.get("opacity"))
        .and_then(Json::as_f64)
        .unwrap_or(50.0);
    if !opacity.is_finite() || !(0.0..=100.0).contains(&opacity) {
        return Response::Error("See-through opacity must be within 0..100".into());
    }
    let alpha = opacity / 100.0;

    let order_index: HashMap<&str, usize> = req
        .mesh_order()
        .iter()
        .enumerate()
        .filter_map(|(i, v)| Some((v.as_str()?, i)))
        .collect();
    let mesh_by_id: HashMap<&str, &Json> = req
        .meshes()
        .iter()
        .filter_map(|m| Some((m.get("id")?.as_str()?, m)))
        .collect();

    let mut earlier_prefixes = Vec::new();
    let mut earlier_aliases = HashSet::new();
    for owner in source.get("earlier_owners").and_then(Json::as_arr).unwrap_or(&[]) {
        if let Some(oid) = owner.get("id").and_then(Json::as_str) {
            earlier_prefixes.push(format!("modifier:{oid}:{}", hex_encode("see-through:")));
        }
        if let Some(aliases) = owner.get("outputAliases").and_then(Json::as_obj) {
            for (k, v) in aliases {
                if k.starts_with("see-through:") {
                    if let Some(id) = v.as_str() {
                        earlier_aliases.insert(id.to_string());
                    }
                }
            }
        }
    }
    let earlier: HashSet<&str> = order_index
        .keys()
        .copied()
        .filter(|id| earlier_aliases.contains(*id) || earlier_prefixes.iter().any(|p| id.starts_with(p.as_str())))
        .collect();

    let targets: HashSet<&str> = req
        .targets()
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str))
        .collect();

    let mut candidates: Vec<(&str, Vec<&str>)> = Vec::new();
    for item in source.get("candidates").and_then(Json::as_arr).unwrap_or(&[]) {
        let Some(orig) = item.get("original").and_then(Json::as_str) else { continue; };
        let eye = item.get("eyes").and_then(Json::as_bool).unwrap_or(false);
        if eye != eyes || !targets.contains(orig) || !order_index.contains_key(orig) || earlier.contains(orig) {
            continue;
        }
        let mut bangs: Vec<&str> = item
            .get("bangs")
            .and_then(Json::as_arr)
            .unwrap_or(&[])
            .iter()
            .filter_map(Json::as_str)
            .filter(|b| order_index.contains_key(*b) && !earlier.contains(*b))
            .collect();
        if bangs.is_empty() {
            continue;
        }
        bangs.sort_by_key(|b| order_index[b]);
        candidates.push((orig, bangs));
    }
    candidates.sort_by_key(|(orig, _)| order_index[orig]);
    if candidates.is_empty() {
        return Response::Commands(Vec::new());
    }

    let half_step = (draw_order_step / 2.0).max(0.5);
    let inst_id = req.header.get("instance").and_then(Json::as_str).unwrap_or("unknown");
    let low_order = |m: &Json| f64_field(m, "lowestOrder", f64_field(m, "drawOrder", 0.0));
    let high_order = |m: &Json| f64_field(m, "highestOrder", f64_field(m, "drawOrder", 0.0));
    let is_visible = |m: &Json| m.get("visible").and_then(Json::as_bool).unwrap_or(true);
    let has_opacity_track = |m: &Json| m.get("hasOpacityTrack").and_then(Json::as_bool).unwrap_or(false);
    let has_masks = |m: &Json| !m.get("maskedBy").and_then(Json::as_arr).unwrap_or(&[]).is_empty();
    let is_inverted = |m: &Json| m.get("invertMask").and_then(Json::as_bool).unwrap_or(false);
    fn mesh_name<'a>(m: &'a Json, fallback: &'a str) -> &'a str {
        m.get("name").and_then(Json::as_str).unwrap_or(fallback)
    }

    let mut windows_order: Vec<&str> = Vec::new();
    let mut windows_parts: HashMap<&str, Vec<&str>> = HashMap::new();
    let mut commands = Vec::new();

    for (orig_id, bangs) in candidates {
        let Some(&orig) = mesh_by_id.get(orig_id) else { continue; };
        if eyes && !has_opacity_track(orig) {
            if has_masks(orig) || !inverted_masks {
                continue;
            }
            let above = high_order(orig);
            for b_id in bangs {
                let Some(&b) = mesh_by_id.get(b_id) else { continue; };
                if is_visible(b) && low_order(b) > above {
                    let entry = windows_parts.entry(b_id).or_insert_with(|| {
                        windows_order.push(b_id);
                        Vec::new()
                    });
                    if !entry.contains(&orig_id) {
                        entry.push(orig_id);
                    }
                }
            }
            continue;
        }

        let new_id = output_id(req, &format!("see-through:{orig_id}"));
        if mesh_by_id.contains_key(new_id.as_str()) {
            return Response::Error(format!("{inst_id}: see-through output conflicts with existing meshes or masks"));
        }
        let above = high_order(orig);
        let masks: Vec<&str> = bangs
            .into_iter()
            .filter(|b_id| mesh_by_id.get(b_id).is_some_and(|b| is_visible(b) && low_order(b) > above))
            .collect();
        if masks.is_empty() {
            continue;
        }
        let top = masks.iter().filter_map(|id| mesh_by_id.get(id)).map(|m| high_order(m)).fold(f64::NEG_INFINITY, f64::max);
        let next = req
            .meshes()
            .iter()
            .filter_map(|m| {
                let id = m.get("id")?.as_str()?;
                if id == orig_id || masks.contains(&id) {
                    return None;
                }
                let lo = low_order(m);
                (lo > top).then_some(lo)
            })
            .fold(None::<f64>, |acc, v| Some(acc.map_or(v, |a| a.min(v))));
        let order = (top + next.map(|n| half_step.min((n - top) / 2.0)).unwrap_or(half_step)).clamp(0.0, 1000.0);

        commands.push(Json::Obj(vec![
            ("op".into(), Json::Str("mesh_clone".into())),
            ("sourceId".into(), Json::Str(orig_id.to_string())),
            ("id".into(), Json::Str(new_id)),
            ("name".into(), Json::Str(pattern.replace("{meshName}", mesh_name(orig, orig_id)))),
            ("drawOrder".into(), Json::Num(order)),
            ("opacityScale".into(), Json::Num(alpha)),
            ("maskedBy".into(), Json::Arr(masks.into_iter().map(|s| Json::Str(s.to_string())).collect())),
            ("invertMask".into(), Json::Bool(false)),
            ("selectable".into(), Json::Bool(true)),
            ("insertBeforeOriginal".into(), Json::Bool(true)),
        ]));
    }

    for bang_id in windows_order {
        let Some(&bang) = mesh_by_id.get(bang_id) else { continue; };
        let new_id = output_id(req, &format!("see-through:{bang_id}"));
        if has_masks(bang) || is_inverted(bang) || mesh_by_id.contains_key(new_id.as_str()) {
            return Response::Error(format!("{inst_id}: see-through output conflicts with existing meshes or masks"));
        }
        let parts = &windows_parts[bang_id];
        let top = high_order(bang);
        let next = req
            .meshes()
            .iter()
            .filter_map(|m| {
                let id = m.get("id")?.as_str()?;
                if id == bang_id {
                    return None;
                }
                let lo = low_order(m);
                (lo > top).then_some(lo)
            })
            .fold(None::<f64>, |acc, v| Some(acc.map_or(v, |a| a.min(v))));
        let order = (top + next.map(|n| half_step.min((n - top) / 2.0)).unwrap_or(half_step) / 2.0).clamp(0.0, 1000.0);
        let masked_json: Vec<Json> = parts.iter().map(|s| Json::Str((*s).to_string())).collect();

        commands.push(Json::Obj(vec![
            ("op".into(), Json::Str("mesh_masked_by".into())),
            ("id".into(), Json::Str(bang_id.to_string())),
            ("maskedBy".into(), Json::Arr(masked_json.clone())),
            ("invertMask".into(), Json::Bool(true)),
        ]));
        commands.push(Json::Obj(vec![
            ("op".into(), Json::Str("mesh_clone".into())),
            ("sourceId".into(), Json::Str(bang_id.to_string())),
            ("id".into(), Json::Str(new_id)),
            ("name".into(), Json::Str(pattern.replace("{meshName}", mesh_name(bang, bang_id)))),
            ("drawOrder".into(), Json::Num(order)),
            ("opacityScale".into(), Json::Num(1.0 - alpha)),
            ("maskedBy".into(), Json::Arr(masked_json)),
            ("invertMask".into(), Json::Bool(false)),
            ("selectable".into(), Json::Bool(true)),
            ("insertBeforeOriginal".into(), Json::Bool(true)),
        ]));
    }

    Response::Commands(commands)
}

fn hex_decode(hex: &str) -> Option<String> {
    if hex.len() % 2 != 0 {
        return None;
    }
    let mut bytes = Vec::with_capacity(hex.len() / 2);
    for i in (0..hex.len()).step_by(2) {
        bytes.push(u8::from_str_radix(&hex[i..i + 2], 16).ok()?);
    }
    String::from_utf8(bytes).ok()
}

fn local_output_name(owner: &Json, global_id: &str) -> Option<String> {
    if let Some(aliases) = owner.get("outputAliases").and_then(Json::as_obj) {
        for (local, alias) in aliases {
            if alias.as_str() == Some(global_id) {
                return Some(local.clone());
            }
        }
    }
    let owner_id = owner.get("id").and_then(Json::as_str)?;
    let prefix = format!("modifier:{owner_id}:");
    global_id.strip_prefix(&prefix).and_then(hex_decode)
}

struct OpacityGrid {
    axes: Vec<(String, Vec<f32>)>,
    values: Vec<f32>,
    strides: Vec<usize>,
}

impl OpacityGrid {
    fn from_axes_and_cells(axes: Vec<(String, Vec<f32>)>, cells: &[(Vec<usize>, f32)]) -> Self {
        let mut strides = Vec::with_capacity(axes.len());
        let mut total = 1usize;
        for (_, keys) in &axes {
            strides.push(total);
            total *= keys.len().max(1);
        }
        let mut values = vec![0.0f32; total];
        for (coord, val) in cells {
            let mut idx = 0usize;
            for (a, &c) in coord.iter().enumerate() {
                idx += c * strides[a];
            }
            if idx < values.len() {
                values[idx] = *val;
            }
        }
        Self { axes, values, strides }
    }

    fn from_json(json: &Json) -> Option<Self> {
        let axes_arr = json.get("axes")?.as_arr()?;
        let mut axes = Vec::with_capacity(axes_arr.len());
        for a in axes_arr {
            let param = a.get("parameter")?.as_str()?.to_string();
            let keys: Vec<f32> = a
                .get("keys")?
                .as_arr()?
                .iter()
                .filter_map(|k| k.as_f64().map(|v| v as f32))
                .collect();
            axes.push((param, keys));
        }
        let cells_arr = json.get("cells")?.as_arr()?;
        let mut cells = Vec::with_capacity(cells_arr.len());
        for c in cells_arr {
            let coord: Vec<usize> = c
                .get("coord")?
                .as_arr()?
                .iter()
                .filter_map(|v| v.as_f64().map(|n| n as usize))
                .collect();
            let val = c.get("value")?.as_f64()? as f32;
            cells.push((coord, val));
        }
        Some(Self::from_axes_and_cells(axes, &cells))
    }

    fn bind_bracket(keys: &[f32], value: f32) -> Option<(usize, f32)> {
        const EPS_KEY: f32 = 0.001;
        const EPS_SPAN: f32 = 0.0015;
        let count = keys.len();
        if count <= 1 {
            if count == 0 {
                return Some((0, 0.0));
            }
            return if value > keys[0] - EPS_KEY && value < keys[0] + EPS_KEY {
                Some((0, 0.0))
            } else {
                None
            };
        }
        if value < keys[0] - EPS_KEY || value >= keys[count - 1] + EPS_KEY {
            return None;
        }
        if value < keys[0] + EPS_KEY {
            return Some((0, 0.0));
        }
        let mut hi = 1usize;
        while hi < count && keys[hi] + EPS_KEY <= value {
            hi += 1;
        }
        if value <= keys[hi] - EPS_KEY {
            let span = keys[hi] - keys[hi - 1];
            let frac = if span >= EPS_SPAN {
                (value - keys[hi - 1]) / span
            } else {
                0.0
            };
            Some((hi - 1, frac))
        } else {
            Some((hi, 0.0))
        }
    }

    fn scalar_at(&self, fallback: f32, pose: &HashMap<&str, f32>, defaults: &HashMap<String, f32>) -> f32 {
        if self.axes.is_empty() || self.values.is_empty() {
            return fallback;
        }
        let mut corners: Vec<(usize, f32)> = vec![(0usize, 1.0f32)];
        for (a, (param, keys)) in self.axes.iter().enumerate() {
            let v = pose
                .get(param.as_str())
                .copied()
                .unwrap_or_else(|| defaults.get(param).copied().unwrap_or(0.0));
            let Some((idx, frac)) = Self::bind_bracket(keys, v) else {
                return fallback;
            };
            let stride = self.strides[a];
            if frac == 0.0 {
                for (lin, _) in &mut corners {
                    *lin += idx * stride;
                }
            } else {
                let mut next = Vec::with_capacity(corners.len() * 2);
                let w0 = 1.0 - frac;
                for (lin, w) in corners {
                    next.push((lin + idx * stride, w * w0));
                    next.push((lin + (idx + 1) * stride, w * frac));
                }
                corners = next;
            }
        }
        corners.iter().map(|&(lin, w)| self.values[lin] * w).sum()
    }
}

fn handle_expression_toggles(req: &Request) -> Response {
    let Some(ctx) = req.context_values() else {
        return Response::Error("Missing expression author inputs".into());
    };
    if ctx.get("schema").and_then(Json::as_str) != Some("expression/1") {
        return Response::Error("Missing expression author inputs".into());
    }
    let Some(layers_obj) = ctx.get("layers").and_then(Json::as_obj) else {
        return Response::Error("Missing expression layers".into());
    };
    let Some(meshes_map_obj) = ctx.get("meshes").and_then(Json::as_obj) else {
        return Response::Error("Missing expression meshes".into());
    };
    let Some(opacity_obj) = ctx.get("opacity").and_then(Json::as_obj) else {
        return Response::Error("Missing expression opacity".into());
    };
    let chain_owners = ctx.get("chain_owners").and_then(Json::as_arr).unwrap_or(&[]);

    let mut layers: HashMap<&str, (&str, Option<i64>)> = HashMap::new();
    for (lid, val) in layers_obj {
        let Some(param) = val.get("parameter").and_then(Json::as_str) else {
            return Response::Error(format!("Missing parameter for layer {lid}"));
        };
        let sw = val.get("switch").and_then(Json::as_f64).map(|n| n as i64);
        layers.insert(lid.as_str(), (param, sw));
    }

    let mut meshes_to_layer: HashMap<&str, &str> = HashMap::new();
    for (mid, val) in meshes_map_obj {
        if let Some(lid) = val.as_str() {
            meshes_to_layer.insert(mid.as_str(), lid);
        }
    }

    let mut targets_vec: Vec<&str> = Vec::new();
    let mut target_set: HashSet<&str> = HashSet::new();
    let mut ids_set: HashSet<&str> = HashSet::new();
    let mut ids_ordered: Vec<&str> = Vec::new();
    for t in req.targets() {
        if t.get("kind").and_then(Json::as_str) == Some("mesh") {
            if let Some(id) = t.get("id").and_then(Json::as_str) {
                if target_set.insert(id) {
                    targets_vec.push(id);
                }
                if let Some(lid) = meshes_to_layer.get(id) {
                    if let Some(&(param, _)) = layers.get(lid) {
                        if ids_set.insert(param) {
                            ids_ordered.push(param);
                        }
                    }
                }
            }
        }
    }
    if ids_set.is_empty() {
        return Response::Commands(vec![]);
    }

    let mut switch_groups: HashMap<&str, Vec<i64>> = HashMap::new();
    for (_, &(param, sw)) in &layers {
        if let Some(s) = sw {
            if !param.is_empty() {
                switch_groups.entry(param).or_default().push(s);
            }
        }
    }
    let mut switch_keys: HashMap<&str, Vec<f32>> = HashMap::new();
    for (param, group) in switch_groups {
        let low = *group.iter().min().unwrap();
        let high = (*group.iter().max().unwrap()).max(low + 1);
        let count = (high - low + 1) as usize;
        let keys: Vec<f32> = (0..(count * 2 - 1)).map(|i| low as f32 + (i as f32) * 0.5).collect();
        switch_keys.insert(param, keys);
    }

    let mut existing_params: HashSet<String> = HashSet::new();
    let mut live_defaults: HashMap<String, f32> = HashMap::new();
    for p in req.parameters() {
        if let Some(id) = p.get("id").and_then(Json::as_str) {
            existing_params.insert(id.to_string());
            let def = p.get("default").and_then(Json::as_f64).unwrap_or(0.0) as f32;
            live_defaults.insert(id.to_string(), def);
        }
    }

    let mut commands: Vec<Json> = Vec::new();
    if let Some(declared) = req.header.get("declared_parameters").and_then(Json::as_arr) {
        for p in declared {
            let Some(id) = p.get("id").and_then(Json::as_str) else { continue };
            if ids_set.contains(id) && !existing_params.contains(id) {
                existing_params.insert(id.to_string());
                let def = p.get("default").and_then(Json::as_f64).unwrap_or(0.0) as f32;
                live_defaults.insert(id.to_string(), def);
                let mut obj = vec![
                    ("op".into(), Json::Str("parameter_add".into())),
                    ("id".into(), Json::Str(id.to_string())),
                    ("name".into(), Json::Str(p.get("name").and_then(Json::as_str).unwrap_or(id).to_string())),
                    ("min".into(), Json::Num(p.get("min").and_then(Json::as_f64).unwrap_or(0.0))),
                    ("max".into(), Json::Num(p.get("max").and_then(Json::as_f64).unwrap_or(1.0))),
                    ("default".into(), Json::Num(def as f64)),
                    ("blend".into(), Json::Bool(p.get("blend").and_then(Json::as_bool).unwrap_or(false))),
                    ("repeat".into(), Json::Bool(p.get("repeat").and_then(Json::as_bool).unwrap_or(false))),
                ];
                if let Some(keys) = p.get("keys") {
                    obj.push(("keys".into(), keys.clone()));
                }
                commands.push(Json::Obj(obj));
            }
        }
    }

    let inst_id = req.header.get("instance").and_then(Json::as_str).unwrap_or("unknown");
    let meshes_by_id: HashMap<&str, &Json> = req
        .meshes()
        .iter()
        .filter_map(|m| m.get("id").and_then(Json::as_str).map(|id| (id, m)))
        .collect();

    for target_id in &targets_vec {
        let Some(&lid) = meshes_to_layer.get(target_id) else { continue };
        let Some(&(param, sw)) = layers.get(lid) else { continue };
        let Some(mesh) = meshes_by_id.get(target_id) else { continue };
        let actual_grid = mesh.get("opacityGrid").and_then(OpacityGrid::from_json);
        if let Some(ref g) = actual_grid {
            if g.axes.iter().any(|(p, _)| ids_set.contains(p.as_str())) {
                return Response::Error(format!("{inst_id}: expression track already exists on {target_id}"));
            }
        }
        if param.is_empty() {
            continue;
        }
        let keys: Vec<f32> = match sw {
            None => vec![0.0, 1.0],
            Some(_) => match switch_keys.get(param) {
                Some(k) if !k.is_empty() => k.clone(),
                _ => continue,
            },
        };
        let desired_cells: Vec<(Vec<usize>, f32)> = keys
            .iter()
            .enumerate()
            .map(|(i, &k)| {
                let v = match sw {
                    None => k,
                    Some(s) => {
                        if (k - s as f32).abs() <= 0.5 { 1.0 } else { 0.0 }
                    }
                };
                (vec![i], v)
            })
            .collect();
        let desired_grid = OpacityGrid::from_axes_and_cells(vec![(param.to_string(), keys)], &desired_cells);

        let neutral_opacity = opacity_obj
            .iter()
            .find(|(k, _)| k == lid)
            .and_then(|(_, v)| v.as_f64())
            .unwrap_or(1.0) as f32;
        let actual_opacity = mesh.get("opacity").and_then(Json::as_f64).unwrap_or(1.0) as f32;

        // Union axes preserving first-seen parameter order, merging keys within EPS_KEY = 0.001
        let mut axis_order: Vec<String> = Vec::new();
        let mut axis_keys: HashMap<String, Vec<f32>> = HashMap::new();
        let mut add_axes = |grid: &OpacityGrid| {
            for (p, ks) in &grid.axes {
                if !live_defaults.contains_key(p) {
                    continue;
                }
                let entry = axis_keys.entry(p.clone()).or_insert_with(|| {
                    axis_order.push(p.clone());
                    Vec::new()
                });
                for &k in ks {
                    if entry.iter().all(|&existing| (existing - k).abs() >= 0.001) {
                        entry.push(k);
                    }
                }
            }
        };
        if let Some(ref g) = actual_grid {
            add_axes(g);
        }
        add_axes(&desired_grid);

        let union_axes: Vec<(String, Vec<f32>)> = axis_order
            .into_iter()
            .map(|p| {
                let mut ks = axis_keys.remove(&p).unwrap_or_default();
                ks.sort_by(f32::total_cmp);
                (p, ks)
            })
            .collect();

        let mut total_cells = 1usize;
        for (_, ks) in &union_axes {
            total_cells *= ks.len();
        }
        let mut out_cells = Vec::with_capacity(total_cells);
        for index in 0..total_cells {
            let mut rem = index;
            let mut coord = Vec::with_capacity(union_axes.len());
            let mut pose: HashMap<&str, f32> = HashMap::with_capacity(union_axes.len());
            for (p, ks) in &union_axes {
                let c = rem % ks.len();
                rem /= ks.len();
                coord.push(c);
                pose.insert(p.as_str(), ks[c]);
            }
            let actual_val = match actual_grid {
                Some(ref g) => g.scalar_at(actual_opacity, &pose, &live_defaults),
                None => actual_opacity,
            };
            let desired_val = desired_grid.scalar_at(neutral_opacity, &pose, &live_defaults);
            let value = actual_val - neutral_opacity + desired_val;
            out_cells.push(Json::Obj(vec![
                ("coord".into(), Json::Arr(coord.into_iter().map(|c| Json::Num(c as f64)).collect())),
                ("value".into(), Json::Num(value as f64)),
            ]));
        }

        let axes_json: Vec<Json> = union_axes
            .into_iter()
            .map(|(p, ks)| {
                Json::Obj(vec![
                    ("parameter".into(), Json::Str(p)),
                    ("keys".into(), Json::Arr(ks.into_iter().map(|k| Json::Num(k as f64)).collect())),
                ])
            })
            .collect();

        commands.push(Json::Obj(vec![
            ("op".into(), Json::Str("mesh_channel_grid_set".into())),
            ("id".into(), Json::Str((*target_id).to_string())),
            ("channel".into(), Json::Str("OPACITY".into())),
            ("axes".into(), Json::Arr(axes_json)),
            ("cells".into(), Json::Arr(out_cells)),
        ]));
    }

    let mut live_expr_params: Vec<&str> = ids_ordered
        .into_iter()
        .filter(|id| live_defaults.contains_key(*id))
        .collect();
    live_expr_params.sort_unstable();
    if !live_expr_params.is_empty() {
        if let Some(groups) = req.header.get("physics_groups").and_then(Json::as_arr) {
            for group in groups {
                let Some(gid) = group.get("id").and_then(Json::as_str) else { continue };
                let Some(slot) = chain_owners.iter().find_map(|o| local_output_name(o, gid)) else { continue };
                if !slot.starts_with("physics:chain:") || slot.ends_with(":Y") {
                    continue;
                }
                let existing_inputs = group.get("inputs").and_then(Json::as_arr).unwrap_or(&[]);
                let mut existing_by_param: HashMap<&str, Vec<&Json>> = HashMap::new();
                for pin in existing_inputs {
                    if let Some(p) = pin.get("parameter").and_then(Json::as_str) {
                        existing_by_param.entry(p).or_default().push(pin);
                    }
                }
                let mut to_add: Vec<Json> = Vec::new();
                for &param in &live_expr_params {
                    if let Some(pins) = existing_by_param.get(param) {
                        for pin in pins {
                            let ok = pin.get("weight").and_then(Json::as_f64) == Some(6.0)
                                && pin.get("type").and_then(Json::as_str) == Some("X")
                                && pin.get("reflect").and_then(Json::as_bool) == Some(false);
                            if !ok {
                                return Response::Error(format!(
                                    "Incompatible expression input {param} in physics group {gid}"
                                ));
                            }
                        }
                    } else {
                        to_add.push(Json::Obj(vec![
                            ("parameter".into(), Json::Str(param.to_string())),
                            ("weight".into(), Json::Num(6.0)),
                            ("type".into(), Json::Str("X".into())),
                            ("reflect".into(), Json::Bool(false)),
                        ]));
                    }
                }
                if !to_add.is_empty() {
                    commands.push(Json::Obj(vec![
                        ("op".into(), Json::Str("physics_group_inputs_add".into())),
                        ("groupId".into(), Json::Str(gid.to_string())),
                        ("inputs".into(), Json::Arr(to_add)),
                    ]));
                }
            }
        }
    }

    Response::Commands(commands)
}

fn main() {
    let _deps = load_dependencies();
    let mut cache = rig_ir::ObjectCache::default();
    if let Err(err) = run(|req| {
        if let Err(e) = cache.sync(req) {
            return Response::Error(e);
        }
        match req.algo_type() {
            "psd2live.native.core/physics" => handle_physics(req),
            "psd2live.native.core/motion" => handle_motion(req),
            "psd2live.native.core/skin-clip" => handle_skin_clip(req),
            "psd2live.native.core/eye-see-through" | "psd2live.native.core/brow-see-through" => handle_see_through(req),
            "psd2live.native.core/expression-toggles" => handle_expression_toggles(req),
            "psd2live.native.core/expression-cheek" => mood::handle_cheek(req, &cache),
            "psd2live.native.core/expression-gloom" => mood::handle_gloom(req, &cache),
            "psd2live.native.core/eye-lid-fade" => lid_fade::handle_lid_fade(req, &cache),
            "psd2live.native.core/eye-squash" => eye_squash::handle_eye_squash(req, &cache),
            "psd2live.native.core/brow-shape" => brow::handle_brow_shape(req, &cache),
            "psd2live.native.core/brow-spring" => brow::handle_brow_spring(req, &cache),
            "psd2live.native.core/swing" => swing::handle_swing(req, &cache, output_id),
            "psd2live.native.core/simulation" => simulation::handle_simulation(req, &cache, output_id),
            other => Response::Error(format!("Unknown native algorithm: {other}")),
        }
    }) {
        eprintln!("psd2live-native-core error: {err}");
        std::process::exit(1);
    }
}
