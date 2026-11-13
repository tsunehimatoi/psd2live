use crate::rig_ir::{
    decode_object_deformer, eval_floats_grid, eval_grid_flag, grid_corners, grid_ref,
    linear_cell_map, Deformer, KeyAxis, KeyCell, KeyGrid, Mesh, ObjectCache, Pivot,
};
use std::collections::{HashMap, HashSet};

pub fn collect_deformer_branch(
    start_parent: Option<&str>,
    cache: &ObjectCache,
) -> Result<Vec<Deformer>, String> {
    let mut branch = Vec::new();
    let mut seen = HashSet::new();
    let mut cur = start_parent.map(String::from);
    while let Some(pid) = cur {
        if !seen.insert(pid.clone()) {
            return Err("Cyclic deformer parent chain".into());
        }
        let d = cache
            .get(&format!("deformer:{pid}"))
            .and_then(|b| decode_object_deformer(b).map_err(|e| e.to_string()))?;
        cur = match &d {
            Deformer::Warp { parent, .. } | Deformer::Rotation { parent, .. } => parent.clone(),
        };
        branch.push(d);
    }
    Ok(branch)
}

const PI_F: f32 = std::f32::consts::PI;
const WARP_INVERSE_JACOBIAN_STEP: f32 = 1e-4;
const RIGID_INVERSE_MAX_ITERATIONS: usize = 24;
const CHANNEL_FLIP_X: u32 = 4;
const CHANNEL_FLIP_Y: u32 = 5;

#[derive(Clone, Copy)]
pub struct RotationXform {
    pub c12: f32,
    pub c13: f32,
    pub c14: f32,
    pub c15: f32,
    pub ox: f32,
    pub oy: f32,
}

impl RotationXform {
    pub fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        (
            self.c15 * y + self.c12 * x + self.ox,
            y * self.c13 + x * self.c14 + self.oy,
        )
    }
}

pub fn rotation_xform(
    angle_degrees: f32,
    scale: f32,
    flip_x: bool,
    flip_y: bool,
    ox: f32,
    oy: f32,
) -> RotationXform {
    let radians = angle_degrees * PI_F / 180.0;
    let sn = (radians as f64).sin() as f32;
    let cs = (radians as f64).cos() as f32;
    let fx = if flip_x { -1.0 } else { 1.0 };
    let fy = if flip_y { -1.0 } else { 1.0 };
    RotationXform {
        c12: cs * scale * fx,
        c13: fy * cs * scale,
        c14: fx * scale * sn,
        c15: -sn * scale * fy,
        ox,
        oy,
    }
}

#[derive(Clone)]
pub enum DeformerWorld {
    Warp {
        cp: Vec<f32>,
        cols: usize,
        rows: usize,
        bilinear: bool,
        acc_y: f32,
    },
    Rotation {
        xform: RotationXform,
        acc_y: f32,
    },
}

impl DeformerWorld {
    pub fn acc_y(&self) -> f32 {
        match self {
            DeformerWorld::Warp { acc_y, .. } | DeformerWorld::Rotation { acc_y, .. } => *acc_y,
        }
    }

    pub fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        match self {
            DeformerWorld::Warp {
                cp,
                cols,
                rows,
                bilinear,
                ..
            } => warp_apply(cp, *cols, *rows, *bilinear, x, y),
            DeformerWorld::Rotation { xform, .. } => xform.apply(x, y),
        }
    }
}

fn warp_in_grid(
    cp: &[f32],
    cols: usize,
    rows: usize,
    bilinear: bool,
    u: f32,
    v: f32,
) -> Option<(f32, f32)> {
    if u < 0.0 || v < 0.0 || u >= 1.0 || v >= 1.0 {
        return None;
    }
    let points_per_row = cols + 1;
    let grid_u = cols as f32 * u;
    let grid_v = rows as f32 * v;
    let iu = grid_u as usize;
    let iv = grid_v as usize;
    let cell_u = grid_u - iu as f32;
    let cell_v = grid_v - iv as f32;
    let cell_index = iv * points_per_row + iu;
    let p00 = cell_index * 2;
    let p10 = (cell_index + 1) * 2;
    let p01 = (cell_index + points_per_row) * 2;
    let p11 = (cell_index + points_per_row + 1) * 2;
    if !bilinear {
        if cell_u + cell_v <= 1.0 {
            let x = cp[p10] * cell_u + cp[p00] * (1.0 - cell_u - cell_v) + cp[p01] * cell_v;
            let y = (1.0 - cell_u - cell_v) * cp[p00 + 1] + cp[p10 + 1] * cell_u + cp[p01 + 1] * cell_v;
            Some((x, y))
        } else {
            let weight = (cell_u - 1.0) + cell_v;
            let x = cp[p01] * (1.0 - cell_u) + cp[p11] * weight + cp[p10] * (1.0 - cell_v);
            let y = weight * cp[p11 + 1] + (1.0 - cell_u) * cp[p01 + 1] + (1.0 - cell_v) * cp[p10 + 1];
            Some((x, y))
        }
    } else {
        let inv_v = 1.0 - cell_v;
        let x = cp[p10] * cell_u * inv_v
            + cp[p00] * (1.0 - cell_u) * inv_v
            + cp[p01] * (1.0 - cell_u) * cell_v
            + cp[p11] * cell_u * cell_v;
        let y = inv_v * cp[p10 + 1] * cell_u
            + cp[p00 + 1] * (1.0 - cell_u) * inv_v
            + (1.0 - cell_u) * cp[p01 + 1] * cell_v
            + cell_u * cp[p11 + 1] * cell_v;
        Some((x, y))
    }
}

fn warp_extrap(cp: &[f32], cols: usize, rows: usize, u: f32, v: f32) -> (f32, f32) {
    let points_per_row = cols + 1;
    let last_row_base = rows * points_per_row;
    let top_left_x = cp[0];
    let top_left_y = cp[1];
    let top_right_x = cp[cols * 2];
    let top_right_y = cp[cols * 2 + 1];
    let bot_left_x = cp[last_row_base * 2];
    let bot_left_y = cp[last_row_base * 2 + 1];
    let bot_right_x = cp[(cols + last_row_base) * 2];
    let bot_right_y = cp[(cols + last_row_base) * 2 + 1];
    let grid_u = cols as f32 * u;
    let grid_v = rows as f32 * v;
    let d_ux = ((bot_right_x - top_left_x) + (top_right_x - bot_left_x)) * 0.5;
    let d_vx = ((bot_right_x - top_left_x) - (top_right_x - bot_left_x)) * 0.5;
    let d_uy = ((bot_right_y - top_left_y) + (top_right_y - bot_left_y)) * 0.5;
    let d_vy = ((bot_right_y - top_left_y) - (top_right_y - bot_left_y)) * 0.5;
    let origin_x = (top_left_x + top_right_x + bot_left_x + bot_right_x) * 0.25
        - (bot_right_x - top_left_x) * 0.5;
    let origin_y = (top_left_y + top_right_y + bot_left_y + bot_right_y) * 0.25
        - (bot_right_y - top_left_y) * 0.5;
    if u <= -2.0 || u >= 3.0 || v <= -2.0 || v >= 3.0 {
        return (
            d_ux * u + origin_x + d_vx * v,
            d_vy * v + d_uy * u + origin_y,
        );
    }
    let (blend_u, blend_v, c0x, c0y, c1x, c1y, c2x, c2y, c3x, c3y);
    if u <= 0.0 {
        blend_u = (u + 2.0) * 0.5;
        c3x = origin_x - 2.0 * d_ux;
        c3y = origin_y - 2.0 * d_uy;
        if v <= 0.0 {
            c2x = top_left_x;
            c2y = top_left_y;
            blend_v = (v + 2.0) * 0.5;
            c1x = origin_x - 2.0 * d_vx;
            c0x = c3x - 2.0 * d_vx;
            c1y = origin_y - 2.0 * d_vy;
            c0y = c3y - 2.0 * d_vy;
        } else if v < 1.0 {
            let mut lo_idx = grid_v as usize;
            let (edge_hi, hi_idx) = if rows == lo_idx {
                lo_idx = rows - 1;
                (rows as f32, rows)
            } else {
                ((lo_idx + 1) as f32, lo_idx + 1)
            };
            blend_v = grid_v - lo_idx as f32;
            let edge_lo = lo_idx as f32 / rows as f32;
            c1x = cp[lo_idx * points_per_row * 2];
            c1y = cp[lo_idx * points_per_row * 2 + 1];
            c2x = cp[hi_idx * points_per_row * 2];
            c2y = cp[hi_idx * points_per_row * 2 + 1];
            c0x = edge_lo * d_vx + c3x;
            c0y = edge_lo * d_vy + c3y;
            let c3x_n = (edge_hi / rows as f32) * d_vx + c3x;
            let c3y_n = (edge_hi / rows as f32) * d_vy + c3y;
            return quad_tri_blend(blend_u, blend_v, c0x, c0y, c1x, c1y, c2x, c2y, c3x_n, c3y_n);
        } else {
            c1x = bot_left_x;
            c1y = bot_left_y;
            blend_v = (v - 1.0) * 0.5;
            c0x = c3x + d_vx;
            c0y = c3y + d_vy;
            let c3x_n = 3.0 * d_vx + c3x;
            c2x = 3.0 * d_vx + origin_x;
            let c3y_n = 3.0 * d_vy + c3y;
            c2y = 3.0 * d_vy + origin_y;
            return quad_tri_blend(blend_u, blend_v, c0x, c0y, c1x, c1y, c2x, c2y, c3x_n, c3y_n);
        }
    } else if u < 1.0 {
        if v <= 0.0 {
            let mut lo_idx = grid_u as usize;
            let (edge_x, edge_y, edge_hi) = if cols == lo_idx {
                lo_idx = cols - 1;
                (top_right_x, top_right_y, cols as f32)
            } else {
                (cp[(lo_idx + 1) * 2], cp[(lo_idx + 1) * 2 + 1], (lo_idx + 1) as f32)
            };
            c2x = edge_x;
            c2y = edge_y;
            blend_v = (v + 2.0) * 0.5;
            blend_u = grid_u - lo_idx as f32;
            let edge_lo = lo_idx as f32 / cols as f32;
            c3x = cp[lo_idx * 2];
            c3y = cp[lo_idx * 2 + 1];
            c0x = (edge_lo * d_ux + origin_x) - 2.0 * d_vx;
            c0y = (edge_lo * d_uy + origin_y) - 2.0 * d_vy;
            c1x = ((edge_hi / cols as f32) * d_ux + origin_x) - 2.0 * d_vx;
            c1y = ((edge_hi / cols as f32) * d_uy + origin_y) - 2.0 * d_vy;
        } else if v >= 1.0 {
            let mut lo_idx = grid_u as usize;
            let (hi_idx, edge_hi) = if cols == lo_idx {
                lo_idx = cols - 1;
                (cols, cols as f32)
            } else {
                (lo_idx + 1, (lo_idx + 1) as f32)
            };
            blend_v = (v - 1.0) * 0.5;
            blend_u = grid_u - lo_idx as f32;
            let edge_lo = lo_idx as f32 / cols as f32;
            c0x = cp[(lo_idx + last_row_base) * 2];
            c0y = cp[(lo_idx + last_row_base) * 2 + 1];
            c1x = cp[(last_row_base + hi_idx) * 2];
            c1y = cp[(last_row_base + hi_idx) * 2 + 1];
            c2x = (edge_hi / cols as f32) * d_ux + origin_x + 3.0 * d_vx;
            c3x = edge_lo * d_ux + origin_x + 3.0 * d_vx;
            c3y = edge_lo * d_uy + origin_y + 3.0 * d_vy;
            c2y = (edge_hi / cols as f32) * d_uy + origin_y + 3.0 * d_vy;
        } else {
            return (origin_x + d_ux * u + d_vx * v, origin_y + d_vy * v + d_uy * u);
        }
    } else {
        blend_u = (u - 1.0) * 0.5;
        c2x = 3.0 * d_ux + origin_x;
        c2y = 3.0 * d_uy + origin_y;
        if v <= 0.0 {
            c3x = top_right_x;
            c3y = top_right_y;
            blend_v = (v + 2.0) * 0.5;
            c0x = (origin_x + d_ux) - 2.0 * d_vx;
            c1x = c2x - 2.0 * d_vx;
            c1y = c2y - 2.0 * d_vy;
            c0y = (origin_y + d_uy) - 2.0 * d_vy;
        } else if v < 1.0 {
            let mut lo_idx = grid_v as usize;
            let (hi_idx, edge_hi) = if rows == lo_idx {
                lo_idx = rows - 1;
                (rows, rows as f32)
            } else {
                (lo_idx + 1, (lo_idx + 1) as f32)
            };
            blend_v = grid_v - lo_idx as f32;
            c0x = cp[(lo_idx * points_per_row + cols) * 2];
            c0y = cp[(lo_idx * points_per_row + cols) * 2 + 1];
            c3x = cp[(hi_idx * points_per_row + cols) * 2];
            c3y = cp[(hi_idx * points_per_row + cols) * 2 + 1];
            let edge_lo = lo_idx as f32 / rows as f32;
            c1x = edge_lo * d_vx + c2x;
            c1y = edge_lo * d_vy + c2y;
            let c2x_n = (edge_hi / rows as f32) * d_vx + c2x;
            let c2y_n = (edge_hi / rows as f32) * d_vy + c2y;
            return quad_tri_blend(blend_u, blend_v, c0x, c0y, c1x, c1y, c2x_n, c2y_n, c3x, c3y);
        } else {
            c0x = bot_right_x;
            c0y = bot_right_y;
            blend_v = (v - 1.0) * 0.5;
            c1x = c2x + d_vx;
            c1y = c2y + d_vy;
            c3x = origin_x + d_ux + 3.0 * d_vx;
            let c2x_n = 3.0 * d_vx + c2x;
            c3y = origin_y + d_uy + 3.0 * d_vy;
            let c2y_n = 3.0 * d_vy + c2y;
            return quad_tri_blend(blend_u, blend_v, c0x, c0y, c1x, c1y, c2x_n, c2y_n, c3x, c3y);
        }
    }
    quad_tri_blend(blend_u, blend_v, c0x, c0y, c1x, c1y, c2x, c2y, c3x, c3y)
}

#[allow(clippy::too_many_arguments)]
fn quad_tri_blend(
    blend_u: f32,
    blend_v: f32,
    c0x: f32,
    c0y: f32,
    c1x: f32,
    c1y: f32,
    c2x: f32,
    c2y: f32,
    c3x: f32,
    c3y: f32,
) -> (f32, f32) {
    if blend_u + blend_v <= 1.0 {
        (
            (c1x - c0x) * blend_u + c0x + (c3x - c0x) * blend_v,
            (c1y - c0y) * blend_u + c0y + (c3y - c0y) * blend_v,
        )
    } else {
        (
            (c3x - c2x) * (1.0 - blend_u) + c2x + (c1x - c2x) * (1.0 - blend_v),
            (1.0 - blend_u) * (c3y - c2y) + c2y + (1.0 - blend_v) * (c1y - c2y),
        )
    }
}

pub fn warp_apply(cp: &[f32], cols: usize, rows: usize, bilinear: bool, u: f32, v: f32) -> (f32, f32) {
    warp_in_grid(cp, cols, rows, bilinear, u, v).unwrap_or_else(|| warp_extrap(cp, cols, rows, u, v))
}

fn eval_blend_weight(keys: &[f32], value: f32) -> Option<(usize, f32)> {
    if keys.is_empty() {
        return None;
    }
    if keys.len() == 1 {
        return if (value - keys[0]).abs() <= 1e-6 {
            Some((0, 0.0))
        } else {
            None
        };
    }
    if value < keys[0] || value > keys[keys.len() - 1] {
        return None;
    }
    for i in 0..keys.len() - 1 {
        if value <= keys[i + 1] {
            let span = keys[i + 1] - keys[i];
            let t = if span.abs() < 1e-9 {
                0.0
            } else {
                (value - keys[i]) / span
            };
            return Some((i, t));
        }
    }
    Some((keys.len() - 2, 1.0))
}

fn eval_pivot_grid(grid: &KeyGrid<Pivot>, pose: &HashMap<String, f32>) -> Option<(f32, f32, f32, f32)> {
    let corners = grid_corners(grid, pose)?;
    let cells = linear_cell_map(grid);
    let mut ox = 0.0f32;
    let mut oy = 0.0f32;
    let mut scale = 0.0f32;
    let mut angle = 0.0f32;
    for (lin, w) in corners {
        if let Some(&idx) = cells.get(&lin) {
            let p = &grid.cells[idx].form;
            ox += w * p.x;
            oy += w * p.y;
            scale += w * p.scale;
            angle += w * p.angle;
        }
    }
    Some((ox, oy, scale, angle))
}

fn build_deformer_world(
    deformer: &Deformer,
    pose: &HashMap<String, f32>,
    defaults: &HashMap<String, f32>,
    parent_world: Option<&DeformerWorld>,
) -> Option<DeformerWorld> {
    match deformer {
        Deformer::Warp {
            rows,
            columns,
            bilinear,
            lattice,
            shapes,
            ..
        } => {
            let grid = lattice.as_ref()?;
            let point_count = (*rows as usize + 1) * (*columns as usize + 1);
            let mut cp = eval_floats_grid(grid, pose, point_count * 2)?;
            for b in shapes {
                let val = pose.get(&b.parameter).copied().or_else(|| defaults.get(&b.parameter).copied()).unwrap_or(0.0);
                let Some((i, t)) = eval_blend_weight(&b.keys, val) else {
                    continue;
                };
                let (w0, w1) = (1.0 - t, t);
                if let Some(Some(s0)) = b.shapes.get(i) {
                    for (k, v) in s0.points.iter().enumerate() {
                        if k < cp.len() {
                            cp[k] += w0 * v;
                        }
                    }
                }
                if let Some(Some(s1)) = b.shapes.get(i + 1) {
                    for (k, v) in s1.points.iter().enumerate() {
                        if k < cp.len() {
                            cp[k] += w1 * v;
                        }
                    }
                }
            }
            let parent_acc_y = parent_world.map(|p| p.acc_y()).unwrap_or(1.0);
            if let Some(pw) = parent_world {
                for idx in 0..point_count {
                    let (nx, ny) = pw.apply(cp[idx * 2], cp[idx * 2 + 1]);
                    cp[idx * 2] = nx;
                    cp[idx * 2 + 1] = ny;
                }
            }
            Some(DeformerWorld::Warp {
                cp,
                cols: *columns as usize,
                rows: *rows as usize,
                bilinear: *bilinear,
                acc_y: parent_acc_y,
            })
        }
        Deformer::Rotation {
            base_angle,
            pivot,
            channels,
            flip_x,
            flip_y,
            shapes,
            ..
        } => {
            let grid = pivot.as_ref()?;
            let (mut ox, mut oy, mut scale_acc, mut angle_acc) = eval_pivot_grid(grid, pose)?;
            for b in shapes {
                let val = pose.get(&b.parameter).copied().or_else(|| defaults.get(&b.parameter).copied()).unwrap_or(0.0);
                let Some((i, t)) = eval_blend_weight(&b.keys, val) else {
                    continue;
                };
                let (w0, w1) = (1.0 - t, t);
                if let Some(Some(s0)) = b.shapes.get(i) {
                    ox += w0 * s0.x;
                    oy += w0 * s0.y;
                    scale_acc += w0 * s0.scale;
                    angle_acc += w0 * s0.angle;
                }
                if let Some(Some(s1)) = b.shapes.get(i + 1) {
                    ox += w1 * s1.x;
                    oy += w1 * s1.y;
                    scale_acc += w1 * s1.scale;
                    angle_acc += w1 * s1.angle;
                }
            }
            let fx = eval_grid_flag(grid_ref(channels, CHANNEL_FLIP_X), *flip_x, pose);
            let fy = eval_grid_flag(grid_ref(channels, CHANNEL_FLIP_Y), *flip_y, pose);
            let angle_deg = *base_angle + angle_acc;
            let parent_acc_y = parent_world.map(|p| p.acc_y()).unwrap_or(1.0);
            let scale = parent_acc_y * scale_acc;
            let (world_ox, world_oy, world_angle) = if let Some(pw) = parent_world {
                let disp = match pw {
                    DeformerWorld::Rotation { .. } => -10.0f32,
                    DeformerWorld::Warp { .. } => -0.1f32,
                };
                let (wx, wy) = pw.apply(ox, oy);
                let mut dx = 0.0f32;
                let mut dy = 0.0f32;
                let mut probe_scale = 1.0f32;
                for _ in 0..10 {
                    let (px, py) = pw.apply(ox, oy + disp * probe_scale);
                    dx = px - wx;
                    dy = py - wy;
                    if dx != 0.0 || dy != 0.0 {
                        break;
                    }
                    let (nx, ny) = pw.apply(ox, oy - disp * probe_scale);
                    dx = wx - nx;
                    dy = wy - ny;
                    if dx != 0.0 || dy != 0.0 {
                        break;
                    }
                    probe_scale *= 0.1;
                }
                let mut inherited =
                    (disp as f64).atan2(0.0) as f32 - (dy as f64).atan2(dx as f64) as f32;
                while inherited > PI_F {
                    inherited -= 2.0 * PI_F;
                }
                while inherited < -PI_F {
                    inherited += 2.0 * PI_F;
                }
                (wx, wy, angle_deg - inherited * 180.0 / PI_F)
            } else {
                (ox, oy, angle_deg)
            };
            Some(DeformerWorld::Rotation {
                xform: rotation_xform(world_angle, scale, fx, fy, world_ox, world_oy),
                acc_y: scale,
            })
        }
    }
}

fn deformer_id(d: &Deformer) -> &str {
    match d {
        Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.as_str(),
    }
}

pub fn build_deformer_worlds(
    deformers: &[Deformer],
    pose: &HashMap<String, f32>,
    defaults: &HashMap<String, f32>,
) -> HashMap<String, DeformerWorld> {
    let mut worlds: HashMap<String, DeformerWorld> = HashMap::with_capacity(deformers.len());
    let ids: HashSet<&str> = deformers.iter().map(|d| deformer_id(d)).collect();
    let mut pending: Vec<&Deformer> = deformers.iter().collect();
    for _ in 0..=deformers.len() + 2 {
        if pending.is_empty() {
            break;
        }
        let mut deferred = Vec::new();
        let mut built = false;
        for d in pending {
            let parent_id = match d {
                Deformer::Warp { parent, .. } | Deformer::Rotation { parent, .. } => parent.as_deref(),
            };
            let parent_known = parent_id.is_none_or(|p| !ids.contains(p) || worlds.contains_key(p));
            if !parent_known {
                deferred.push(d);
                continue;
            }
            let parent_world = parent_id.and_then(|p| worlds.get(p));
            if let Some(w) = build_deformer_world(d, pose, defaults, parent_world) {
                worlds.insert(deformer_id(d).to_string(), w);
                built = true;
            }
        }
        if !built {
            break;
        }
        pending = deferred;
    }
    worlds
}

pub struct DrawableSpaceMapping {
    parent_world: Option<DeformerWorld>,
}

impl DrawableSpaceMapping {
    pub fn for_mesh(
        mesh: &Mesh,
        deformers: &[Deformer],
        pose: &HashMap<String, f32>,
        defaults: &HashMap<String, f32>,
    ) -> Option<Self> {
        let Some(parent_id) = &mesh.parent else {
            return Some(Self { parent_world: None });
        };
        let worlds = build_deformer_worlds(deformers, pose, defaults);
        let parent_world = worlds.get(parent_id)?.clone();
        Some(Self {
            parent_world: Some(parent_world),
        })
    }

    pub fn local_to_world(&self, local: &[f32]) -> Vec<f32> {
        let mut world = vec![0.0; local.len()];
        for i in (0..local.len()).step_by(2) {
            let (wx, wy) = if let Some(pw) = &self.parent_world {
                pw.apply(local[i], local[i + 1])
            } else {
                (local[i], local[i + 1])
            };
            world[i] = wx;
            world[i + 1] = -wy;
        }
        world
    }

    pub fn world_to_local_linearized(&self, world: &[f32], seed_local: &[f32]) -> Vec<f32> {
        let mut local = seed_local.to_vec();
        match &self.parent_world {
            None => {
                for i in (0..world.len().min(local.len())).step_by(2) {
                    local[i] = world[i];
                    local[i + 1] = -world[i + 1];
                }
            }
            Some(DeformerWorld::Rotation { xform, .. }) => {
                let det = xform.c12 * xform.c13 - xform.c15 * xform.c14;
                if det.abs() >= 1e-12 {
                    for i in (0..world.len().min(local.len())).step_by(2) {
                        let rx = world[i] - xform.ox;
                        let ry = -world[i + 1] - xform.oy;
                        local[i] = (rx * xform.c13 - xform.c15 * ry) / det;
                        local[i + 1] = (xform.c12 * ry - xform.c14 * rx) / det;
                    }
                }
            }
            Some(DeformerWorld::Warp {
                cp,
                cols,
                rows,
                bilinear,
                ..
            }) => {
                let tol_sq = warp_inverse_tolerance_sq(cp);
                for i in (0..world.len().min(local.len())).step_by(2) {
                    let tx = world[i];
                    let ty = -world[i + 1];
                    let mut u = local[i];
                    let mut v = local[i + 1];
                    let mut best_u = u;
                    let mut best_v = v;
                    let mut best_err_sq = f32::MAX;
                    for _ in 0..RIGID_INVERSE_MAX_ITERATIONS {
                        let (sx, sy) = warp_apply(cp, *cols, *rows, *bilinear, u, v);
                        let rx = sx - tx;
                        let ry = sy - ty;
                        let err_sq = rx * rx + ry * ry;
                        if err_sq < best_err_sq {
                            best_err_sq = err_sq;
                            best_u = u;
                            best_v = v;
                        }
                        if err_sq <= tol_sq {
                            break;
                        }
                        let (dux, duy) =
                            warp_apply(cp, *cols, *rows, *bilinear, u + WARP_INVERSE_JACOBIAN_STEP, v);
                        let dx_du = (dux - sx) / WARP_INVERSE_JACOBIAN_STEP;
                        let dy_du = (duy - sy) / WARP_INVERSE_JACOBIAN_STEP;
                        let (dvx, dvy) =
                            warp_apply(cp, *cols, *rows, *bilinear, u, v + WARP_INVERSE_JACOBIAN_STEP);
                        let dx_dv = (dvx - sx) / WARP_INVERSE_JACOBIAN_STEP;
                        let dy_dv = (dvy - sy) / WARP_INVERSE_JACOBIAN_STEP;
                        let det = dx_du * dy_dv - dx_dv * dy_du;
                        if det.abs() < 1e-12 {
                            break;
                        }
                        u += (-rx * dy_dv + dx_dv * ry) / det;
                        v += (-ry * dx_du + dy_du * rx) / det;
                    }
                    local[i] = best_u;
                    local[i + 1] = best_v;
                }
            }
        }
        local
    }
}

fn warp_inverse_tolerance_sq(cp: &[f32]) -> f32 {
    let mut min_x = f32::MAX;
    let mut max_x = f32::MIN;
    let mut min_y = f32::MAX;
    let mut max_y = f32::MIN;
    for i in (0..cp.len()).step_by(2) {
        min_x = min_x.min(cp[i]);
        max_x = max_x.max(cp[i]);
        min_y = min_y.min(cp[i + 1]);
        max_y = max_y.max(cp[i + 1]);
    }
    let span = (max_x - min_x).max(max_y - min_y);
    let tol = (1e-5 * span).max(1e-6);
    tol * tol
}

fn union_mesh_axes(axes_list: &[&[KeyAxis]], live: &HashMap<String, f32>) -> Vec<KeyAxis> {
    let mut keys_by_param: Vec<(String, Vec<f32>)> = Vec::new();
    for axes in axes_list {
        for axis in *axes {
            if !live.contains_key(&axis.parameter) {
                continue;
            }
            if let Some((_, existing)) = keys_by_param.iter_mut().find(|(p, _)| p == &axis.parameter)
            {
                for &k in &axis.keys {
                    if !existing.iter().any(|&ek| (ek - k).abs() <= 1e-6) {
                        existing.push(k);
                    }
                }
            } else {
                keys_by_param.push((axis.parameter.clone(), axis.keys.clone()));
            }
        }
    }
    keys_by_param
        .into_iter()
        .map(|(parameter, mut keys)| {
            keys.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
            KeyAxis { parameter, keys }
        })
        .collect()
}

fn deformer_axes(deformer: &Deformer) -> Vec<KeyAxis> {
    match deformer {
        Deformer::Warp { lattice, shapes, .. } => {
            let mut out = lattice.as_ref().map(|g| g.axes.clone()).unwrap_or_default();
            for s in shapes {
                out.push(KeyAxis {
                    parameter: s.parameter.clone(),
                    keys: s.keys.clone(),
                });
            }
            out
        }
        Deformer::Rotation { pivot, shapes, .. } => {
            let mut out = pivot.as_ref().map(|g| g.axes.clone()).unwrap_or_default();
            for s in shapes {
                out.push(KeyAxis {
                    parameter: s.parameter.clone(),
                    keys: s.keys.clone(),
                });
            }
            out
        }
    }
}

fn sample_mesh_local(mesh: &Mesh, pose: &HashMap<String, f32>, defaults: &HashMap<String, f32>) -> Vec<f32> {
    let base = mesh
        .geometry
        .as_ref()
        .map(|g| g.positions.as_slice())
        .unwrap_or(&[]);
    let mut out = base.to_vec();
    if let Some(grid) = &mesh.offsets {
        if let Some(deltas) = eval_floats_grid(grid, pose, out.len()) {
            for (dst, d) in out.iter_mut().zip(deltas.iter()) {
                *dst += d;
            }
        }
    }
    for b in &mesh.shapes {
        let val = pose
            .get(&b.parameter)
            .copied()
            .or_else(|| defaults.get(&b.parameter).copied())
            .unwrap_or(0.0);
        let Some((i, t)) = eval_blend_weight(&b.keys, val) else {
            continue;
        };
        let (w0, w1) = (1.0 - t, t);
        if let Some(Some(s0)) = b.shapes.get(i) {
            for (dst, d) in out.iter_mut().zip(s0.deltas.iter()) {
                *dst += w0 * d;
            }
        }
        if let Some(Some(s1)) = b.shapes.get(i + 1) {
            for (dst, d) in out.iter_mut().zip(s1.deltas.iter()) {
                *dst += w1 * d;
            }
        }
    }
    out
}

pub fn geometry_residual(
    live_mesh: &Mesh,
    live_deformers: &[Deformer],
    live_defaults: &HashMap<String, f32>,
    canonical_mesh: &Mesh,
    canonical_deformers: &[Deformer],
    before_offsets: &KeyGrid<Vec<f32>>,
    after_offsets: &KeyGrid<Vec<f32>>,
) -> Result<Option<KeyGrid<Vec<f32>>>, String> {
    let base_positions = live_mesh
        .geometry
        .as_ref()
        .map(|g| g.positions.as_slice())
        .ok_or("Missing mesh geometry")?;

    let mut branch_axes: Vec<KeyAxis> = Vec::new();
    for d in canonical_deformers {
        branch_axes.extend(deformer_axes(d));
    }
    let live_axes = live_mesh
        .offsets
        .as_ref()
        .map(|g| g.axes.as_slice())
        .unwrap_or(&[]);
    let mut old_axes = before_offsets.axes.clone();
    old_axes.extend(branch_axes.iter().cloned());
    let mut new_axes = after_offsets.axes.clone();
    new_axes.extend(branch_axes);
    let axes = union_mesh_axes(&[live_axes, &old_axes, &new_axes], live_defaults);

    let mapping = DrawableSpaceMapping::for_mesh(live_mesh, live_deformers, live_defaults, live_defaults)
        .ok_or("Parent cannot place mesh")?;

    let mut old_mesh = canonical_mesh.clone();
    old_mesh.offsets = Some(before_offsets.clone());
    let mut new_mesh = canonical_mesh.clone();
    new_mesh.offsets = Some(after_offsets.clone());

    let dims: Vec<usize> = axes.iter().map(|a| a.keys.len()).collect();
    let total_cells: usize = dims.iter().product::<usize>().max(1);
    let mut cells = Vec::with_capacity(total_cells);
    for flat in 0..total_cells {
        let mut coord = vec![0i32; dims.len()];
        let mut rem = flat;
        for d in (0..dims.len()).rev() {
            coord[d] = (rem % dims[d]) as i32;
            rem /= dims[d];
        }
        let mut pose = live_defaults.clone();
        for (ax_idx, ax) in axes.iter().enumerate() {
            pose.insert(ax.parameter.clone(), ax.keys[coord[ax_idx] as usize]);
        }
        let old_mapping =
            DrawableSpaceMapping::for_mesh(&old_mesh, canonical_deformers, &pose, live_defaults)
                .ok_or("Canonical parent cannot place old mesh")?;
        let new_mapping =
            DrawableSpaceMapping::for_mesh(&new_mesh, canonical_deformers, &pose, live_defaults)
                .ok_or("Canonical parent cannot place new mesh")?;
        let before_world = old_mapping.local_to_world(&sample_mesh_local(&old_mesh, &pose, live_defaults));
        let after_world = new_mapping.local_to_world(&sample_mesh_local(&new_mesh, &pose, live_defaults));
        let authored = sample_mesh_local(live_mesh, &pose, live_defaults);
        let old_local = mapping.world_to_local_linearized(&before_world, base_positions);
        let new_local = mapping.world_to_local_linearized(&after_world, base_positions);
        let reconstructed = mapping.local_to_world(&new_local);
        for (a, b) in reconstructed.iter().zip(after_world.iter()) {
            if (a - b).abs() > 0.05 {
                return Err("Generation migration cannot invert the authored neutral frame".into());
            }
        }
        let deltas: Vec<f32> = authored
            .iter()
            .zip(old_local.iter())
            .zip(new_local.iter())
            .zip(base_positions.iter())
            .map(|(((a, ol), nl), bp)| (a - ol + nl) - bp)
            .collect();
        cells.push(KeyCell {
            coordinate: coord,
            form: deltas,
        });
    }

    if axes.is_empty() && cells.len() == 1 && cells[0].form.iter().all(|&d| d == 0.0) {
        return Ok(None);
    }
    Ok(Some(KeyGrid { axes, cells }))
}
