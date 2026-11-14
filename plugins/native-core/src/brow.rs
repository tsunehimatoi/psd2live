use crate::output_id;
use crate::rig_ir::{
    decode_object_deformer, decode_object_frame, decode_object_mesh, decode_rig_binary,
    encode_object_deformer, encode_object_frame, encode_object_mesh, encode_rig_binary,
    objects_response, BlendBinding, Canvas, Deformer, GroupLabel, KeyAxis, KeyCell, KeyGrid,
    Mesh, MeshGeometry, MeshShape, ObjectCache, Parameter, ParameterNode, PhysicsGroup,
    PhysicsInput, PhysicsNormalization, PhysicsOutput, PhysicsSegment, Rgb, RigIR,
};
use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};

const BROW_SAMPLES: usize = 17;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Side {
    Left,
    Right,
}

impl Side {
    fn from_str(s: &str) -> Result<Self, String> {
        match s {
            "LEFT" => Ok(Side::Left),
            "RIGHT" => Ok(Side::Right),
            _ => Err(format!("Invalid brow side: {s}")),
        }
    }

    fn suffix(self) -> &'static str {
        match self {
            Side::Left => "L",
            Side::Right => "R",
        }
    }
}

#[derive(Clone)]
struct BrowFit {
    side: Side,
    origin_x: f32,
    origin_y: f32,
    axis_x: f32,
    axis_y: f32,
    length: f32,
    centre: Vec<f32>,
}

impl BrowFit {
    fn down_x(&self) -> f32 {
        if self.axis_x >= 0.0 {
            -self.axis_y
        } else {
            self.axis_y
        }
    }

    fn down_y(&self) -> f32 {
        if self.axis_x >= 0.0 {
            self.axis_x
        } else {
            -self.axis_x
        }
    }

    fn to_brow(&self, x: f32, y: f32) -> (f32, f32) {
        let dx = (x - self.origin_x) / self.length;
        let dy = (y - self.origin_y) / self.length;
        (
            dx * self.axis_x + dy * self.axis_y,
            dx * self.down_x() + dy * self.down_y(),
        )
    }

    fn canvas_delta(&self, du: f32, dv: f32) -> (f32, f32) {
        (
            (du * self.axis_x + dv * self.down_x()) * self.length,
            (du * self.axis_y + dv * self.down_y()) * self.length,
        )
    }

    fn sample(curve: &[f32], u: f32) -> f32 {
        let f = u.clamp(0.0, 1.0) * (BROW_SAMPLES - 1) as f32;
        let i = (f as usize).min(BROW_SAMPLES - 2);
        curve[i] + (curve[i + 1] - curve[i]) * (f - i as f32)
    }
}

#[derive(Clone, Copy)]
struct BrowOptions {
    shape: bool,
    shape_strength: f32,
    spring: bool,
    blink: bool,
    blink_dip: f32,
}

impl Default for BrowOptions {
    fn default() -> Self {
        Self {
            shape: false,
            shape_strength: 1.0,
            spring: false,
            blink: false,
            blink_dip: 1.0,
        }
    }
}

struct BrowRig<'a> {
    fit: &'a BrowFit,
    unit: f32,
    options: BrowOptions,
}

impl<'a> BrowRig<'a> {
    fn new(fit: &'a BrowFit, lift: f32, options: BrowOptions) -> Self {
        Self {
            fit,
            unit: lift / fit.length,
            options,
        }
    }

    fn delta(&self, px: f32, py: f32, y: f32, angle: f32, x: f32, form: f32) -> (f32, f32) {
        self.delta_with_dip(px, py, y, angle, x, form, 0.0)
    }

    fn delta_with_dip(
        &self,
        px: f32,
        py: f32,
        y: f32,
        angle: f32,
        x: f32,
        form: f32,
        dip: f32,
    ) -> (f32, f32) {
        let (u, v) = self.fit.to_brow(px, py);
        let k = self.options.shape_strength;
        let theta = 0.28 * k * angle;
        let cu = 0.5;
        let cv = BrowFit::sample(&self.fit.centre, 0.5);
        let cos_t = theta.cos();
        let sin_t = theta.sin();
        let ru = cu + (u - cu) * cos_t - (v - cv) * sin_t;
        let rv = cv + (u - cu) * sin_t + (v - cv) * cos_t;
        let uc = u.clamp(0.0, 1.0);
        let arch = -0.1 * k * form * 4.0 * uc * (1.0 - uc);
        let dip_shape = dip * self.unit * (0.35 + 0.15 * (1.0 - uc));
        let du = (ru - u) + 0.08 * k * x;
        let dv = (rv - v) - y * self.unit + arch + dip_shape;
        self.fit.canvas_delta(du, dv)
    }

    fn form<F>(&self, positions: &[f32], frame: [f32; 4], f: F) -> Vec<f32>
    where
        F: Fn(f32, f32) -> (f32, f32),
    {
        let width = (frame[2] - frame[0]).max(1e-4);
        let height = (frame[3] - frame[1]).max(1e-4);
        let mut out = vec![0.0; positions.len()];
        for i in (0..positions.len()).step_by(2) {
            let (dx, dy) = f(positions[i], positions[i + 1]);
            out[i] = dx / width;
            out[i + 1] = dy / height;
        }
        out
    }

    fn grid(
        &self,
        positions: &[f32],
        frame: [f32; 4],
        brow_y: &str,
        brow_angle: &str,
    ) -> KeyGrid<Vec<f32>> {
        let ys = [-1.0f32, 0.0, 1.0];
        let angles: &[f32] = if self.options.shape {
            &[-1.0, -0.5, 0.0, 0.5, 1.0]
        } else {
            &[0.0]
        };
        let mut cells = Vec::with_capacity(angles.len() * ys.len());
        for (a, &angle) in angles.iter().enumerate() {
            for (i, &y) in ys.iter().enumerate() {
                let coord = if self.options.shape {
                    vec![i as i32, a as i32]
                } else {
                    vec![i as i32]
                };
                let deltas = self.form(positions, frame, |px, py| {
                    self.delta(px, py, y, angle, 0.0, 0.0)
                });
                cells.push(KeyCell {
                    coordinate: coord,
                    form: deltas,
                });
            }
        }
        let mut axes = vec![KeyAxis {
            parameter: brow_y.to_string(),
            keys: ys.to_vec(),
        }];
        if self.options.shape {
            axes.push(KeyAxis {
                parameter: brow_angle.to_string(),
                keys: angles.to_vec(),
            });
        }
        KeyGrid { axes, cells }
    }

    fn shapes(
        &self,
        positions: &[f32],
        frame: [f32; 4],
        brow_x: &str,
        brow_form: &str,
        spring: Option<&str>,
        draw_order: f32,
        opacity: f32,
        multiply: Rgb,
        screen: Rgb,
    ) -> Vec<BlendBinding<MeshShape>> {
        let make_shape = |deltas: Vec<f32>| MeshShape {
            deltas,
            draw_order,
            opacity,
            multiply: multiply.clone(),
            screen: screen.clone(),
        };
        let mut out = Vec::new();
        if self.options.spring {
            if let Some(spring_id) = spring {
                let neg = make_shape(self.form(positions, frame, |px, py| {
                    self.delta(px, py, -0.18, 0.0, 0.0, 0.0)
                }));
                let pos = make_shape(self.form(positions, frame, |px, py| {
                    self.delta(px, py, 0.18, 0.0, 0.0, 0.0)
                }));
                out.push(BlendBinding {
                    parameter: spring_id.to_string(),
                    keys: vec![-1.0, 0.0, 1.0],
                    neutral_index: 1,
                    shapes: vec![Some(neg), None, Some(pos)],
                    limits: Vec::new(),
                });
            }
        }
        if self.options.shape {
            let x_neg = make_shape(self.form(positions, frame, |px, py| {
                self.delta(px, py, 0.0, 0.0, -1.0, 0.0)
            }));
            let x_pos = make_shape(self.form(positions, frame, |px, py| {
                self.delta(px, py, 0.0, 0.0, 1.0, 0.0)
            }));
            out.push(BlendBinding {
                parameter: brow_x.to_string(),
                keys: vec![-1.0, 0.0, 1.0],
                neutral_index: 1,
                shapes: vec![Some(x_neg), None, Some(x_pos)],
                limits: Vec::new(),
            });
            let f_neg = make_shape(self.form(positions, frame, |px, py| {
                self.delta(px, py, 0.0, 0.0, 0.0, -1.0)
            }));
            let f_pos = make_shape(self.form(positions, frame, |px, py| {
                self.delta(px, py, 0.0, 0.0, 0.0, 1.0)
            }));
            out.push(BlendBinding {
                parameter: brow_form.to_string(),
                keys: vec![-1.0, 0.0, 1.0],
                neutral_index: 1,
                shapes: vec![Some(f_neg), None, Some(f_pos)],
                limits: Vec::new(),
            });
        }
        out
    }
}

fn parse_floats(v: &Json, expected: usize, label: &str) -> Result<Vec<f32>, String> {
    let arr = v
        .as_arr()
        .ok_or_else(|| format!("Invalid {label}: expected array"))?;
    if arr.len() != expected {
        return Err(format!("Invalid {label}: expected {expected} numbers"));
    }
    let mut out = Vec::with_capacity(expected);
    for item in arr {
        let f = item
            .as_f64()
            .ok_or_else(|| format!("Invalid {label}: non-number"))? as f32;
        if !f.is_finite() {
            return Err(format!("Invalid {label}: non-finite number"));
        }
        out.push(f);
    }
    Ok(out)
}

fn parse_fit(side: Side, v: &Json) -> Result<BrowFit, String> {
    let nums = parse_floats(v, 5 + BROW_SAMPLES, "brow fit")?;
    if nums[4] <= 0.0 {
        return Err("Invalid brow fit length".into());
    }
    Ok(BrowFit {
        side,
        origin_x: nums[0],
        origin_y: nums[1],
        axis_x: nums[2],
        axis_y: nums[3],
        length: nums[4],
        centre: nums[5..].to_vec(),
    })
}

fn parse_eye(v: &Json) -> Result<Option<(f32, f32)>, String> {
    if matches!(v, Json::Null) {
        return Ok(None);
    }
    let nums = parse_floats(v, 2, "eye")?;
    if nums[0] < 0.0 || nums[1] <= 0.0 {
        return Err("Invalid brow eye measurement".into());
    }
    Ok(Some((nums[0], nums[1])))
}

fn parse_frame(v: &Json) -> Result<[f32; 4], String> {
    let nums = parse_floats(v, 4, "frame")?;
    if nums[2] <= nums[0] || nums[3] <= nums[1] {
        return Err("Invalid brow frame".into());
    }
    Ok([nums[0], nums[1], nums[2], nums[3]])
}

fn knob_fraction(config: &Json, key: &str, default: f32, min: f32, max: f32) -> Result<f32, String> {
    let raw = config
        .get("values")
        .and_then(|v| v.get(key))
        .and_then(Json::as_f64)
        .map(|v| v as f32)
        .unwrap_or(default);
    let frac = raw / 100.0;
    if !(min..=max).contains(&frac) {
        return Err(format!("Invalid brow value {key}: {raw}"));
    }
    Ok(frac)
}

fn normalize_positions(rig_positions: &[f32], frame: [f32; 4], normalize: bool) -> Vec<f32> {
    if !normalize {
        return rig_positions.to_vec();
    }
    let width = (frame[2] - frame[0]).max(1e-4);
    let height = (frame[3] - frame[1]).max(1e-4);
    let mut out = Vec::with_capacity(rig_positions.len());
    for (i, &val) in rig_positions.iter().enumerate() {
        if i % 2 == 0 {
            out.push((val - frame[0]) / width);
        } else {
            out.push((val - frame[1]) / height);
        }
    }
    out
}

fn target_ids_from_req(req: &Request) -> HashSet<String> {
    req.targets()
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(String::from))
        .collect()
}

fn find_upstream<'a>(
    upstream: &'a [Json],
    capability: &str,
    alt_type: Option<&str>,
    instance_id: Option<&str>,
    scaffold: Option<&Json>,
    required_targets: &[&str],
) -> Option<&'a Json> {
    upstream.iter().rfind(|cap| {
        let cap_name = cap.get("capability").and_then(Json::as_str);
        let type_name = cap.get("type").and_then(Json::as_str);
        let matches_cap = cap_name == Some(capability)
            || alt_type.is_some_and(|alt| type_name == Some(alt) || cap_name == Some(alt));
        if !matches_cap {
            return false;
        }
        if let Some(id) = instance_id {
            if cap.get("instanceId").and_then(Json::as_str) != Some(id) {
                return false;
            }
        }
        if let Some(sc) = scaffold {
            if cap.get("config").and_then(|c| c.get("scaffold")) != Some(sc) {
                return false;
            }
        }
        if !required_targets.is_empty() {
            let cap_targets: HashSet<&str> = cap
                .get("targets")
                .and_then(Json::as_arr)
                .map(|arr| {
                    arr.iter()
                        .filter_map(|t| t.get("id").and_then(Json::as_str))
                        .collect()
                })
                .unwrap_or_default();
            if !required_targets.iter().all(|t| cap_targets.contains(t)) {
                return false;
            }
        }
        true
    })
}

fn append_to_parameter_group(
    nodes: &[ParameterNode],
    group_id: &str,
    children: &[ParameterNode],
) -> (Vec<ParameterNode>, bool) {
    let mut found = false;
    let out = nodes
        .iter()
        .map(|node| match node {
            ParameterNode::Param { .. } => node.clone(),
            ParameterNode::Group {
                id,
                name,
                open,
                children: existing,
                label,
            } => {
                if id == group_id {
                    found = true;
                    let mut next = existing.clone();
                    next.extend(children.iter().cloned());
                    ParameterNode::Group {
                        id: id.clone(),
                        name: name.clone(),
                        open: *open,
                        children: next,
                        label: label.clone(),
                    }
                } else {
                    let (nested, matched) = append_to_parameter_group(existing, group_id, children);
                    found = found || matched;
                    if matched {
                        ParameterNode::Group {
                            id: id.clone(),
                            name: name.clone(),
                            open: *open,
                            children: nested,
                            label: label.clone(),
                        }
                    } else {
                        node.clone()
                    }
                }
            }
        })
        .collect();
    (out, found)
}

pub(crate) fn ensure_parameters(
    frame: &mut RigIR,
    declared: Vec<Parameter>,
    group_id: &str,
    group_name: &str,
) -> Result<(), String> {
    let mut added = Vec::new();
    for param in declared {
        if let Some(existing) = frame.parameters.iter().find(|p| p.id == param.id) {
            let mut cmp = existing.clone();
            cmp.name = param.name.clone();
            if cmp != param {
                return Err(format!("Incompatible brow parameter {}", param.id));
            }
        } else {
            added.push(param);
        }
    }
    if added.is_empty() {
        return Ok(());
    }
    frame.parameters.extend(added.iter().cloned());
    let children: Vec<ParameterNode> = added
        .iter()
        .map(|p| ParameterNode::Param(p.id.clone()))
        .collect();
    let root: Vec<ParameterNode> = if frame.parameter_tree.is_empty() {
        frame
            .parameters
            .iter()
            .filter(|p| !added.iter().any(|a| a.id == p.id))
            .map(|p| ParameterNode::Param(p.id.clone()))
            .collect()
    } else {
        frame.parameter_tree.clone()
    };
    let (mut next, found) = append_to_parameter_group(&root, group_id, &children);
    if !found {
        next.push(ParameterNode::Group {
            id: group_id.to_string(),
            name: group_name.to_string(),
            open: true,
            children,
            label: GroupLabel::None,
        });
    }
    frame.parameter_tree = next;
    Ok(())
}

fn decode_source_meshes(req: &Request) -> Result<HashMap<String, MeshGeometry>, String> {
    let bytes = req
        .context_resource("source_meshes")
        .ok_or("Missing source_meshes resource")?;
    let rig = decode_rig_binary(&bytes).map_err(|e| format!("Invalid source_meshes: {e}"))?;
    let mut map = HashMap::with_capacity(rig.meshes.len());
    for mesh in rig.meshes {
        if let Some(geom) = mesh.geometry {
            map.insert(mesh.id, geom);
        }
    }
    Ok(map)
}

fn sha256_hex(data: &[u8]) -> String {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let bit_len = (data.len() as u64) * 8;
    let mut padded = data.to_vec();
    padded.push(0x80);
    while (padded.len() % 64) != 56 {
        padded.push(0);
    }
    padded.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in padded.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                chunk[i * 4],
                chunk[i * 4 + 1],
                chunk[i * 4 + 2],
                chunk[i * 4 + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }
        let next = [a, b, c, d, e, f, g, hh];
        for i in 0..8 {
            h[i] = h[i].wrapping_add(next[i]);
        }
    }
    let mut hex = String::with_capacity(64);
    for word in h {
        use std::fmt::Write;
        let _ = write!(hex, "{:08x}", word);
    }
    hex
}

fn obj_get<'a>(entries: &'a [(String, Json)], key: &str) -> Option<&'a Json> {
    entries.iter().find(|(k, _)| k == key).map(|(_, v)| v)
}

fn deformer_fingerprint(d: &Deformer) -> String {
    let mut stripped = d.clone();
    match &mut stripped {
        Deformer::Warp { part, .. } | Deformer::Rotation { part, .. } => *part = None,
    }
    let ir = RigIR {
        canvas: Canvas {
            width: 0.0,
            height: 0.0,
            origin_x: 0.0,
            origin_y: 0.0,
            pixels_per_unit: None,
        },
        deformers: vec![stripped],
        ..RigIR::default()
    };
    sha256_hex(&encode_rig_binary(&ir))
}

fn check_height_param(frame: &RigIR, id: &str) -> Result<(), String> {
    let p = frame
        .parameters
        .iter()
        .find(|p| p.id == id)
        .ok_or_else(|| format!("Missing brow parameter {id}"))?;
    if p.min != -1.0 || p.max != 1.0 || p.default != 0.0 || p.blend {
        return Err(format!("Incompatible brow parameter {id}"));
    }
    Ok(())
}

pub fn handle_brow_shape(req: &Request, cache: &ObjectCache) -> Response {
    match handle_brow_shape_inner(req, cache) {
        Ok(resp) => resp,
        Err(msg) => Response::Error(msg),
    }
}

fn handle_brow_shape_inner(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    let values = req.context_values().ok_or("Missing brow shape context")?;
    if values.get("schema").and_then(Json::as_str) != Some("brow-shape/1") {
        return Err("Missing brow shape source inputs".into());
    }
    let names: Vec<String> = values
        .get("names")
        .and_then(Json::as_arr)
        .ok_or("Missing brow shape names")?
        .iter()
        .filter_map(|v| v.as_str().map(String::from))
        .collect();
    let group_name = values
        .get("group")
        .and_then(Json::as_str)
        .ok_or("Missing brow shape group")?;
    let eye_provider = values.get("eye_provider").and_then(Json::as_str);
    let targets_obj = values
        .get("targets")
        .and_then(Json::as_obj)
        .ok_or("Missing brow shape targets")?;

    let config = req.config();
    let scaffold_id = config
        .get("scaffold")
        .and_then(Json::as_str)
        .ok_or("Brow shape requires scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Brow shape requires base")?;
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    let target_ids_vec: Vec<String> = req
        .targets()
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(String::from))
        .collect();
    let target_refs: Vec<&str> = target_ids_vec.iter().map(String::as_str).collect();
    let scaffold_json = Json::Str(scaffold_id.to_string());

    if find_upstream(&upstream, "rig.scaffold", None, Some(scaffold_id), None, &target_refs).is_none() {
        return Err("Brow shape requires a shared scaffold".into());
    }
    let fitted = find_upstream(&upstream, "brow/fitted", None, Some(base_id), Some(&scaffold_json), &target_refs)
        .ok_or("Brow shape requires a compatible fitted brow provider")?;
    let lift = knob_fraction(
        fitted.get("config").unwrap_or(&Json::Null),
        "lift",
        45.0,
        0.2,
        1.0,
    )?;
    let strength = knob_fraction(config, "strength", 100.0, 0.0, 2.0)?;

    if names.is_empty() {
        return Ok(objects_response(HashMap::new(), Vec::new(), Vec::new()));
    }
    let raw_meshes = decode_source_meshes(req)?;
    let target_set = target_ids_from_req(req);

    struct ShapeTarget {
        id: String,
        fit: BrowFit,
        eye: Option<(f32, f32)>,
        parent: Option<String>,
        frame: [f32; 4],
        raw: MeshGeometry,
    }

    let mut parsed_targets = Vec::with_capacity(targets_obj.len());
    let mut sides = HashSet::new();
    for (id, entry) in targets_obj {
        if !target_set.contains(id) {
            return Err(format!("Unexpected brow shape target {id}"));
        }
        let side = Side::from_str(
            entry
                .get("side")
                .and_then(Json::as_str)
                .ok_or("Missing target side")?,
        )?;
        let fit = parse_fit(side, entry.get("fit").ok_or("Missing fit")?)?;
        let eye = parse_eye(entry.get("eye").ok_or("Missing eye")?)?;
        let parent = entry
            .get("parent")
            .and_then(Json::as_str)
            .map(String::from);
        let frame_bounds = parse_frame(entry.get("frame").ok_or("Missing frame")?)?;
        let raw = raw_meshes
            .get(id)
            .ok_or_else(|| format!("Missing source mesh {id}"))?
            .clone();
        sides.insert(side);
        parsed_targets.push(ShapeTarget {
            id: id.clone(),
            fit,
            eye,
            parent,
            frame: frame_bounds,
            raw,
        });
    }

    if let Some(ep) = eye_provider {
        let eye_targets: Vec<&str> = parsed_targets
            .iter()
            .filter(|t| t.eye.is_some())
            .map(|t| t.id.as_str())
            .collect();
        if find_upstream(&upstream, "eye/fitted", None, Some(ep), Some(&scaffold_json), &eye_targets).is_none() {
            return Err("Brow shape requires its captured fitted eyes".into());
        }
    }

    let mut frame = cache
        .get("frame")
        .and_then(|b| decode_object_frame(b).map_err(|e| e.to_string()))?;
    let frame_before = frame.clone();
    let suffixes = ["X", "Angle", "Form"];
    let mut declared = Vec::with_capacity(names.len());
    for (index, name) in names.iter().enumerate() {
        let left = index % 2 == 0;
        let side = if left { Side::Left } else { Side::Right };
        let suffix = suffixes[index / 2];
        let blend = suffix != "Angle" && sides.contains(&side);
        declared.push(Parameter {
            id: format!("ParamBrow{}{suffix}", side.suffix()),
            name: name.clone(),
            min: -1.0,
            max: 1.0,
            default: 0.0,
            repeat: false,
            blend,
            keys: if blend {
                Some(vec![-1.0, 0.0, 1.0])
            } else {
                None
            },
        });
    }
    ensure_parameters(
        &mut frame,
        declared,
        &output_id(req, "parameter-folder:ParamGroupBrows"),
        group_name,
    )?;

    let mut changed_blobs = HashMap::new();
    if frame != frame_before {
        changed_blobs.insert("frame".to_string(), encode_object_frame(&frame));
    }

    for target in &parsed_targets {
        let mut mesh = cache
            .get(&format!("mesh:{}", target.id))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let side = target.fit.side.suffix();
        let height_id = format!("ParamBrow{side}Y");
        let angle_id = format!("ParamBrow{side}Angle");
        check_height_param(&frame, &height_id)?;

        let expected_geom = MeshGeometry {
            positions: normalize_positions(&target.raw.positions, target.frame, target.parent.is_some()),
            uvs: target.raw.uvs.clone(),
            indices: target.raw.indices.clone(),
        };
        if mesh.parent != target.parent || mesh.geometry.as_ref() != Some(&expected_geom) {
            return Err("Brow shape requires the fitted source mesh and parent frame".into());
        }

        let travel = target
            .eye
            .map(|(opening, width)| opening * width * lift)
            .unwrap_or_else(|| target.fit.length * 0.25 * lift / 0.45);
        let prior_rig = BrowRig::new(&target.fit, travel, BrowOptions::default());
        let prior_grid = prior_rig.grid(&target.raw.positions, target.frame, &height_id, &angle_id);
        if mesh.offsets.as_ref() != Some(&prior_grid)
            || !mesh.shapes.is_empty()
            || !mesh.channels.is_empty()
        {
            return Err("Brow shape requires the unedited fitted height grid, without earlier mesh motion or drawing tracks".into());
        }

        let rig = BrowRig::new(
            &target.fit,
            travel,
            BrowOptions {
                shape: true,
                shape_strength: strength,
                spring: false,
                blink: false,
                blink_dip: 1.0,
            },
        );
        let next_grid = rig.grid(&target.raw.positions, target.frame, &height_id, &angle_id);
        let next_shapes = rig.shapes(
            &target.raw.positions,
            target.frame,
            &format!("ParamBrow{side}X"),
            &format!("ParamBrow{side}Form"),
            None,
            mesh.draw_order,
            mesh.opacity,
            mesh.multiply.clone(),
            mesh.screen.clone(),
        );
        let before_mesh = mesh.clone();
        mesh.offsets = Some(next_grid);
        mesh.shapes = next_shapes;
        if mesh != before_mesh {
            changed_blobs.insert(format!("mesh:{}", mesh.id), encode_object_mesh(&mesh));
        }
    }

    Ok(objects_response(changed_blobs, Vec::new(), Vec::new()))
}

pub fn handle_brow_spring(req: &Request, cache: &ObjectCache) -> Response {
    match handle_brow_spring_inner(req, cache) {
        Ok(resp) => resp,
        Err(msg) => Response::Error(msg),
    }
}

fn handle_brow_spring_inner(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    let values = req.context_values().ok_or("Missing brow spring context")?;
    if values.get("schema").and_then(Json::as_str) != Some("brow-spring/1") {
        return Err("Missing brow spring source inputs".into());
    }
    let names_obj = values
        .get("names")
        .and_then(Json::as_obj)
        .ok_or("Missing brow spring names")?;
    let physics_names_obj = values
        .get("physics_names")
        .and_then(Json::as_obj)
        .ok_or("Missing brow spring physics_names")?;
    let group_name = values
        .get("group")
        .and_then(Json::as_str)
        .ok_or("Missing brow spring group")?;
    let eye_provider = values.get("eye").and_then(Json::as_str);
    let shape_provider = values.get("shape").and_then(Json::as_str);
    let targets_obj = values
        .get("targets")
        .and_then(Json::as_obj)
        .ok_or("Missing brow spring targets")?;

    let config = req.config();
    let scaffold_id = config
        .get("scaffold")
        .and_then(Json::as_str)
        .ok_or("Brow spring requires scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Brow spring requires base")?;
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    let target_ids_vec: Vec<String> = req
        .targets()
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(String::from))
        .collect();
    let target_refs: Vec<&str> = target_ids_vec.iter().map(String::as_str).collect();
    let scaffold_json = Json::Str(scaffold_id.to_string());

    if find_upstream(&upstream, "rig.scaffold", None, Some(scaffold_id), None, &target_refs).is_none() {
        return Err("Brow spring requires a shared scaffold".into());
    }
    let fitted = find_upstream(&upstream, "brow/fitted", None, Some(base_id), Some(&scaffold_json), &target_refs)
        .ok_or("Brow spring requires a compatible fitted brow provider")?;
    let lift = knob_fraction(
        fitted.get("config").unwrap_or(&Json::Null),
        "lift",
        45.0,
        0.2,
        1.0,
    )?;

    let shape = if let Some(sp) = shape_provider {
        let cap = find_upstream(
            &upstream,
            "brow.shape",
            Some("psd2live.native.core/brow-shape"),
            Some(sp),
            Some(&scaffold_json),
            &target_refs,
        )
        .ok_or("Brow spring requires its captured brow shape")?;
        Some(cap)
    } else {
        None
    };
    let shape_strength = if let Some(cap) = shape {
        knob_fraction(
            cap.get("config").unwrap_or(&Json::Null),
            "strength",
            100.0,
            0.0,
            2.0,
        )?
    } else {
        1.0
    };

    let raw_meshes = decode_source_meshes(req)?;
    let target_set = target_ids_from_req(req);

    struct SpringTarget {
        id: String,
        fit: BrowFit,
        eye: Option<(f32, f32)>,
        frame: [f32; 4],
        raw: MeshGeometry,
        branch: Vec<String>,
        order: f32,
        opacity: f32,
    }

    let mut parsed_targets = Vec::with_capacity(targets_obj.len());
    let mut sides = HashSet::new();
    for (id, entry) in targets_obj {
        if !target_set.contains(id) {
            return Err(format!("Unexpected brow spring target {id}"));
        }
        let side = Side::from_str(
            entry
                .get("side")
                .and_then(Json::as_str)
                .ok_or("Missing target side")?,
        )?;
        let fit = parse_fit(side, entry.get("fit").ok_or("Missing fit")?)?;
        let eye = parse_eye(entry.get("eye").ok_or("Missing eye")?)?;
        let frame_bounds = parse_frame(entry.get("frame").ok_or("Missing frame")?)?;
        let branch: Vec<String> = entry
            .get("branch")
            .and_then(Json::as_arr)
            .ok_or("Missing branch")?
            .iter()
            .filter_map(|v| v.as_str().map(String::from))
            .collect();
        let drawing = parse_floats(entry.get("drawing").ok_or("Missing drawing")?, 2, "drawing")?;
        let raw = raw_meshes
            .get(id)
            .ok_or_else(|| format!("Missing source mesh {id}"))?
            .clone();
        sides.insert(side);
        parsed_targets.push(SpringTarget {
            id: id.clone(),
            fit,
            eye,
            frame: frame_bounds,
            raw,
            branch,
            order: drawing[0],
            opacity: drawing[1],
        });
    }

    if let Some(ep) = eye_provider {
        let eye_targets: Vec<&str> = parsed_targets
            .iter()
            .filter(|t| t.eye.is_some())
            .map(|t| t.id.as_str())
            .collect();
        if find_upstream(&upstream, "eye/fitted", None, Some(ep), Some(&scaffold_json), &eye_targets).is_none() {
            return Err("Brow spring requires its captured fitted eyes".into());
        }
    }

    let mut frame = cache
        .get("frame")
        .and_then(|b| decode_object_frame(b).map_err(|e| e.to_string()))?;
    let frame_before = frame.clone();

    let mut declared = Vec::new();
    for (side, side_key) in [(Side::Left, "LEFT"), (Side::Right, "RIGHT")] {
        if let Some(name) = obj_get(names_obj, side_key).and_then(Json::as_str) {
            let blend = sides.contains(&side);
            declared.push(Parameter {
                id: format!("ParamBrow{}Spring", side.suffix()),
                name: name.to_string(),
                min: -1.0,
                max: 1.0,
                default: 0.0,
                repeat: false,
                blend,
                keys: if blend {
                    Some(vec![-1.0, 0.0, 1.0])
                } else {
                    None
                },
            });
        }
    }
    ensure_parameters(
        &mut frame,
        declared,
        &output_id(req, "parameter-folder:ParamGroupPhysics"),
        group_name,
    )?;

    let options = BrowOptions {
        shape: shape.is_some(),
        shape_strength,
        spring: true,
        blink: false,
        blink_dip: 1.0,
    };

    let mut changed_blobs = HashMap::new();
    for target in &parsed_targets {
        let mut mesh = cache
            .get(&format!("mesh:{}", target.id))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let side = target.fit.side.suffix();
        let height_id = format!("ParamBrow{side}Y");
        let angle_id = format!("ParamBrow{side}Angle");
        check_height_param(&frame, &height_id)?;
        if options.shape {
            check_height_param(&frame, &angle_id)?;
        }

        let expected_geom = MeshGeometry {
            positions: normalize_positions(&target.raw.positions, target.frame, !target.branch.is_empty()),
            uvs: target.raw.uvs.clone(),
            indices: target.raw.indices.clone(),
        };
        if mesh.geometry.as_ref() != Some(&expected_geom) {
            return Err("Brow spring requires the native fitted brow mesh".into());
        }

        let mut live_branch = Vec::new();
        let mut parent = mesh.parent.clone();
        let mut seen = HashSet::new();
        while let Some(pid) = parent {
            if !seen.insert(pid.clone()) {
                return Err("Cyclic brow parent chain".into());
            }
            let d = cache
                .get(&format!("deformer:{pid}"))
                .and_then(|b| decode_object_deformer(b).map_err(|e| e.to_string()))?;
            parent = match &d {
                Deformer::Warp { parent, .. } | Deformer::Rotation { parent, .. } => parent.clone(),
            };
            live_branch.push(deformer_fingerprint(&d));
        }
        if mesh.parent.is_none() != target.branch.is_empty() {
            return Err("Brow spring requires the native fitted brow mesh and parent branch".into());
        }

        let travel = target
            .eye
            .map(|(opening, width)| opening * width * lift)
            .unwrap_or_else(|| target.fit.length * 0.25 * lift / 0.45);
        let rig = BrowRig::new(&target.fit, travel, options);
        let expected_grid = rig.grid(&target.raw.positions, target.frame, &height_id, &angle_id);
        let shapes = rig.shapes(
            &target.raw.positions,
            target.frame,
            &format!("ParamBrow{side}X"),
            &format!("ParamBrow{side}Form"),
            Some(&format!("ParamBrow{side}Spring")),
            target.order,
            target.opacity,
            Rgb {
                r: 1.0,
                g: 1.0,
                b: 1.0,
            },
            Rgb {
                r: 0.0,
                g: 0.0,
                b: 0.0,
            },
        );
        if mesh.offsets.as_ref() != Some(&expected_grid)
            || mesh.shapes != shapes[1..]
            || !mesh.channels.is_empty()
        {
            return Err("Brow spring requires the native height and brow shape tracks".into());
        }
        mesh.shapes = shapes;
        changed_blobs.insert(format!("mesh:{}", mesh.id), encode_object_mesh(&mesh));
    }

    if config.get("physicsEnabled").and_then(Json::as_bool) != Some(false) {
        let available: HashSet<String> = frame.parameters.iter().map(|p| p.id.clone()).collect();
        let overrides = config.get("physics").and_then(Json::as_obj);
        let mut native_rules = Vec::new();
        for (side_suffix, side_key) in [("L", "LEFT"), ("R", "RIGHT")] {
            let spring_id = format!("ParamBrow{side_suffix}Spring");
            if !available.contains(&spring_id) {
                continue;
            }
            let inputs: Vec<PhysicsInput> = [
                (format!("ParamBrow{side_suffix}Y"), 70.0),
                (format!("ParamBrow{side_suffix}Angle"), 30.0),
            ]
            .into_iter()
            .filter(|(p, _)| available.contains(p))
            .map(|(parameter, weight)| PhysicsInput {
                parameter,
                weight,
                source: 0,
                reflect: false,
            })
            .collect();
            if inputs.is_empty() {
                continue;
            }
            let group_id = format!("PhysicsBrow{side_suffix}Spring");
            let name = obj_get(physics_names_obj, side_key)
                .and_then(Json::as_str)
                .ok_or("Missing brow spring physics name")?
                .to_string();
            native_rules.push((
                group_id.clone(),
                PhysicsGroup {
                    id: output_id(req, &format!("physics:{group_id}")),
                    name,
                    inputs,
                    outputs: vec![PhysicsOutput {
                        parameter: spring_id,
                        vertex: 2,
                        scale: 1.2,
                        weight: 100.0,
                        source: 2,
                        reflect: false,
                    }],
                    segments: vec![
                        PhysicsSegment {
                            length: 1.0,
                            mobility: 0.92,
                            delay: 0.55,
                            acceleration: 1.6,
                        },
                        PhysicsSegment {
                            length: 1.0,
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
                },
            ));
        }

        if let Some(ov) = overrides {
            for (k, _) in ov {
                if !native_rules.iter().any(|(id, _)| id == k) {
                    return Err(format!("Unknown or unavailable brow spring physics group: {k}"));
                }
            }
        }

        for (rule_id, default_group) in native_rules {
            let requested = if let Some(ov_val) = overrides.and_then(|o| obj_get(o, &rule_id)) {
                let mut g = crate::simulation::parse_physics_group(ov_val)
                    .ok_or_else(|| format!("Invalid physics override for {rule_id}"))?;
                g.id = default_group.id.clone();
                g
            } else {
                default_group
            };
            let existing = &frame.physics.groups;
            let compatible = existing.iter().any(|g| {
                let mut cmp = g.clone();
                cmp.id = requested.id.clone();
                cmp.name = requested.name.clone();
                cmp == requested
            });
            if !compatible {
                if existing.iter().any(|g| g.id == requested.id) {
                    return Err(format!("Duplicate physics group {}", requested.id));
                }
                let claimed: HashSet<&str> = existing
                    .iter()
                    .flat_map(|g| g.outputs.iter().map(|o| o.parameter.as_str()))
                    .collect();
                if requested
                    .outputs
                    .iter()
                    .any(|o| claimed.contains(o.parameter.as_str()))
                {
                    return Err("Physics output parameter already claimed".into());
                }
                frame.physics.groups.push(requested);
            }
        }
    }

    if frame != frame_before {
        changed_blobs.insert("frame".to_string(), encode_object_frame(&frame));
    }
    Ok(objects_response(changed_blobs, Vec::new(), Vec::new()))
}

pub fn handle_brow_blink(req: &Request, cache: &ObjectCache) -> Response {
    match brow_blink_impl(req, cache) {
        Ok(r) => r,
        Err(e) => Response::Error(e),
    }
}

fn brow_blink_impl(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    if req.header.get("version").and_then(Json::as_i64) != Some(2) {
        return Err("Unsupported brow.blink version".into());
    }
    let ctx = req.context_values().ok_or("Missing brow.blink context")?;
    let names = ctx
        .get("carrier_names")
        .and_then(Json::as_obj)
        .ok_or("Missing carrier_names")?;
    let targets_obj = ctx
        .get("targets")
        .and_then(Json::as_obj)
        .ok_or("Missing brow blink targets")?;

    let config = req.config();
    let scaffold_id = config
        .get("scaffold")
        .and_then(Json::as_str)
        .ok_or("Brow blink requires scaffold")?;
    let base_id = config
        .get("base")
        .and_then(Json::as_str)
        .ok_or("Brow blink requires base")?;
    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);
    let target_ids_vec: Vec<String> = req
        .targets()
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(String::from))
        .collect();
    let target_refs: Vec<&str> = target_ids_vec.iter().map(String::as_str).collect();
    let scaffold_json = Json::Str(scaffold_id.to_string());

    if find_upstream(
        upstream,
        "rig.scaffold",
        None,
        Some(scaffold_id),
        None,
        &target_refs,
    )
    .is_none_or(|p| p.get("version").and_then(Json::as_i64) != Some(2))
    {
        return Err("Brow blink v2 requires a shared v2 scaffold".into());
    }
    let fitted = find_upstream(
        upstream,
        "brow/fitted",
        None,
        Some(base_id),
        Some(&scaffold_json),
        &target_refs,
    )
    .ok_or("Brow blink v2 requires a compatible fitted brow provider")?;
    let fv = fitted.get("version").and_then(Json::as_i64).unwrap_or(0);
    if !(1..=2).contains(&fv) {
        return Err("Brow blink v2 requires fitted brows v1..v2".into());
    }
    if targets_obj.len() != names.len()
        || !targets_obj.iter().all(|(k, _)| {
            names.iter().any(|(nk, _)| nk == k) && target_refs.contains(&k.as_str())
        })
    {
        return Err("Target mismatch in brow.blink".into());
    }
    let lift = knob_fraction(
        fitted.get("config").unwrap_or(&Json::Null),
        "lift",
        45.0,
        0.2,
        1.0,
    )?;
    let dip = knob_fraction(config, "dip", 100.0, 0.0, 2.0)?;
    let eye_provider = ctx.get("eyeProvider").and_then(Json::as_str);
    let raw_meshes = decode_source_meshes(req)?;
    let frame_bytes = cache.get("frame")?;
    let frame = decode_object_frame(frame_bytes).map_err(|e| e.to_string())?;

    let mut changed_blobs: HashMap<String, Vec<u8>> = HashMap::new();
    for (id, tv) in targets_obj {
        if tv.as_obj().is_none() {
            return Err("Invalid brow blink target".into());
        }
        let side = Side::from_str(
            tv.get("side")
                .and_then(Json::as_str)
                .ok_or("Missing side")?,
        )?;
        let fit = parse_fit(side, tv.get("fit").ok_or("Missing fit")?)?;
        let eye_dims = match tv.get("eye") {
            Some(Json::Null) | None => None,
            Some(ev) => {
                if ev.as_obj().is_none() {
                    return Err("Invalid eye".into());
                }
                let w = ev.get("width").and_then(Json::as_f64).ok_or("eye.width")? as f32;
                let o = ev
                    .get("opening")
                    .and_then(Json::as_f64)
                    .ok_or("eye.opening")? as f32;
                Some((w, o))
            }
        };
        let fb = parse_floats(
            tv.get("frame").ok_or("Missing frame")?,
            4,
            "brow blink frame",
        )?;
        let target_frame = [fb[0], fb[1], fb[2], fb[3]];
        let target_parent = tv
            .get("parent")
            .and_then(Json::as_str)
            .map(String::from);
        let raw_layer = tv
            .get("raw")
            .and_then(Json::as_str)
            .ok_or("Missing raw layer")?;
        let raw = raw_meshes
            .get(raw_layer)
            .ok_or_else(|| format!("Missing raw mesh {raw_layer}"))?;

        let providers: Vec<&Json> = upstream
            .iter()
            .filter(|p| {
                p.get("config").and_then(|c| c.get("scaffold")) == Some(&scaffold_json)
                    && p.get("targets").and_then(Json::as_arr).is_some_and(|arr| {
                        arr.iter()
                            .any(|t| t.get("id").and_then(Json::as_str) == Some(id.as_str()))
                    })
            })
            .collect();
        if providers.iter().any(|p| {
            p.get("capability").and_then(Json::as_str) == Some("brow.blink")
                || p.get("type").and_then(Json::as_str) == Some("psd2live.native.core/brow-blink")
        }) {
            return Err("Brow blink v2 requires a brow without preceding blink".into());
        }
        let eye_prov = providers
            .iter()
            .copied()
            .rfind(|p| p.get("capability").and_then(Json::as_str) == Some("eye/fitted"));
        if let (Some(ep), Some(_)) = (eye_provider, eye_dims) {
            let ok = eye_prov.is_some_and(|p| {
                p.get("instanceId").and_then(Json::as_str) == Some(ep)
                    && matches!(p.get("version").and_then(Json::as_i64), Some(1..=4))
            });
            if !ok {
                return Err("Missing captured fitted eyes".into());
            }
        }
        let has_legacy_eye = providers
            .iter()
            .any(|p| p.get("capability").and_then(Json::as_str) == Some("eye/legacy"));
        if eye_prov.is_none() && !has_legacy_eye {
            continue;
        }

        let suffix = side.suffix();
        let open_id = format!("ParamEye{suffix}Open");
        let smile_prov = providers.iter().copied().rfind(|p| {
            p.get("capability").and_then(Json::as_str) == Some("eye.smile")
                || p.get("type").and_then(Json::as_str) == Some("psd2live.native.core/eye-smile")
        });
        let smile_id = if eye_dims.is_some() && smile_prov.is_some() {
            Some(format!("ParamEye{suffix}Smile"))
        } else {
            None
        };
        let wide = eye_prov.is_some()
            && providers.iter().any(|p| {
                p.get("capability").and_then(Json::as_str) == Some("eye.wide")
                    || p.get("type").and_then(Json::as_str) == Some("psd2live.native.core/eye-wide")
            });

        let open_param = frame
            .parameters
            .iter()
            .find(|p| p.id == open_id)
            .ok_or_else(|| format!("Missing open parameter: {open_id}"))?;
        let exp_max = if wide { 2.0 } else { 1.0 };
        if open_param.blend
            || open_param.min != 0.0
            || open_param.default != 1.0
            || open_param.max != exp_max
        {
            return Err(format!("Incompatible open parameter: {open_id}"));
        }
        if let Some(sid) = &smile_id {
            let sp = frame
                .parameters
                .iter()
                .find(|p| &p.id == sid)
                .ok_or_else(|| format!("Missing smile parameter: {sid}"))?;
            if sp.blend || sp.min != 0.0 || sp.max != 1.0 || sp.default != 0.0 {
                return Err(format!("Incompatible smile parameter: {sid}"));
            }
        }

        let mesh = cache
            .get(&format!("mesh:{id}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;
        let geom = mesh
            .geometry
            .as_ref()
            .ok_or_else(|| format!("Missing geometry: {id}"))?;
        let fw = (target_frame[2] - target_frame[0]).max(1e-4);
        let fh = (target_frame[3] - target_frame[1]).max(1e-4);
        let expected_pos: Vec<f32> = raw
            .positions
            .iter()
            .enumerate()
            .map(|(i, &v)| {
                if target_parent.is_none() {
                    v
                } else if i % 2 == 0 {
                    (v - target_frame[0]) / fw
                } else {
                    (v - target_frame[1]) / fh
                }
            })
            .collect();
        if mesh.parent != target_parent
            || geom.positions != expected_pos
            || geom.uvs != raw.uvs
            || geom.indices != raw.indices
        {
            return Err(format!(
                "Brow blink v2 requires the fitted source mesh and parent frame: {id}"
            ));
        }

        let shape_prov = providers.iter().copied().rfind(|p| {
            p.get("capability").and_then(Json::as_str) == Some("brow.shape")
                || p.get("type").and_then(Json::as_str) == Some("psd2live.native.core/brow-shape")
        });
        let shape_strength = if let Some(sp) = shape_prov {
            knob_fraction(
                sp.get("config").unwrap_or(&Json::Null),
                "strength",
                100.0,
                0.0,
                2.0,
            )?
        } else {
            1.0
        };
        let travel = match eye_dims {
            Some((ew, eo)) => eo * ew * lift,
            None => fit.length * 0.25 * lift / 0.45,
        };
        let rig = BrowRig::new(
            &fit,
            travel,
            BrowOptions {
                shape: shape_prov.is_some(),
                shape_strength,
                spring: false,
                blink: true,
                blink_dip: dip,
            },
        );
        let height_id = format!("ParamBrow{suffix}Y");
        let angle_id = format!("ParamBrow{suffix}Angle");
        let expected_grid = rig.grid(&raw.positions, target_frame, &height_id, &angle_id);
        let allowed_shapes: HashSet<String> = [
            format!("ParamBrow{suffix}X"),
            format!("ParamBrow{suffix}Form"),
            format!("ParamBrow{suffix}Spring"),
        ]
        .into_iter()
        .collect();
        if mesh.offsets.as_ref() != Some(&expected_grid)
            || !mesh.channels.is_empty()
            || mesh
                .shapes
                .iter()
                .any(|s| !allowed_shapes.contains(&s.parameter))
        {
            return Err(format!(
                "Brow blink v2 requires the fitted height and angle grid without other local motion or drawing tracks: {id}"
            ));
        }

        let mut lid_mesh: Option<Mesh> = None;
        if let Some(sid) = &smile_id {
            let sp = smile_prov.unwrap();
            let smile_targets = sp
                .get("targets")
                .and_then(Json::as_arr)
                .unwrap_or(&[]);
            for st in smile_targets {
                if let Some(stid) = st.get("id").and_then(Json::as_str) {
                    if let Ok(b) = cache.get(&format!("mesh:{stid}")) {
                        if let Ok(mut lm) = decode_object_mesh(b) {
                            if lm
                                .offsets
                                .as_ref()
                                .is_some_and(|g| g.axes.iter().any(|a| &a.parameter == sid))
                            {
                                lm.shapes.clear();
                                lid_mesh = Some(lm);
                                break;
                            }
                        }
                    }
                }
            }
            if lid_mesh.is_none() {
                return Err(format!(
                    "Brow blink v2 requires an upstream lid grid keyed by {sid}"
                ));
            }
        }

        let parent_deformer = match &mesh.parent {
            Some(pid) => Some(
                cache
                    .get(&format!("deformer:{pid}"))
                    .and_then(|b| decode_object_deformer(b).map_err(|e| e.to_string()))?,
            ),
            None => None,
        };

        let make_form = |deltas: Vec<f32>| MeshShape {
            deltas,
            draw_order: mesh.draw_order,
            opacity: mesh.opacity,
            multiply: mesh.multiply.clone(),
            screen: mesh.screen.clone(),
        };
        let mut blink_bindings = Vec::new();
        let keys = if wide {
            vec![0.0, 1.0, 2.0]
        } else {
            vec![0.0, 1.0]
        };
        let mut forms = vec![
            Some(make_form(rig.form(&raw.positions, target_frame, |px, py| {
                rig.delta_with_dip(px, py, 0.0, 0.0, 0.0, 0.0, 0.25 * dip)
            }))),
            None,
        ];
        if wide {
            forms.push(Some(make_form(rig.form(
                &raw.positions,
                target_frame,
                |px, py| rig.delta_with_dip(px, py, 0.6, 0.0, 0.0, 0.0, 0.0),
            ))));
        }
        blink_bindings.push(BlendBinding {
            parameter: open_id,
            keys,
            neutral_index: 1,
            shapes: forms,
            limits: Vec::new(),
        });
        if let Some(sid) = smile_id {
            blink_bindings.push(BlendBinding {
                parameter: sid,
                keys: vec![0.0, 1.0],
                neutral_index: 0,
                shapes: vec![
                    None,
                    Some(make_form(rig.form(&raw.positions, target_frame, |px, py| {
                        rig.delta_with_dip(px, py, 0.35, 0.0, 0.0, 0.0, 0.0)
                    }))),
                ],
                limits: Vec::new(),
            });
        }

        let mut candidate = RigIR::default();
        candidate.canvas = frame.canvas.clone();
        candidate.parameters = frame.parameters.clone();
        let mut head_mesh = mesh.clone();
        head_mesh.shapes.extend(blink_bindings);
        candidate.meshes.push(head_mesh);
        if let Some(lm) = lid_mesh {
            candidate.meshes.push(lm);
        }
        if let Some(mut pd) = parent_deformer {
            match &mut pd {
                Deformer::Warp { shapes, .. } => shapes.clear(),
                Deformer::Rotation { shapes, .. } => shapes.clear(),
            }
            candidate.deformers.push(pd);
        }

        let carrier_label = names
            .iter()
            .find(|(k, _)| k == id)
            .and_then(|(_, v)| v.as_str())
            .ok_or_else(|| format!("Missing carrier_name for {id}"))?;
        let lowered = crate::lowering::lawful(&candidate, |_, _| carrier_label.to_string());
        let added = &lowered.deformers[candidate.deformers.len()..];
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
            changed_blobs.insert(format!("deformer:{cid}"), encode_object_deformer(&remapped));
        }

        let mut next = lowered.meshes.into_iter().next().unwrap();
        if let Some(p) = &mut next.parent {
            if let Some(np) = remap.get(p.as_str()) {
                *p = np.clone();
            }
        }
        if next != mesh {
            changed_blobs.insert(format!("mesh:{id}"), encode_object_mesh(&next));
        }
    }

    Ok(objects_response(changed_blobs, Vec::new(), Vec::new()))
}
