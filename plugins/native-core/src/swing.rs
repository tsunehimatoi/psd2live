use crate::rig_ir::{
    Deformer, KeyAxis, KeyCell, KeyGrid, Mesh, ObjectCache, Parameter, ParameterNode,
    PhysicsGroup, PhysicsInput, PhysicsNormalization, PhysicsOutput, PhysicsSegment, Rgb,
    decode_object_deformer, decode_object_frame, decode_object_mesh, encode_object_deformer,
    encode_object_frame, encode_object_mesh, grid_corners, linear_cell_map, objects_response,
    sync_parameters_from_tree,
};
use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, PartialEq, Eq)]
enum SwingKind {
    Lateral,
    Vertical,
}

impl SwingKind {
    fn from_str(s: &str) -> Result<Self, String> {
        match s.to_ascii_uppercase().as_str() {
            "LATERAL" => Ok(Self::Lateral),
            "VERTICAL" => Ok(Self::Vertical),
            _ => Err(format!("Unknown swing kind: {s}")),
        }
    }
    fn as_lower(self) -> &'static str {
        match self {
            Self::Lateral => "lateral",
            Self::Vertical => "vertical",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SwingFulcrum {
    Auto,
    Top,
    Bottom,
    Left,
    Right,
}

impl SwingFulcrum {
    fn from_str(s: &str) -> Result<Self, String> {
        match s.to_ascii_uppercase().as_str() {
            "AUTO" => Ok(Self::Auto),
            "TOP" => Ok(Self::Top),
            "BOTTOM" => Ok(Self::Bottom),
            "LEFT" => Ok(Self::Left),
            "RIGHT" => Ok(Self::Right),
            _ => Err(format!("Unknown swing fulcrum: {s}")),
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SwingPreset {
    Hair,
    Accessory,
    Cloth,
}

impl SwingPreset {
    fn from_str(s: &str) -> Result<Self, String> {
        match s.to_ascii_uppercase().as_str() {
            "HAIR" => Ok(Self::Hair),
            "ACCESSORY" => Ok(Self::Accessory),
            "CLOTH" => Ok(Self::Cloth),
            _ => Err(format!("Unknown swing preset: {s}")),
        }
    }
}

#[derive(Clone)]
struct SwingShape {
    magnitude: f32,
    lift: f32,
    softness: f32,
    zoom: f32,
    parallel: f32,
    flip: bool,
}

impl SwingShape {
    fn default_for(preset: SwingPreset, kind: SwingKind) -> Self {
        match (preset, kind) {
            (SwingPreset::Hair, SwingKind::Lateral) => Self { magnitude: 0.22, lift: 0.04, softness: 0.6, zoom: 0.0, parallel: 0.0, flip: false },
            (SwingPreset::Hair, SwingKind::Vertical) => Self { magnitude: 0.10, lift: 0.0, softness: 0.5, zoom: 0.04, parallel: 0.0, flip: false },
            (SwingPreset::Accessory, SwingKind::Lateral) => Self { magnitude: 0.30, lift: 0.06, softness: 0.1, zoom: 0.0, parallel: 0.0, flip: false },
            (SwingPreset::Accessory, SwingKind::Vertical) => Self { magnitude: 0.14, lift: 0.0, softness: 0.2, zoom: 0.0, parallel: 0.0, flip: false },
            (SwingPreset::Cloth, SwingKind::Lateral) => Self { magnitude: 0.16, lift: 0.02, softness: 0.8, zoom: 0.04, parallel: 0.0, flip: false },
            (SwingPreset::Cloth, SwingKind::Vertical) => Self { magnitude: 0.08, lift: 0.0, softness: 0.7, zoom: 0.06, parallel: 0.0, flip: false },
        }
    }
}

#[derive(Clone)]
struct SwingPhysics {
    length: f32,
    mobility: f32,
    delay: f32,
    acceleration: f32,
    output_scale: f32,
}

impl SwingPhysics {
    fn default_for(preset: SwingPreset, kind: SwingKind) -> Self {
        match preset {
            SwingPreset::Hair => Self {
                length: 10.0,
                mobility: 0.92,
                delay: 0.9,
                acceleration: 1.3,
                output_scale: if kind == SwingKind::Lateral { 1.6 } else { 1.4 },
            },
            SwingPreset::Accessory => Self {
                length: 6.0,
                mobility: 0.96,
                delay: 0.6,
                acceleration: 1.8,
                output_scale: if kind == SwingKind::Lateral { 1.8 } else { 1.6 },
            },
            SwingPreset::Cloth => Self {
                length: 12.0,
                mobility: 0.85,
                delay: 1.2,
                acceleration: 0.9,
                output_scale: if kind == SwingKind::Lateral { 1.3 } else { 1.2 },
            },
        }
    }
}

struct SwingMotion {
    kind: SwingKind,
    parameter_ids: Vec<String>,
    shape: SwingShape,
    physics: Option<SwingPhysics>,
}

struct SwingEdit {
    id: String,
    name: String,
    targets: Vec<String>,
    motions: Vec<SwingMotion>,
    fulcrum: SwingFulcrum,
    baked: bool,
    tilt: f32,
    offset_along: f32,
    offset_across: f32,
}

fn num_or(obj: &Json, key: &str, fallback: f32) -> f32 {
    obj.get(key).and_then(Json::as_f64).map(|v| v as f32).unwrap_or(fallback)
}

fn parse_swing_edit(config: &Json) -> Result<SwingEdit, String> {
    let preset = config
        .get("preset")
        .and_then(Json::as_str)
        .map(SwingPreset::from_str)
        .transpose()?
        .unwrap_or(SwingPreset::Hair);
    let fulcrum = config
        .get("fulcrum")
        .and_then(Json::as_str)
        .map(SwingFulcrum::from_str)
        .transpose()?
        .unwrap_or(SwingFulcrum::Auto);
    let id = config.get("id").and_then(Json::as_str).ok_or("Swing id is required")?.to_string();
    let name = config.get("name").and_then(Json::as_str).unwrap_or(&id).to_string();
    let targets: Vec<String> = config
        .get("targets")
        .and_then(Json::as_arr)
        .ok_or("Swing targets are required")?
        .iter()
        .filter_map(|v| v.as_str().map(str::to_string))
        .collect();
    let parse_motion = |m: &Json| -> Result<SwingMotion, String> {
        let kind = SwingKind::from_str(m.get("kind").and_then(Json::as_str).ok_or("Swing motion kind is required")?)?;
        let def_shape = SwingShape::default_for(preset, kind);
        let shape = SwingShape {
            magnitude: num_or(m, "magnitude", def_shape.magnitude),
            lift: num_or(m, "lift", def_shape.lift),
            softness: num_or(m, "softness", def_shape.softness),
            zoom: num_or(m, "zoom", def_shape.zoom),
            parallel: num_or(m, "parallel", def_shape.parallel),
            flip: m.get("flip").and_then(Json::as_bool).unwrap_or(def_shape.flip),
        };
        let physics = match m.get("physics") {
            None => Some(SwingPhysics::default_for(preset, kind)),
            Some(Json::Null) => None,
            Some(p) => Some(SwingPhysics {
                length: num_or(p, "length", 10.0),
                mobility: num_or(p, "mobility", 0.9),
                delay: num_or(p, "delay", 0.9),
                acceleration: num_or(p, "acceleration", 1.2),
                output_scale: num_or(p, "output_scale", 1.5),
            }),
        };
        let parameter_ids = m
            .get("parameters")
            .and_then(Json::as_arr)
            .ok_or("Swing motion parameters are required")?
            .iter()
            .filter_map(|v| v.as_str().map(str::to_string))
            .collect();
        Ok(SwingMotion { kind, parameter_ids, shape, physics })
    };
    let motions = if let Some(arr) = config.get("motions").and_then(Json::as_arr) {
        arr.iter().map(parse_motion).collect::<Result<Vec<_>, _>>()?
    } else {
        vec![parse_motion(config)?]
    };
    Ok(SwingEdit {
        id,
        name,
        targets,
        motions,
        fulcrum,
        baked: config.get("baked").and_then(Json::as_bool).unwrap_or(false),
        tilt: num_or(config, "tilt", 0.0),
        offset_along: num_or(config, "offset_along", 0.0),
        offset_across: num_or(config, "offset_across", 0.0),
    })
}

#[derive(Clone)]
enum DeformerWorld {
    Warp { cp: Vec<f32>, cols: usize, rows: usize, bilinear: bool },
    Rotation { c12: f32, c13: f32, c14: f32, c15: f32, ox: f32, oy: f32 },
}

impl DeformerWorld {
    fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        match self {
            Self::Warp { cp, cols, rows, bilinear } => warp_apply(cp, *cols, *rows, *bilinear, x, y),
            Self::Rotation { c12, c13, c14, c15, ox, oy } => (c15 * y + c12 * x + ox, y * c13 + x * c14 + oy),
        }
    }
}

fn warp_in_grid(cp: &[f32], cols: usize, rows: usize, bilinear: bool, u: f32, v: f32) -> Option<(f32, f32)> {
    if u < 0.0 || v < 0.0 || u >= 1.0 || v >= 1.0 {
        return None;
    }
    let ppr = cols + 1;
    let gu = cols as f32 * u;
    let gv = rows as f32 * v;
    let ui = gu as usize;
    let vi = gv as usize;
    let cu = gu - ui as f32;
    let cv = gv - vi as f32;
    let ci = vi * ppr + ui;
    let p00 = ci * 2;
    let p10 = (ci + 1) * 2;
    let p01 = (ci + ppr) * 2;
    let p11 = (ci + ppr + 1) * 2;
    if !bilinear {
        if cu + cv <= 1.0 {
            let w00 = 1.0 - cu - cv;
            Some((
                cp[p10] * cu + cp[p00] * w00 + cp[p01] * cv,
                w00 * cp[p00 + 1] + cp[p10 + 1] * cu + cp[p01 + 1] * cv,
            ))
        } else {
            let w = (cu - 1.0) + cv;
            Some((
                cp[p01] * (1.0 - cu) + cp[p11] * w + cp[p10] * (1.0 - cv),
                w * cp[p11 + 1] + (1.0 - cu) * cp[p01 + 1] + (1.0 - cv) * cp[p10 + 1],
            ))
        }
    } else {
        let icv = 1.0 - cv;
        let icu = 1.0 - cu;
        Some((
            cp[p10] * cu * icv + cp[p00] * icu * icv + cp[p01] * icu * cv + cp[p11] * cu * cv,
            icv * cp[p10 + 1] * cu + cp[p00 + 1] * icu * icv + icu * cp[p01 + 1] * cv + cu * cp[p11 + 1] * cv,
        ))
    }
}

fn warp_apply(cp: &[f32], cols: usize, rows: usize, bilinear: bool, u: f32, v: f32) -> (f32, f32) {
    if let Some(p) = warp_in_grid(cp, cols, rows, bilinear, u, v) {
        return p;
    }
    let ppr = cols + 1;
    let lrb = rows * ppr;
    let tlx = cp[0];
    let tly = cp[1];
    let trx = cp[cols * 2];
    let try_ = cp[cols * 2 + 1];
    let blx = cp[lrb * 2];
    let bly = cp[lrb * 2 + 1];
    let dux = trx - tlx;
    let duy = try_ - tly;
    let dvx = blx - tlx;
    let dvy = bly - tly;
    (tlx + dux * u + dvx * v, tly + dvy * v + duy * u)
}

fn build_deformer_worlds(deformers: &[Deformer], defaults: &HashMap<String, f32>) -> HashMap<String, DeformerWorld> {
    let mut worlds: HashMap<String, DeformerWorld> = HashMap::with_capacity(deformers.len());
    let ids: HashSet<&str> = deformers
        .iter()
        .map(|d| match d {
            Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.as_str(),
        })
        .collect();
    let mut pending: Vec<&Deformer> = deformers.iter().collect();
    for _ in 0..=deformers.len() + 2 {
        if pending.is_empty() {
            break;
        }
        let mut deferred = Vec::new();
        let mut progressed = false;
        for d in pending {
            let (id, parent) = match d {
                Deformer::Warp { id, parent, .. } | Deformer::Rotation { id, parent, .. } => (id.as_str(), parent.as_deref()),
            };
            if let Some(p) = parent {
                if ids.contains(p) && !worlds.contains_key(p) {
                    deferred.push(d);
                    continue;
                }
            }
            let parent_world = parent.and_then(|p| worlds.get(p));
            let world = match d {
                Deformer::Warp { rows, columns, bilinear, lattice, .. } => {
                    let Some(grid) = lattice else { continue };
                    let Some(corners) = grid_corners(grid, defaults) else { continue };
                    let cmap = linear_cell_map(grid);
                    let pcount = ((*rows + 1) * (*columns + 1) * 2) as usize;
                    let mut cp = vec![0.0f32; pcount];
                    for (lin, w) in corners {
                        if let Some(&ci) = cmap.get(&lin) {
                            let pts = &grid.cells[ci].form;
                            for i in 0..cp.len().min(pts.len()) {
                                cp[i] += w * pts[i];
                            }
                        }
                    }
                    if let Some(pw) = parent_world {
                        for chunk in cp.chunks_exact_mut(2) {
                            let (nx, ny) = pw.apply(chunk[0], chunk[1]);
                            chunk[0] = nx;
                            chunk[1] = ny;
                        }
                    }
                    DeformerWorld::Warp { cp, cols: *columns as usize, rows: *rows as usize, bilinear: *bilinear }
                }
                Deformer::Rotation { base_angle, pivot, flip_x, flip_y, .. } => {
                    let Some(grid) = pivot else { continue };
                    let Some(corners) = grid_corners(grid, defaults) else { continue };
                    let cmap = linear_cell_map(grid);
                    let (mut ox, mut oy, mut scale, mut angle) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
                    for (lin, w) in corners {
                        if let Some(&ci) = cmap.get(&lin) {
                            let p = &grid.cells[ci].form;
                            ox += w * p.x;
                            oy += w * p.y;
                            scale += w * p.scale;
                            angle += w * p.angle;
                        }
                    }
                    let mut w_angle = base_angle + angle;
                    let mut w_scale = scale;
                    let (mut w_ox, mut w_oy) = (ox, oy);
                    if let Some(pw) = parent_world {
                        let (px, py) = pw.apply(ox, oy);
                        w_ox = px;
                        w_oy = py;
                        match pw {
                            DeformerWorld::Rotation { c12, c13, c14, c15, .. } => {
                                let p_ang = c14.atan2(*c12).to_degrees();
                                let p_scale = (c12 * c12 + c14 * c14).sqrt();
                                let _ = (c13, c15);
                                w_angle += p_ang;
                                w_scale *= p_scale;
                            }
                            DeformerWorld::Warp { .. } => {}
                        }
                    }
                    let rad = w_angle * 3.1415927_f32 / 180.0;
                    let sn = rad.sin();
                    let cs = rad.cos();
                    let fx = if *flip_x { -1.0 } else { 1.0 };
                    let fy = if *flip_y { -1.0 } else { 1.0 };
                    DeformerWorld::Rotation {
                        c12: cs * w_scale * fx,
                        c13: fy * cs * w_scale,
                        c14: fx * w_scale * sn,
                        c15: -sn * w_scale * fy,
                        ox: w_ox,
                        oy: w_oy,
                    }
                }
            };
            worlds.insert(id.to_string(), world);
            progressed = true;
        }
        if !progressed {
            break;
        }
        pending = deferred;
    }
    worlds
}

fn mid_points(points: &[f32], indices: &[usize], sx: f32, sy: f32) -> (f32, f32) {
    let mut x = 0.0f32;
    let mut y = 0.0f32;
    for &i in indices {
        x += points[i * 2] * sx;
        y += points[i * 2 + 1] * sy;
    }
    let n = indices.len() as f32;
    (x / n, y / n)
}

fn solve_tip_angle(magnitude: f32, segments: usize, power: f32) -> f32 {
    if magnitude <= 0.0 {
        return 0.0;
    }
    let drive = |s: f32| -> f32 {
        let mut sum = 0.0f32;
        for k in 0..segments {
            sum += ((s - k as f32 / segments as f32) * segments as f32).clamp(0.0, 1.0).powf(power);
        }
        sum / segments as f32
    };
    let offset = |theta: f32| -> f32 {
        let steps = 128;
        let mut sum = 0.0f32;
        for j in 0..steps {
            sum += (theta * drive((j as f32 + 0.5) / steps as f32)).sin();
        }
        sum / steps as f32
    };
    let (mut low, mut high, mut best, mut best_off) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
    let mut theta = 0.0f32;
    while theta <= 3.2 {
        let o = offset(theta);
        if o >= magnitude {
            high = theta;
            break;
        }
        if o > best_off {
            best_off = o;
            best = theta;
        }
        low = theta;
        theta += 0.02;
    }
    if high == 0.0 {
        return best;
    }
    for _ in 0..24 {
        let mid = (low + high) * 0.5;
        if offset(mid) < magnitude {
            low = mid;
        } else {
            high = mid;
        }
    }
    (low + high) * 0.5
}

fn deform_swing(
    lattice: &[f32],
    rows: usize,
    cols: usize,
    sx: f32,
    sy: f32,
    kind: SwingKind,
    fulcrum: SwingFulcrum,
    shape: &SwingShape,
    segments: usize,
    tilt: f32,
    offset_along: f32,
    offset_across: f32,
    values: &[f32],
) -> Vec<f32> {
    let sign = if shape.flip { -1.0 } else { 1.0 };
    let v: Vec<f32> = values.iter().map(|&x| x * sign).collect();
    if v.iter().all(|&x| x == 0.0) {
        return lattice.to_vec();
    }
    let row = |r: usize| -> Vec<usize> { (0..=cols).map(|c| r * (cols + 1) + c).collect() };
    let col = |c: usize| -> Vec<usize> { (0..=rows).map(|r| r * (cols + 1) + c).collect() };
    let (pinned, opposite) = match fulcrum {
        SwingFulcrum::Top | SwingFulcrum::Auto => (row(0), row(rows)),
        SwingFulcrum::Bottom => (row(rows), row(0)),
        SwingFulcrum::Left => (col(0), col(cols)),
        SwingFulcrum::Right => (col(cols), col(0)),
    };
    let root = mid_points(lattice, &pinned, sx, sy);
    let tip = mid_points(lattice, &opposite, sx, sy);
    let length = (tip.0 - root.0).hypot(tip.1 - root.1).max(1e-6);
    let e0x = (tip.0 - root.0) / length;
    let e0y = (tip.1 - root.1) / length;
    let dx = (lattice[*pinned.last().unwrap() * 2] - lattice[pinned[0] * 2]) * sx;
    let dy = (lattice[*pinned.last().unwrap() * 2 + 1] - lattice[pinned[0] * 2 + 1]) * sy;
    let side = if -e0y * dx + e0x * dy < 0.0 { -1.0 } else { 1.0 };
    let width = dx.hypot(dy).max(1e-6);
    let root_x = root.0 + e0x * length * offset_along - side * e0y * width * offset_across;
    let root_y = root.1 + e0y * length * offset_along + side * e0x * width * offset_across;
    let rad = (tilt as f64).to_radians() as f32;
    let ex = e0x * rad.cos() - side * e0y * rad.sin();
    let ey = e0y * rad.cos() + side * e0x * rad.sin();

    let (m_from, m_to) = if kind == SwingKind::Lateral {
        (mid_points(lattice, &col(0), sx, sy), mid_points(lattice, &col(cols), sx, sy))
    } else {
        (mid_points(lattice, &row(0), sx, sy), mid_points(lattice, &row(rows), sx, sy))
    };
    let mx = m_to.0 - m_from.0;
    let my = m_to.1 - m_from.1;
    let (mut nx, mut ny) = (-ey, ex);
    if nx * mx + ny * my < 0.0 {
        nx = -nx;
        ny = -ny;
    }
    let stretch_sign = if ex * mx + ey * my < 0.0 { -1.0 } else { 1.0 };

    let bends = (fulcrum == SwingFulcrum::Top || fulcrum == SwingFulcrum::Bottom) == (kind == SwingKind::Lateral);
    let power = 1.0 + 3.0 * shape.softness;
    let drive = |s: f32| -> f32 {
        let mut sum = 0.0f32;
        for k in 0..segments {
            sum += ((s - k as f32 / segments as f32) * segments as f32).clamp(0.0, 1.0).powf(power) * v[k];
        }
        sum / segments as f32
    };

    let mut out = lattice.to_vec();
    let count = lattice.len() / 2;
    let mut along = vec![0.0f32; count];
    let mut across = vec![0.0f32; count];
    let mut max_s = 1.0f32;
    for i in 0..count {
        let x = lattice[i * 2] * sx - root_x;
        let y = lattice[i * 2 + 1] * sy - root_y;
        along[i] = x * ex + y * ey;
        across[i] = x * nx + y * ny;
        max_s = max_s.max(along[i] / length);
    }

    const SAMPLES: usize = 256;
    if bends {
        let theta = solve_tip_angle(shape.magnitude, segments, power);
        let ds = max_s / SAMPLES as f32;
        let mut cx = vec![0.0f32; SAMPLES + 1];
        let mut cy = vec![0.0f32; SAMPLES + 1];
        let mut angle = vec![0.0f32; SAMPLES + 1];
        for j in 0..=SAMPLES {
            angle[j] = theta * drive(j as f32 * ds);
        }
        for j in 1..=SAMPLES {
            let mid = theta * drive((j as f32 - 0.5) * ds);
            cx[j] = cx[j - 1] + ds * mid.cos();
            cy[j] = cy[j - 1] + ds * mid.sin();
        }
        for i in 0..count {
            let s = along[i] / length;
            if s <= 0.0 {
                continue;
            }
            let f = (s / ds).min(SAMPLES as f32);
            let j = (f as usize).min(SAMPLES - 1);
            let t = f - j as f32;
            let a = (angle[j] + (angle[j + 1] - angle[j]) * t) * (1.0 - shape.parallel);
            let px = cx[j] + (cx[j + 1] - cx[j]) * t;
            let py = cy[j] + (cy[j + 1] - cy[j]) * t;
            let d = drive(s);
            let b = across[i] * (1.0 + shape.zoom * d.abs());
            let new_along = px * length - b * a.sin() - shape.lift * length * d * d;
            let new_across = py * length + b * a.cos();
            out[i * 2] = (root_x + new_along * ex + new_across * nx) / sx;
            out[i * 2 + 1] = (root_y + new_along * ey + new_across * ny) / sy;
        }
    } else {
        let h = 1.0 / SAMPLES as f32;
        for i in 0..count {
            let s = along[i] / length;
            if s <= 0.0 {
                continue;
            }
            let disp = stretch_sign * shape.magnitude * drive(s);
            let s_lo = (s - h).max(0.0);
            let strain = stretch_sign * shape.magnitude * (drive(s + h) - drive(s_lo)) / (s + h - s_lo);
            let narrow = 1.0 / (1.0 + strain).max(0.2).sqrt();
            let b = across[i] * narrow * (1.0 + shape.zoom * drive(s).abs());
            let na = along[i] + disp * length;
            out[i * 2] = (root_x + na * ex + b * nx) / sx;
            out[i * 2 + 1] = (root_y + na * ey + b * ny) / sy;
        }
    }
    out
}

pub fn handle_swing(req: &Request, cache: &ObjectCache, output_id: fn(&Request, &str) -> String) -> Response {
    let mut edit = match parse_swing_edit(req.config()) {
        Ok(e) => e,
        Err(e) => return Response::Error(e),
    };
    if edit.baked {
        return Response::Error("A baked swing is ordinary content".into());
    }
    let target_ids: Vec<String> = req
        .targets()
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(str::to_string))
        .collect();
    if edit.targets != target_ids {
        return Response::Error("Swing targets must match the captured modifier targets".into());
    }
    edit.id = output_id(req, "swing");
    for m in &mut edit.motions {
        for i in 0..m.parameter_ids.len() {
            let local = format!("swing:{}:{}", m.kind.as_lower(), i);
            m.parameter_ids[i] = output_id(req, &local);
        }
    }

    let frame_bytes = match cache.get("frame") {
        Ok(b) => b,
        Err(e) => return Response::Error(e),
    };
    let mut frame = match decode_object_frame(frame_bytes) {
        Ok(f) => f,
        Err(e) => return Response::Error(format!("Decode frame: {e}")),
    };

    let mut deformer_order: Vec<String> = req
        .header
        .get("deformer_order")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(|v| v.as_str().map(str::to_string))
        .collect();
    let mesh_order: Vec<String> = req
        .mesh_order()
        .iter()
        .filter_map(|v| v.as_str().map(str::to_string))
        .collect();

    let mut deformers: Vec<Deformer> = Vec::with_capacity(deformer_order.len());
    for did in &deformer_order {
        let key = format!("deformer:{did}");
        let bytes = match cache.get(&key) {
            Ok(b) => b,
            Err(e) => return Response::Error(e),
        };
        match decode_object_deformer(bytes) {
            Ok(d) => deformers.push(d),
            Err(e) => return Response::Error(format!("Decode {key}: {e}")),
        }
    }
    let mut meshes: Vec<Mesh> = Vec::with_capacity(mesh_order.len());
    for mid in &mesh_order {
        let key = format!("mesh:{mid}");
        let bytes = match cache.get(&key) {
            Ok(b) => b,
            Err(e) => return Response::Error(e),
        };
        match decode_object_mesh(bytes) {
            Ok(m) => meshes.push(m),
            Err(e) => return Response::Error(format!("Decode {key}: {e}")),
        }
    }

    let mut changed_blobs: HashMap<String, Vec<u8>> = HashMap::new();
    let mut new_targets: Vec<String> = Vec::new();
    let mut mesh_groups: Vec<(Option<String>, Vec<usize>)> = Vec::new();

    for t in &edit.targets {
        if let Some(d) = deformers.iter().find(|d| match d {
            Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id == t,
        }) {
            match d {
                Deformer::Warp { .. } => new_targets.push(t.clone()),
                Deformer::Rotation { .. } => {
                    return Response::Error(format!("Swing needs a Warp or a mesh, not rotation {t}"));
                }
            }
        } else if let Some(mi) = meshes.iter().position(|m| &m.id == t) {
            let parent = meshes[mi].parent.clone();
            if let Some((_, group)) = mesh_groups.iter_mut().find(|(p, _)| *p == parent) {
                group.push(mi);
            } else {
                mesh_groups.push((parent, vec![mi]));
            }
        } else {
            return Response::Error(format!("Swing target not found: {t}"));
        }
    }

    // Wrap mesh targets into Warp_Swing_<id> deformers
    for (idx, (parent, group)) in mesh_groups.into_iter().enumerate() {
        let expected_id = if idx == 0 {
            format!("Warp_Swing_{}", edit.id)
        } else {
            format!("Warp_Swing_{}_{}", edit.id, idx + 1)
        };
        if deformers.iter().any(|d| match d {
            Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id == &expected_id,
        }) {
            return Response::Error(format!("Swing wrapper ID collides with existing content: {expected_id}"));
        }
        let (mut min_x, mut min_y, mut max_x, mut max_y) = (f32::INFINITY, f32::INFINITY, f32::NEG_INFINITY, f32::NEG_INFINITY);
        for &mi in &group {
            let Some(ref geom) = meshes[mi].geometry else {
                return Response::Error(format!("Mesh {} has no geometry", meshes[mi].id));
            };
            for pair in geom.positions.chunks_exact(2) {
                min_x = min_x.min(pair[0]);
                max_x = max_x.max(pair[0]);
                min_y = min_y.min(pair[1]);
                max_y = max_y.max(pair[1]);
            }
            if let Some(ref offsets) = meshes[mi].offsets {
                for cell in &offsets.cells {
                    for (j, &pos) in geom.positions.iter().enumerate() {
                        let v = pos + cell.form[j];
                        if j % 2 == 0 {
                            min_x = min_x.min(v);
                            max_x = max_x.max(v);
                        } else {
                            min_y = min_y.min(v);
                            max_y = max_y.max(v);
                        }
                    }
                }
            }
        }
        let bw = max_x - min_x;
        let bh = max_y - min_y;
        let tall = bh >= bw;
        let rows = if tall { 6 } else { 3 };
        let cols = if tall { 3 } else { 6 };
        let x = min_x - bw * 0.05;
        let y = min_y - bh * 0.05;
        let w = bw * 1.1;
        let h = bh * 1.1;

        let part_id = parent
            .as_deref()
            .and_then(|pid| {
                deformers.iter().find_map(|d| match d {
                    Deformer::Warp { id, part, .. } | Deformer::Rotation { id, part, .. } if id == pid => part.clone(),
                    _ => None,
                })
            })
            .or_else(|| {
                let mid = &meshes[group[0]].id;
                frame.parts.iter().find_map(|p| {
                    p.children.iter().any(|c| matches!(c, crate::rig_ir::ChildRef::MeshRef(id) if id == mid)).then(|| p.id.clone())
                })
            });

        let mut points = Vec::with_capacity(((rows + 1) * (cols + 1) * 2) as usize);
        for r in 0..=rows {
            for c in 0..=cols {
                points.push(x + c as f32 * w / cols as f32);
                points.push(y + r as f32 * h / rows as f32);
            }
        }
        let warp = Deformer::Warp {
            id: expected_id.clone(),
            name: edit.name.clone(),
            parent,
            part: part_id,
            rows,
            columns: cols,
            bilinear: true,
            lattice: Some(KeyGrid {
                axes: vec![],
                cells: vec![KeyCell { coordinate: vec![], form: points }],
            }),
            channels: vec![],
            opacity: 1.0,
            multiply: Rgb { r: 1.0, g: 1.0, b: 1.0 },
            screen: Rgb { r: 0.0, g: 0.0, b: 0.0 },
            selectable: true,
            visible: true,
            enabled: true,
            shapes: vec![],
        };
        deformer_order.push(expected_id.clone());
        changed_blobs.insert(format!("deformer:{expected_id}"), encode_object_deformer(&warp));
        deformers.push(warp);

        for mi in group {
            let m = &mut meshes[mi];
            m.parent = Some(expected_id.clone());
            if let Some(ref mut geom) = m.geometry {
                for (j, p) in geom.positions.iter_mut().enumerate() {
                    *p = if j % 2 == 0 { (*p - x) / w } else { (*p - y) / h };
                }
            }
            if let Some(ref mut offsets) = m.offsets {
                for cell in &mut offsets.cells {
                    for (j, d) in cell.form.iter_mut().enumerate() {
                        *d /= if j % 2 == 0 { w } else { h };
                    }
                }
            }
            for binding in &mut m.shapes {
                for s in binding.shapes.iter_mut().flatten() {
                    for (j, d) in s.deltas.iter_mut().enumerate() {
                        *d /= if j % 2 == 0 { w } else { h };
                    }
                }
            }
            changed_blobs.insert(format!("mesh:{}", m.id), encode_object_mesh(m));
        }
        new_targets.push(expected_id);
    }

    // Create swing parameters
    let all_param_ids: Vec<String> = edit.motions.iter().flat_map(|m| m.parameter_ids.iter().cloned()).collect();
    for raw in &all_param_ids {
        if frame.parameters.iter().any(|p| &p.id == raw) {
            let existing = frame.parameters.iter().find(|p| &p.id == raw).unwrap();
            if existing.blend {
                return Response::Error(format!("{}: swing parameters must be normal parameters", edit.id));
            }
        } else {
            if frame.parameter_tree.is_empty() && !frame.parameters.is_empty() {
                frame.parameter_tree = frame
                    .parameters
                    .iter()
                    .map(|p| ParameterNode::Param(p.id.clone()))
                    .collect();
            }
            frame.parameters.push(Parameter {
                id: raw.clone(),
                name: edit.name.clone(),
                min: -1.0,
                max: 1.0,
                default: 0.0,
                blend: false,
                repeat: false,
                keys: None,
            });
            frame.parameter_tree.insert(0, ParameterNode::Param(raw.clone()));
        }
    }

    let defaults: HashMap<String, f32> = frame.parameters.iter().map(|p| (p.id.clone(), p.default)).collect();
    let worlds = build_deformer_worlds(&deformers, &defaults);

    struct AxisInfo {
        motion: usize,
        segment: usize,
        min: f32,
        default: f32,
        max: f32,
        keys: Vec<f32>,
    }
    let mut axes_info = Vec::new();
    for (mi, motion) in edit.motions.iter().enumerate() {
        for (si, pid) in motion.parameter_ids.iter().enumerate() {
            let p = frame.parameters.iter().find(|p| &p.id == pid).unwrap();
            let mut ks = vec![p.min, p.default, p.max];
            ks.sort_by(f32::total_cmp);
            ks.dedup();
            axes_info.push(AxisInfo {
                motion: mi,
                segment: si,
                min: p.min,
                default: p.default,
                max: p.max,
                keys: ks,
            });
        }
    }
    let combinations: usize = axes_info.iter().map(|a| a.keys.len()).product();

    for target in &new_targets {
        let Some(di) = deformers.iter().position(|d| match d {
            Deformer::Warp { id, .. } => id == target,
            _ => false,
        }) else {
            return Response::Error(format!("{}: target Warp not found: {target}", edit.id));
        };
        let encoded_deformer = {
            let Deformer::Warp { id, parent, rows, columns, lattice, .. } = &mut deformers[di] else {
                unreachable!();
            };
            let Some(ref mut grid) = lattice else { continue };
        // Collapse any existing swing axes on the warp
        let mut base_axes = grid.axes.clone();
        let mut base_cells = grid.cells.clone();
        for pid in &all_param_ids {
            if let Some(ai) = base_axes.iter().position(|a| &a.parameter == pid) {
                let def = defaults.get(pid).copied().unwrap_or(0.0);
                let keep = base_axes[ai]
                    .keys
                    .iter()
                    .enumerate()
                    .min_by(|a, b| (*a.1 - def).abs().total_cmp(&(*b.1 - def).abs()))
                    .map(|(i, _)| i)
                    .unwrap_or(0);
                base_axes.remove(ai);
                base_cells = base_cells
                    .into_iter()
                    .filter(|c| c.coordinate[ai] as usize == keep)
                    .map(|mut c| {
                        c.coordinate.remove(ai);
                        c
                    })
                    .collect();
            }
        }
        let base_grid = KeyGrid { axes: base_axes, cells: base_cells };
        let Some(corners) = grid_corners(&base_grid, &defaults) else { continue };
        let cmap = linear_cell_map(&base_grid);
        let mut rest = vec![0.0f32; ((*rows + 1) * (*columns + 1) * 2) as usize];
        for (lin, w) in corners {
            if let Some(&ci) = cmap.get(&lin) {
                let pts = &base_grid.cells[ci].form;
                for i in 0..rest.len().min(pts.len()) {
                    rest[i] += w * pts[i];
                }
            }
        }

        let pw = parent.as_deref().and_then(|p| worlds.get(p));
        let world_pt = |x: f32, y: f32| -> (f32, f32) {
            pw.map_or((x, y), |w| w.apply(x, y))
        };
        let (sx, sy) = if let Some(_) = pw {
            let xs: Vec<f32> = rest.iter().step_by(2).copied().collect();
            let ys: Vec<f32> = rest.iter().skip(1).step_by(2).copied().collect();
            let min_x = xs.iter().copied().fold(f32::INFINITY, f32::min);
            let max_x = xs.iter().copied().fold(f32::NEG_INFINITY, f32::max);
            let min_y = ys.iter().copied().fold(f32::INFINITY, f32::min);
            let max_y = ys.iter().copied().fold(f32::NEG_INFINITY, f32::max);
            let cx = (min_x + max_x) * 0.5;
            let cy = (min_y + max_y) * 0.5;
            let du = ((max_x - min_x) * 0.05).max(1e-4);
            let dv = ((max_y - min_y) * 0.05).max(1e-4);
            let c = world_pt(cx, cy);
            let u = world_pt(cx + du, cy);
            let v = world_pt(cx, cy + dv);
            let sx = ((u.0 - c.0).hypot(u.1 - c.1) / du).max(1e-6);
            let sy = ((v.0 - c.0).hypot(v.1 - c.1) / dv).max(1e-6);
            (sx, sy)
        } else {
            (1.0, 1.0)
        };

        let fulcrum = if edit.fulcrum == SwingFulcrum::Auto {
            let r_cols = *columns as usize;
            let r_rows = *rows as usize;
            let mid_w = |idxs: Vec<usize>| -> (f32, f32) {
                let mut wx = 0.0f32;
                let mut wy = 0.0f32;
                for i in &idxs {
                    let p = world_pt(rest[i * 2], rest[i * 2 + 1]);
                    wx += p.0;
                    wy += p.1;
                }
                (wx / idxs.len() as f32, wy / idxs.len() as f32)
            };
            let top = mid_w((0..=r_cols).collect());
            let bot = mid_w((0..=r_cols).map(|c| r_rows * (r_cols + 1) + c).collect());
            let left = mid_w((0..=r_rows).map(|r| r * (r_cols + 1)).collect());
            let right = mid_w((0..=r_rows).map(|r| r * (r_cols + 1) + r_cols).collect());
            let height = (bot.0 - top.0).hypot(bot.1 - top.1);
            let width = (right.0 - left.0).hypot(right.1 - left.1);
            if height >= width * 0.8 {
                SwingFulcrum::Top
            } else {
                let center = frame.canvas.origin_x;
                if (left.0 - center).abs() <= (right.0 - center).abs() {
                    SwingFulcrum::Left
                } else {
                    SwingFulcrum::Right
                }
            }
        } else {
            edit.fulcrum
        };

        let mut motion_order: Vec<usize> = (0..edit.motions.len()).collect();
        motion_order.sort_by_key(|&mi| {
            let bends = (fulcrum == SwingFulcrum::Top || fulcrum == SwingFulcrum::Bottom)
                == (edit.motions[mi].kind == SwingKind::Lateral);
            if bends { 1 } else { 0 }
        });

        let mut out_cells = Vec::with_capacity(base_grid.cells.len() * combinations);
        let mut combo = vec![0usize; axes_info.len()];
        for _ in 0..combinations {
            let mut vals: Vec<Vec<f32>> = edit.motions.iter().map(|m| vec![0.0; m.parameter_ids.len()]).collect();
            for (i, a) in axes_info.iter().enumerate() {
                let v = a.keys[combo[i]];
                vals[a.motion][a.segment] = if v < a.default {
                    -(a.default - v) / (a.default - a.min)
                } else if v > a.default {
                    (v - a.default) / (a.max - a.default)
                } else {
                    0.0
                };
            }
            let still = vals.iter().all(|mv| mv.iter().all(|&x| x == 0.0));
            for cell in &base_grid.cells {
                let mut pts = cell.form.clone();
                if !still {
                    for &mi in &motion_order {
                        let m = &edit.motions[mi];
                        pts = deform_swing(
                            &pts,
                            *rows as usize,
                            *columns as usize,
                            sx,
                            sy,
                            m.kind,
                            fulcrum,
                            &m.shape,
                            m.parameter_ids.len(),
                            edit.tilt,
                            edit.offset_along,
                            edit.offset_across,
                            &vals[mi],
                        );
                    }
                }
                let mut coord = cell.coordinate.clone();
                for &c in &combo {
                    coord.push(c as i32);
                }
                out_cells.push(KeyCell { coordinate: coord, form: pts });
            }
            for k in 0..combo.len() {
                combo[k] += 1;
                if combo[k] < axes_info[k].keys.len() {
                    break;
                }
                combo[k] = 0;
            }
        }

        let mut new_axes = base_grid.axes;
        for (mi, motion) in edit.motions.iter().enumerate() {
            for pid in &motion.parameter_ids {
                let ai = edit.motions[..mi].iter().map(|m| m.parameter_ids.len()).sum::<usize>()
                    + motion.parameter_ids.iter().position(|p| p == pid).unwrap();
                new_axes.push(KeyAxis {
                    parameter: pid.clone(),
                    keys: axes_info[ai].keys.clone(),
                });
            }
        }
        *grid = KeyGrid { axes: new_axes, cells: out_cells };
        id.clone()
        };
        changed_blobs.insert(format!("deformer:{encoded_deformer}"), encode_object_deformer(&deformers[di]));
    }

    // Physics groups
    let available: HashSet<&str> = frame.parameters.iter().map(|p| p.id.as_str()).collect();
    for motion in &edit.motions {
        let Some(ref phys) = motion.physics else { continue };
        let candidate_inputs: Vec<(&str, f32, u32)> = match motion.kind {
            SwingKind::Lateral => vec![
                ("ParamAngleX", 60.0, 0),
                ("ParamAngleZ", 60.0, 1),
                ("ParamBodyAngleX", 40.0, 0),
                ("ParamBodyAngleZ", 40.0, 1),
            ],
            SwingKind::Vertical => vec![
                ("ParamAngleY", 60.0, 0),
                ("ParamBodyAngleY", 40.0, 0),
                ("ParamAngleZ", 20.0, 1),
            ],
        };
        let inputs: Vec<PhysicsInput> = candidate_inputs
            .into_iter()
            .filter(|(p, _, _)| available.contains(p) && !all_param_ids.iter().any(|x| x == p))
            .map(|(p, w, s)| PhysicsInput {
                parameter: p.to_string(),
                weight: w,
                source: s,
                reflect: false,
            })
            .collect();
        if inputs.is_empty() {
            continue;
        }
        let suffix = if edit.motions.len() == 1 {
            ""
        } else if motion.kind == SwingKind::Lateral {
            "_X"
        } else {
            "_Y"
        };
        let gid = format!("PhysicsSwing_{}{}", edit.id, suffix);
        let gname = if edit.motions.len() == 1 {
            edit.name.clone()
        } else {
            format!("{} {}", edit.name, &suffix[1..])
        };
        if frame.physics.groups.iter().any(|g| g.id == gid) {
            return Response::Error(format!("Duplicate physics group {gid}"));
        }
        let seg = PhysicsSegment {
            length: phys.length / motion.parameter_ids.len() as f32,
            mobility: phys.mobility,
            delay: phys.delay,
            acceleration: phys.acceleration,
        };
        frame.physics.groups.push(PhysicsGroup {
            id: gid,
            name: gname,
            inputs,
            outputs: motion
                .parameter_ids
                .iter()
                .enumerate()
                .map(|(k, p)| PhysicsOutput {
                    parameter: p.clone(),
                    vertex: (k + 1) as i32,
                    scale: phys.output_scale,
                    weight: 100.0,
                    source: 1,
                    reflect: false,
                })
                .collect(),
            segments: vec![seg; motion.parameter_ids.len()],
            normalization: PhysicsNormalization {
                position_min: -10.0,
                position_default: 0.0,
                position_max: 10.0,
                angle_min: -30.0,
                angle_default: 0.0,
                angle_max: 30.0,
            },
        });
    }

    sync_parameters_from_tree(&mut frame);

    changed_blobs.insert("frame".into(), encode_object_frame(&frame));
    objects_response(changed_blobs, mesh_order, deformer_order)
}
