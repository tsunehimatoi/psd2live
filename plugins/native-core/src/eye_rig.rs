#![allow(dead_code, non_camel_case_types)]

use crate::rig_ir::{
    decode_rig_binary, BlendBinding, BlendLimit, BlendLimitPoint, ChannelValue, KeyAxis, KeyCell,
    KeyGrid, MeshGeometry, MeshShape, Rgb,
};
use psd2live_plugin_sdk::{Json, Request};
use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Debug)]
pub enum Side {
    LEFT,
    RIGHT,
    NONE,
}

impl Side {
    pub fn from_str(s: &str) -> Result<Self, String> {
        match s {
            "LEFT" => Ok(Side::LEFT),
            "RIGHT" => Ok(Side::RIGHT),
            "NONE" => Ok(Side::NONE),
            _ => Err(format!("Unknown side: {s}")),
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            Side::LEFT => "LEFT",
            Side::RIGHT => "RIGHT",
            Side::NONE => "NONE",
        }
    }

    pub fn suffix(&self) -> &'static str {
        match self {
            Side::LEFT => "L",
            Side::RIGHT => "R",
            Side::NONE => "",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum EyeRegion {
    UPPER,
    LOWER,
}

impl EyeRegion {
    pub fn from_str(s: &str) -> Result<Self, String> {
        match s {
            "UPPER" => Ok(EyeRegion::UPPER),
            "LOWER" => Ok(EyeRegion::LOWER),
            _ => Err(format!("Unknown eye region: {s}")),
        }
    }

    pub fn lower_name(&self) -> &'static str {
        match self {
            EyeRegion::UPPER => "upper",
            EyeRegion::LOWER => "lower",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum EyePrimitive {
    LINE,
    TUFT,
    SIDE,
    SKIN,
    AUTO,
}

impl EyePrimitive {
    pub fn from_str(s: &str) -> Result<Self, String> {
        match s {
            "LINE" => Ok(EyePrimitive::LINE),
            "TUFT" => Ok(EyePrimitive::TUFT),
            "SIDE" => Ok(EyePrimitive::SIDE),
            "SKIN" => Ok(EyePrimitive::SKIN),
            "AUTO" => Ok(EyePrimitive::AUTO),
            _ => Err(format!("Unknown eye primitive: {s}")),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Bounds {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Bounds {
    pub fn width(&self) -> f32 {
        self.right - self.left
    }
    pub fn height(&self) -> f32 {
        self.bottom - self.top
    }
    pub fn center_x(&self) -> f32 {
        (self.left + self.right) * 0.5
    }
    pub fn center_y(&self) -> f32 {
        (self.top + self.bottom) * 0.5
    }
}

#[derive(Clone, Copy, Debug)]
pub struct EyePoint {
    pub x: f32,
    pub y: f32,
}

impl EyePoint {
    pub fn distance(self, other: EyePoint) -> f32 {
        (self.x - other.x).hypot(self.y - other.y)
    }
}

#[derive(Clone)]
pub struct EyeStroke {
    pub region: EyeRegion,
    pub points: Vec<EyePoint>,
    pub half_widths: Vec<f32>,
    pub observed: Vec<bool>,
}

impl EyeStroke {
    pub fn at(&self, x: f32) -> EyePoint {
        if let Some(i) = self.points.iter().position(|p| p.x >= x) {
            if i == 0 {
                return self.points[0];
            }
            let a = self.points[i - 1];
            let b = self.points[i];
            let t = (x - a.x) / (b.x - a.x).max(1e-8);
            EyePoint {
                x: a.x + (b.x - a.x) * t,
                y: a.y + (b.y - a.y) * t,
            }
        } else {
            *self.points.last().unwrap()
        }
    }

    pub fn half_width(&self, x: f32) -> f32 {
        if let Some(i) = self.points.iter().position(|p| p.x >= x) {
            if i == 0 {
                return self.half_widths[0];
            }
            let t = ((x - self.points[i - 1].x) / (self.points[i].x - self.points[i - 1].x).max(1e-8))
                .clamp(0.0, 1.0);
            self.half_widths[i - 1] + (self.half_widths[i] - self.half_widths[i - 1]) * t
        } else {
            *self.half_widths.last().unwrap()
        }
    }

    pub fn is_observed(&self, x: f32) -> bool {
        if x < self.points[0].x || x > self.points.last().unwrap().x {
            return false;
        }
        let mut best_i = 0;
        let mut best_d = f32::INFINITY;
        for (i, p) in self.points.iter().enumerate() {
            let d = (p.x - x).abs();
            if d < best_d {
                best_d = d;
                best_i = i;
            }
        }
        self.observed[best_i]
    }
}

#[derive(Clone)]
pub struct EyeContact {
    pub region: EyeRegion,
    pub from: f32,
    pub to: f32,
    pub centre: EyePoint,
}

#[derive(Clone)]
pub struct EyeBranch {
    pub root: EyePoint,
    pub tip: EyePoint,
    pub points: Vec<EyePoint>,
}

#[derive(Clone)]
pub struct EyeMaterialPart {
    pub id: String,
    pub region: EyeRegion,
    pub primitive: EyePrimitive,
    pub samples: Vec<EyePoint>,
    pub centre: EyePoint,
    pub contacts: Vec<EyeContact>,
    pub branches: Vec<EyeBranch>,
}

#[derive(Clone)]
pub struct EyeComponent {
    pub id: String,
    pub layer_id: String,
    pub side: Side,
    pub region: EyeRegion,
    pub primitive: EyePrimitive,
    pub samples: Vec<EyePoint>,
    pub centre: EyePoint,
    pub contact: EyeContact,
    pub branches: Vec<EyeBranch>,
    pub confidence: f32,
    pub source_stamp: String,
    pub contacts: Vec<EyeContact>,
    pub parts: Vec<EyeMaterialPart>,
}

#[derive(Clone)]
pub struct EyeArtwork {
    pub side: Side,
    pub upper: EyeStroke,
    pub lower: EyeStroke,
    pub components: Vec<EyeComponent>,
    pub opening_layers: HashSet<String>,
}

impl EyeArtwork {
    pub fn components_for_layer(&self, layer_id: &str) -> Vec<&EyeComponent> {
        self.components.iter().filter(|c| c.layer_id == layer_id).collect()
    }
}

#[derive(Clone)]
pub struct EyeFit {
    pub side: Side,
    pub origin_x: f32,
    pub origin_y: f32,
    pub axis_x: f32,
    pub axis_y: f32,
    pub width: f32,
    pub upper: Vec<f32>,
    pub lower: Vec<f32>,
    pub closed: Option<Vec<f32>>,
    pub iris: Option<Bounds>,
    pub artwork: Option<EyeArtwork>,
    pub opening_layers: HashSet<String>,
    pub upper_correction: Option<Vec<f32>>,
}

impl EyeFit {
    pub const SAMPLES: usize = 17;

    pub fn u(k: usize) -> f32 {
        k as f32 / (Self::SAMPLES - 1) as f32
    }

    pub fn sample(curve: &[f32], u: f32) -> f32 {
        let f = u.clamp(0.0, 1.0) * (Self::SAMPLES - 1) as f32;
        let i = (f as usize).min(Self::SAMPLES - 2);
        curve[i] + (curve[i + 1] - curve[i]) * (f - i as f32)
    }

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

    pub fn corner_v(&self) -> f32 {
        *self.upper.last().unwrap()
    }

    pub fn baseline(&self, u: f32) -> f32 {
        self.corner_v() * u
    }

    pub fn to_canvas(&self, u: f32, v: f32) -> (f32, f32) {
        (
            self.origin_x + (u * self.axis_x + v * self.down_x()) * self.width,
            self.origin_y + (u * self.axis_y + v * self.down_y()) * self.width,
        )
    }

    pub fn to_eye(&self, x: f32, y: f32) -> (f32, f32) {
        let dx = (x - self.origin_x) / self.width;
        let dy = (y - self.origin_y) / self.width;
        (
            dx * self.axis_x + dy * self.axis_y,
            dx * self.down_x() + dy * self.down_y(),
        )
    }

    pub fn canvas_delta(&self, du: f32, dv: f32) -> (f32, f32) {
        (
            (du * self.axis_x + dv * self.down_x()) * self.width,
            (du * self.axis_y + dv * self.down_y()) * self.width,
        )
    }

    pub fn moved(&self, moves: &HashMap<String, (f32, f32)>) -> EyeFit {
        if moves.is_empty() {
            return self.clone();
        }
        let inner = *moves.get("inner").unwrap_or(&(0.0, 0.0));
        let outer = *moves.get("outer").unwrap_or(&(0.0, 0.0));
        let shifted = |curve: &[f32], peak: (f32, f32)| -> Vec<(f32, f32)> {
            (0..curve.len())
                .map(|k| {
                    let u = Self::u(k);
                    let (x, y) = self.to_canvas(u, curve[k]);
                    let m = hump(u).max(0.0);
                    let dx = inner.0 * (1.0 - u) + outer.0 * u + peak.0 * m;
                    let dy = inner.1 * (1.0 - u) + outer.1 * u + peak.1 * m;
                    (x + dx, y + dy)
                })
                .collect()
        };
        let upper_pts = shifted(&self.upper, *moves.get("upper").unwrap_or(&(0.0, 0.0)));
        let lower_pts = shifted(&self.lower, *moves.get("lower").unwrap_or(&(0.0, 0.0)));
        let closed_pts = self.closed.as_ref().map(|c| shifted(c, (0.0, 0.0)));
        let (ox, oy) = upper_pts[0];
        let (ex, ey) = *upper_pts.last().unwrap();
        let width = ((ex - ox) * self.axis_x + (ey - oy) * self.axis_y).max(1.0);
        let mut frame = self.clone();
        frame.origin_x = ox;
        frame.origin_y = oy;
        frame.width = width;
        let resample = |pts: &[(f32, f32)]| -> Vec<f32> {
            let mut framed: Vec<(f32, f32)> = pts.iter().map(|&(x, y)| frame.to_eye(x, y)).collect();
            framed.sort_by(|a, b| a.0.total_cmp(&b.0));
            (0..Self::SAMPLES)
                .map(|k| {
                    let u = k as f32 / (Self::SAMPLES - 1) as f32;
                    match framed.iter().position(|p| p.0 >= u) {
                        None => framed.last().unwrap().1,
                        Some(0) => framed[0].1,
                        Some(i) => {
                            let a = framed[i - 1];
                            let b = framed[i];
                            let t = ((u - a.0) / (b.0 - a.0).max(1e-5)).clamp(0.0, 1.0);
                            a.1 + (b.1 - a.1) * t
                        }
                    }
                })
                .collect()
        };
        let moved_upper = resample(&upper_pts);
        let moved_lower = resample(&lower_pts);
        let moved_closed = closed_pts.as_ref().map(|c| resample(c));
        let orig_upper_pts: Vec<(f32, f32)> = (0..self.upper.len())
            .map(|k| self.to_canvas(Self::u(k), self.upper[k]))
            .collect();
        let source_upper = resample(&orig_upper_pts);
        let upper_correction: Vec<f32> = moved_upper
            .iter()
            .zip(&source_upper)
            .map(|(a, b)| a - b)
            .collect();
        frame.upper = moved_upper;
        frame.lower = moved_lower;
        frame.closed = moved_closed;
        frame.artwork = None;
        frame.upper_correction = Some(upper_correction);
        frame
    }
}

pub struct EyelidModel {
    centre_y: f32,
    corner_depth: f32,
}

impl EyelidModel {
    pub fn new(radius: f32, centre_y: f32) -> Self {
        let corner_depth = (radius * radius - 0.25 - centre_y * centre_y).max(1e-4).sqrt();
        Self {
            centre_y,
            corner_depth,
        }
    }

    pub fn edge(&self, u: f32, angle: f32) -> f32 {
        let b = 2.0 * (self.centre_y * angle.cos() + self.corner_depth * angle.sin());
        let c = (u - 0.5) * (u - 0.5) - 0.25;
        let root = (b * b - 4.0 * c).max(0.0).sqrt();
        let s = if angle.sin() >= 0.0 {
            (-b + root) * 0.5
        } else {
            (-b - root) * 0.5
        };
        -s * angle.cos()
    }

    pub fn angle_for(&self, height: f32) -> f32 {
        let mut lo = 0.05f32;
        let mut hi = 2.6f32;
        let rising = self.edge(0.5, lo) < self.edge(0.5, hi);
        for _ in 0..48 {
            let mid = (lo + hi) * 0.5;
            if (self.edge(0.5, mid) < height) == rising {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        (lo + hi) * 0.5
    }
}

const EYE_OPENING_CURVES: [[f32; 5]; 4] = [
    [
        -0.020840220153331757,
        0.04423410817980766,
        -0.020951110869646072,
        0.0021297826897352934,
        0.002628128742799163,
    ],
    [
        -0.8473459482192993,
        0.03265843167901039,
        0.20850403606891632,
        -0.04963310807943344,
        0.05584998056292534,
    ],
    [
        0.0030613390263170004,
        0.06932855397462845,
        -0.03216902166604996,
        -0.022964660078287125,
        -0.010055518709123135,
    ],
    [
        0.10951793938875198,
        -0.04601138457655907,
        -0.5181517004966736,
        0.029036708176136017,
        -0.17435814440250397,
    ],
];

fn eye_opening_model_at(u: f32, strength: f32) -> [f32; 4] {
    let x = 2.0 * u.clamp(0.0, 1.0) - 1.0;
    let mut out = [0.0f32; 4];
    for (idx, c) in EYE_OPENING_CURVES.iter().enumerate() {
        let mut b1 = 0.0f32;
        let mut b2 = 0.0f32;
        for j in (1..c.len()).rev() {
            let b = c[j] + 2.0 * x * b1 - b2;
            b2 = b1;
            b1 = b;
        }
        out[idx] = (c[0] + x * b1 - b2) * strength;
    }
    out
}

pub fn hump(u: f32) -> f32 {
    (std::f32::consts::PI * u.clamp(0.0, 1.0)).sin()
}

pub struct EyeBand {
    fit: EyeFit,
    stroke: EyeStroke,
    roundness: f32,
    smile_arc: f32,
    centres: Vec<f32>,
    pub half_width: f32,
    closed_override: Option<Vec<f32>>,
    closed_smile_override: Option<Vec<f32>>,
}

impl EyeBand {
    pub fn sample_hermite(values: &[f32], u: f32) -> f32 {
        let last = values.len() - 1;
        let tangent = |k: usize| -> f32 {
            if k == 0 {
                values[1] - values[0]
            } else if k == last {
                values[k] - values[k - 1]
            } else {
                (values[k + 1] - values[k - 1]) * 0.5
            }
        };
        if u < 0.0 {
            return values[0] + tangent(0) * u * last as f32;
        }
        if u > 1.0 {
            return values[last] + tangent(last) * (u - 1.0) * last as f32;
        }
        let x = u * last as f32;
        let i = (x as usize).min(last - 1);
        let t = x - i as f32;
        let t2 = t * t;
        let t3 = t2 * t;
        (2.0 * t3 - 3.0 * t2 + 1.0) * values[i]
            + (t3 - 2.0 * t2 + t) * tangent(i)
            + (-2.0 * t3 + 3.0 * t2) * values[i + 1]
            + (t3 - t2) * tangent(i + 1)
    }

    pub fn new(fit: &EyeFit, stroke: &EyeStroke, roundness: f32, smile_arc: f32) -> Self {
        let n = EyeFit::SAMPLES;
        let mut centres = Vec::with_capacity(n);
        for i in 0..n {
            let u = i as f32 / (n - 1) as f32;
            let radius = stroke.half_width(u).max(1.0 / (n - 1) as f32);
            let mut sum = 0.0f32;
            let mut cnt = 0usize;
            for p in &stroke.points {
                if (p.x - u).abs() <= radius && (0.0..=1.0).contains(&p.x) {
                    sum += p.y;
                    cnt += 1;
                }
            }
            centres.push(if cnt == 0 {
                stroke.at(u).y
            } else {
                sum / cnt as f32
            });
        }
        let mut hws: Vec<f32> = stroke
            .half_widths
            .iter()
            .enumerate()
            .filter(|&(i, _)| stroke.observed[i] && (0.2..=0.8).contains(&stroke.points[i].x))
            .map(|(_, &w)| w)
            .collect();
        hws.sort_by(|a, b| a.total_cmp(b));
        let half_width = if hws.is_empty() {
            1.0 / fit.width
        } else {
            hws[hws.len() / 2]
        }
        .max(0.5 / fit.width);
        Self {
            fit: fit.clone(),
            stroke: stroke.clone(),
            roundness,
            smile_arc,
            centres,
            half_width,
            closed_override: None,
            closed_smile_override: None,
        }
    }

    pub fn with_closed_override(mut self, normal: Vec<f32>, smile: Vec<f32>) -> Self {
        self.closed_override = Some(normal);
        self.closed_smile_override = Some(smile);
        self
    }

    pub fn centre(&self, u: f32) -> f32 {
        Self::sample_hermite(&self.centres, u)
    }

    pub fn slope(&self, u: f32) -> f32 {
        (self.centre(u + 0.001) - self.centre(u - 0.001)) / 0.002
    }

    pub fn closed(&self, u: f32, smile: bool) -> f32 {
        if let Some(ref ov) = if smile {
            &self.closed_smile_override
        } else {
            &self.closed_override
        } {
            return Self::sample_hermite(ov, u);
        }
        if !smile {
            if let Some(ref closed) = self.fit.closed {
                let corr = self
                    .fit
                    .upper_correction
                    .as_ref()
                    .map(|c| Self::sample_hermite(c, u))
                    .unwrap_or(0.0);
                return Self::sample_hermite(closed, u) + corr;
            }
        }
        let x = u.clamp(0.0, 1.0);
        let tilt = self.fit.corner_v();
        let midpoint = if smile {
            self.fit.baseline(0.5) - self.smile_arc
        } else {
            EyeFit::sample(&self.fit.lower, 0.5) - self.half_width * 2.0
        };
        let depth = if smile {
            -self.smile_arc
        } else {
            self.half_width * self.roundness
        };
        let end_slope = tilt + depth * 4.0 * (1.0 - 2.0 * x);
        let corr = self
            .fit
            .upper_correction
            .as_ref()
            .map(|c| Self::sample_hermite(c, u))
            .unwrap_or(0.0);
        midpoint + tilt * (x - 0.5) + depth * (4.0 * x * (1.0 - x) - 1.0) + (u - x) * end_slope + corr
    }

    pub fn move_pt(
        &self,
        u: f32,
        v: f32,
        open: f32,
        smile: bool,
        turn_tuft: bool,
        tooth_weight: f32,
    ) -> EyePoint {
        let o = open.clamp(0.0, 1.0);
        let rest = self.centre(u);
        let offset = v - rest;
        let target = self.closed(u, smile);
        let source_slope = self.slope(u);
        let target_slope =
            (self.closed(u + 0.001, smile) - self.closed(u - 0.001, smile)) / 0.002;
        let angle = (source_slope * o + target_slope * (1.0 - o)).atan() - source_slope.atan();
        let exposed = (-offset - self.half_width).max(0.0);
        let t = (exposed / self.half_width).clamp(0.0, 1.0);
        let blend = t * t * (3.0 - 2.0 * t);
        let tooth = if turn_tuft && !smile {
            2.0 * exposed * blend * (1.0 - o) * tooth_weight
        } else {
            0.0
        };
        let side = (offset - self.half_width).max(0.0);
        let retract = if self.stroke.region == EyeRegion::UPPER {
            side * side / (side + self.half_width) * (1.0 - o)
        } else {
            0.0
        };
        let dy = (target - rest) * (1.0 - o) + offset * (angle.cos() - 1.0) + tooth - retract;
        EyePoint {
            x: u - offset * angle.sin(),
            y: v + dy,
        }
    }
}

pub struct BiharmonicField {
    columns: usize,
    rows: usize,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    field: [Vec<f32>; 2],
}

impl BiharmonicField {
    pub fn new(
        columns: usize,
        rows: usize,
        left: f32,
        top: f32,
        right: f32,
        bottom: f32,
        fixed: &HashMap<usize, (f32, f32)>,
    ) -> Self {
        let n = columns * rows;
        let mut field = [vec![0.0f32; n], vec![0.0f32; n]];
        for axis in 0..2 {
            let mut value = vec![0.0f64; n];
            let mut is_fixed = vec![false; n];
            for (&node, &(vx, vy)) in fixed {
                is_fixed[node] = true;
                value[node] = if axis == 0 { vx as f64 } else { vy as f64 };
            }
            let laplacian = |d: &[f64]| -> Vec<f64> {
                let mut out = vec![0.0f64; n];
                for r in 0..rows {
                    for c in 0..columns {
                        let i = r * columns + c;
                        let mut s = -4.0 * d[i];
                        if c > 0 {
                            s += d[i - 1];
                        }
                        if c + 1 < columns {
                            s += d[i + 1];
                        }
                        if r > 0 {
                            s += d[i - columns];
                        }
                        if r + 1 < rows {
                            s += d[i + columns];
                        }
                        out[i] = s;
                    }
                }
                out
            };
            let normal = |d: &[f64]| -> Vec<f64> { laplacian(&laplacian(d)) };
            let mut rhs = normal(&value);
            for i in 0..n {
                rhs[i] = if is_fixed[i] { 0.0 } else { -rhs[i] };
            }
            let mut x = vec![0.0f64; n];
            let mut r = rhs.clone();
            let mut p = r.clone();
            let mut rr: f64 = r.iter().map(|v| v * v).sum();
            let rhs_norm: f64 = rhs.iter().map(|v| v * v).sum();
            let tolerance = 1e-10 * rhs_norm.max(1e-30);
            let mut iter = 0;
            while rr > tolerance && iter < 4 * n {
                let mut masked = vec![0.0f64; n];
                for i in 0..n {
                    if !is_fixed[i] {
                        masked[i] = p[i];
                    }
                }
                let mut ap = normal(&masked);
                for i in 0..n {
                    if is_fixed[i] {
                        ap[i] = 0.0;
                    }
                }
                let pap: f64 = p.iter().zip(&ap).map(|(a, b)| a * b).sum();
                if pap <= 0.0 {
                    break;
                }
                let alpha = rr / pap;
                for i in 0..n {
                    x[i] += alpha * p[i];
                    r[i] -= alpha * ap[i];
                }
                let next: f64 = r.iter().map(|v| v * v).sum();
                let beta = next / rr;
                for i in 0..n {
                    p[i] = r[i] + beta * p[i];
                }
                rr = next;
                iter += 1;
            }
            for i in 0..n {
                field[axis][i] = if is_fixed[i] { value[i] } else { x[i] } as f32;
            }
        }
        Self {
            columns,
            rows,
            left,
            top,
            right,
            bottom,
            field,
        }
    }

    pub fn nearest(columns: usize, rows: usize, left: f32, top: f32, right: f32, bottom: f32, x: f32, y: f32) -> usize {
        let col = (((x - left) / (right - left) * (columns - 1) as f32).round() as isize)
            .clamp(0, (columns - 1) as isize) as usize;
        let row = (((y - top) / (bottom - top) * (rows - 1) as f32).round() as isize)
            .clamp(0, (rows - 1) as isize) as usize;
        row * columns + col
    }

    pub fn at(&self, x: f32, y: f32) -> (f32, f32) {
        let fx = (x - self.left) / (self.right - self.left) * (self.columns - 1) as f32;
        let fy = (y - self.top) / (self.bottom - self.top) * (self.rows - 1) as f32;
        if fx < 0.0 || fy < 0.0 || fx > (self.columns - 1) as f32 || fy > (self.rows - 1) as f32 {
            return (0.0, 0.0);
        }
        let c = (fx.floor() as usize).min(self.columns - 2);
        let r = (fy.floor() as usize).min(self.rows - 2);
        let tx = fx - c as f32;
        let ty = fy - r as f32;
        let lerp = |axis: usize| -> f32 {
            let f = &self.field[axis];
            let top = f[r * self.columns + c] * (1.0 - tx) + f[r * self.columns + c + 1] * tx;
            let bottom =
                f[(r + 1) * self.columns + c] * (1.0 - tx) + f[(r + 1) * self.columns + c + 1] * tx;
            top * (1.0 - ty) + bottom * ty
        };
        (lerp(0), lerp(1))
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub enum EyeMesh {
    WHITE,
    UPPER_LASH,
    LOWER_LASH,
    SKIN,
}

pub fn mesh_of(tag: &str, role: Option<&str>) -> Option<EyeMesh> {
    if tag == "EYEWHITE" {
        Some(EyeMesh::WHITE)
    } else if tag != "EYELASH" {
        None
    } else if role == Some("LOWER") {
        Some(EyeMesh::LOWER_LASH)
    } else if role == Some("LID") || role == Some("SHADOW") {
        Some(EyeMesh::SKIN)
    } else {
        Some(EyeMesh::UPPER_LASH)
    }
}

#[derive(Clone)]
pub struct EyePose {
    pub upper: Vec<f32>,
    pub lower: Vec<f32>,
    pub fold: f32,
    pub lashes: f32,
    pub upper_dx: Option<Vec<f32>>,
    pub lower_dx: Option<Vec<f32>>,
    pub open: Option<f32>,
    pub smiling: bool,
}

#[derive(Clone, Copy, Default)]
pub struct EyeOptions {
    pub smile: bool,
    pub smile_arc: f32,
    pub smile_push: f32,
    pub wide: bool,
    pub wide_lift: f32,
    pub gaze: bool,
    pub gaze_follow: f32,
    pub emotion: bool,
    pub emotion_strength: f32,
    pub lid_fade: bool,
    pub aftershock: bool,
    pub softness: f32,
    pub highlight: bool,
    pub parallax: f32,
    pub squash: bool,
    pub brow_coupling: bool,
    pub brow_strength: f32,
    pub cheek_push: bool,
    pub cheek_strength: f32,
}

impl EyeOptions {
    pub fn defaults() -> Self {
        Self {
            smile_arc: 0.12,
            smile_push: 0.5,
            wide_lift: 0.1,
            gaze_follow: 1.0,
            emotion_strength: 1.0,
            softness: 1.0,
            parallax: 1.0,
            brow_strength: 1.0,
            cheek_strength: 1.0,
            ..Default::default()
        }
    }
}

pub struct EyeRig {
    pub fit: EyeFit,
    share: f32,
    squash: f32,
    smile_arc: f32,
    smile_push: f32,
    wide_lift: f32,
    model: EyelidModel,
    closed_target: Vec<f32>,
    open_rise: f32,
    closed_rise: f32,
    open_angle: f32,
    pub crease: f32,
    pub opening: f32,
    pub keys: Vec<f32>,
    upper_band: Option<EyeBand>,
    lower_band: Option<EyeBand>,
    smile_closed: Vec<f32>,
    smile_stroke_closed: Vec<f32>,
    fields: std::cell::RefCell<HashMap<Vec<u32>, BiharmonicField>>,
}

impl EyeRig {
    pub const SWAP: f32 = 0.06;
    pub const CREASE: f32 = 0.15;
    pub const LATEST_CREASE: f32 = 0.25;
    const TAIL: f32 = 0.12;
    const TUCK: f32 = 0.03;
    const WING_DROOP: f32 = 0.35;
    const TIP: f32 = 0.04;

    pub fn new(
        fit: EyeFit,
        lower_share: f32,
        roundness: f32,
        lash_squash: f32,
        smile_arc: f32,
        smile_push: f32,
        wide_lift: f32,
    ) -> Self {
        let model = EyelidModel::new(0.5 + 0.18 * roundness, -0.18 * roundness);
        let mid = EyeFit::SAMPLES / 2;
        let closed_target: Vec<f32> = match &fit.closed {
            Some(c) => c.clone(),
            None => (0..EyeFit::SAMPLES)
                .map(|k| fit.lower[k] - 0.15 * (fit.lower[k] - fit.upper[k]))
                .collect(),
        };
        let rise = |curve: &[f32], k: usize| curve[k] - fit.baseline(EyeFit::u(k));
        let open_rise = rise(&fit.upper, mid);
        let closed_rise = rise(&closed_target, mid);
        let open_angle = model.angle_for(open_rise);
        let crease = (closed_rise / (closed_rise - open_rise).max(1e-3))
            .clamp(Self::CREASE, Self::LATEST_CREASE);
        let opening = fit.lower[mid] - fit.upper[mid];
        let keys = vec![
            0.0,
            if fit.closed.is_some() {
                Self::SWAP
            } else {
                crease * 0.5
            },
            crease,
            crease + (1.0 - crease) / 3.0,
            crease + 2.0 * (1.0 - crease) / 3.0,
            1.0,
        ];
        let upper_band = fit.artwork.as_ref().and_then(|art| {
            let obs = art.upper.observed.iter().filter(|&&y| y).count();
            if obs >= art.upper.points.len() / 3 {
                Some(EyeBand::new(&fit, &art.upper, roundness, smile_arc))
            } else {
                None
            }
        });
        let smile_closed: Vec<f32> = (0..EyeFit::SAMPLES)
            .map(|k| {
                let u = EyeFit::u(k);
                if let Some(ref band) = upper_band {
                    band.closed(u, true) + band.half_width
                } else {
                    fit.baseline(u) - smile_arc * 4.0 * u * (1.0 - u)
                }
            })
            .collect();
        let smile_stroke_closed: Vec<f32> = if let Some(ref band) = upper_band {
            (0..EyeFit::SAMPLES)
                .map(|k| band.closed(EyeFit::u(k), true))
                .collect()
        } else {
            smile_closed.clone()
        };

        let mut rig = Self {
            fit: fit.clone(),
            share: lower_share,
            squash: lash_squash,
            smile_arc,
            smile_push,
            wide_lift,
            model,
            closed_target,
            open_rise,
            closed_rise,
            open_angle,
            crease,
            opening,
            keys,
            upper_band,
            lower_band: None,
            smile_closed,
            smile_stroke_closed,
            fields: std::cell::RefCell::new(HashMap::new()),
        };

        let upper_zero = rig.upper(0.0);
        let lower_band = fit.artwork.as_ref().and_then(|art| {
            if art.lower.observed.iter().any(|&y| y) {
                let closed_norm: Vec<f32> = (0..EyeFit::SAMPLES)
                    .map(|k| {
                        let u = EyeFit::u(k);
                        rig.upper_band
                            .as_ref()
                            .map(|b| b.closed(u, false))
                            .unwrap_or_else(|| EyeFit::sample(&upper_zero, u))
                    })
                    .collect();
                let closed_sm: Vec<f32> = (0..EyeFit::SAMPLES)
                    .map(|k| {
                        let u = EyeFit::u(k);
                        rig.upper_band
                            .as_ref()
                            .map(|b| b.closed(u, true))
                            .unwrap_or_else(|| EyeFit::sample(&upper_zero, u))
                    })
                    .collect();
                Some(
                    EyeBand::new(&fit, &art.lower, roundness, smile_arc)
                        .with_closed_override(closed_norm, closed_sm),
                )
            } else {
                None
            }
        });
        rig.lower_band = lower_band;
        rig
    }

    fn expanded(&self, open: f32) -> EyePose {
        let mut upper = Vec::with_capacity(EyeFit::SAMPLES);
        let mut lower = Vec::with_capacity(EyeFit::SAMPLES);
        let mut u_dx = Vec::with_capacity(EyeFit::SAMPLES);
        let mut l_dx = Vec::with_capacity(EyeFit::SAMPLES);
        for k in 0..EyeFit::SAMPLES {
            let m = eye_opening_model_at(EyeFit::u(k), self.wide_lift * (open - 1.0));
            upper.push(self.fit.upper[k] + m[1] * self.opening);
            lower.push(self.fit.lower[k] + m[3] * self.opening);
            u_dx.push(m[0]);
            l_dx.push(m[2]);
        }
        EyePose {
            upper,
            lower,
            fold: 0.0,
            lashes: 1.0,
            upper_dx: Some(u_dx),
            lower_dx: Some(l_dx),
            open: None,
            smiling: false,
        }
    }

    pub fn upper(&self, open: f32) -> Vec<f32> {
        if open > 1.0 {
            return self.expanded(open).upper;
        }
        if open == 1.0 {
            return self.fit.upper.clone();
        }
        if open <= 0.0 {
            if let Some(ref c) = self.fit.closed {
                return c.clone();
            }
        }
        if let Some(ref band) = self.upper_band {
            let o = open.clamp(0.0, 1.0);
            return (0..self.fit.upper.len())
                .map(|k| {
                    let target = self
                        .fit
                        .closed
                        .as_ref()
                        .map(|c| c[k])
                        .unwrap_or_else(|| band.closed(EyeFit::u(k), false) + band.half_width);
                    target + (self.fit.upper[k] - target) * o
                })
                .collect();
        }
        let height = if open >= self.crease {
            self.open_rise * (open - self.crease) / (1.0 - self.crease)
        } else {
            self.closed_rise * (1.0 - open / self.crease)
        };
        let angle = self.model.angle_for(height);
        let keep = (height / self.open_rise).clamp(0.0, 1.0);
        let to_closed = if open >= self.crease {
            0.0
        } else {
            1.0 - open / self.crease
        };
        let closed_angle = self.model.angle_for(self.closed_rise);
        (0..EyeFit::SAMPLES)
            .map(|k| {
                let u = EyeFit::u(k);
                let rise_k = self.fit.upper[k] - self.fit.baseline(u);
                let lid = self.fit.baseline(u)
                    + self.model.edge(u, angle)
                    + (rise_k - self.model.edge(u, self.open_angle)) * keep;
                lid + (self.closed_target[k]
                    - (self.fit.baseline(u) + self.model.edge(u, closed_angle)))
                    * to_closed
            })
            .collect()
    }

    pub fn lower(&self, open: f32) -> Vec<f32> {
        if open > 1.0 {
            return self.expanded(open).lower;
        }
        let closed = self.upper(0.0);
        (0..EyeFit::SAMPLES)
            .map(|k| {
                self.fit.lower[k]
                    - self.share * (1.0 - open) * (self.fit.lower[k] - closed[k]).max(0.0)
            })
            .collect()
    }

    pub fn pose(&self, open: f32) -> EyePose {
        if open > 1.0 {
            self.expanded(open)
        } else {
            EyePose {
                upper: self.upper(open),
                lower: self.lower(open),
                fold: ((self.crease - open) / self.crease).clamp(0.0, 1.0).sqrt(),
                lashes: self.squash + (1.0 - self.squash) * open.min(1.0),
                upper_dx: None,
                lower_dx: None,
                open: Some(open),
                smiling: false,
            }
        }
    }

    pub fn smile_pose(&self, open: f32) -> EyePose {
        let o = open.clamp(0.0, 1.0);
        let up = (0..EyeFit::SAMPLES)
            .map(|k| self.smile_closed[k] + (self.fit.upper[k] - self.smile_closed[k]) * o)
            .collect();
        let push = self.smile_push * (1.0 - o) + (1.0 - self.smile_push) * (1.0 - o) * (1.0 - o);
        let low = (0..EyeFit::SAMPLES)
            .map(|k| self.fit.lower[k] - push * (self.fit.lower[k] - self.smile_closed[k]))
            .collect();
        EyePose {
            upper: up,
            lower: low,
            fold: ((0.3 - o) / 0.3).clamp(0.0, 1.0).sqrt(),
            lashes: self.squash + (1.0 - self.squash) * o,
            upper_dx: None,
            lower_dx: None,
            open: Some(open),
            smiling: true,
        }
    }

    pub fn shape(&self, d_upper: impl Fn(f32) -> f32, d_lower: impl Fn(f32) -> f32) -> EyePose {
        EyePose {
            upper: (0..EyeFit::SAMPLES)
                .map(|k| self.fit.upper[k] + d_upper(EyeFit::u(k)))
                .collect(),
            lower: (0..EyeFit::SAMPLES)
                .map(|k| self.fit.lower[k] + d_lower(EyeFit::u(k)))
                .collect(),
            fold: 0.0,
            lashes: 1.0,
            upper_dx: None,
            lower_dx: None,
            open: None,
            smiling: false,
        }
    }

    fn field_at(&self, pose: &EyePose, u: f32, v: f32) -> (f32, f32) {
        let mut key = Vec::with_capacity(EyeFit::SAMPLES * 4);
        for &x in &pose.upper {
            key.push(x.to_bits());
        }
        for &x in &pose.lower {
            key.push(x.to_bits());
        }
        if let Some(ref d) = pose.upper_dx {
            for &x in d {
                key.push(x.to_bits());
            }
        }
        if let Some(ref d) = pose.lower_dx {
            for &x in d {
                key.push(x.to_bits());
            }
        }
        let mut cache = self.fields.borrow_mut();
        let field = cache.entry(key).or_insert_with(|| {
            let mut fixed = HashMap::new();
            for k in 0..EyeFit::SAMPLES {
                let uu = EyeFit::u(k);
                let idx_u = BiharmonicField::nearest(
                    25,
                    33,
                    -0.45,
                    -1.4,
                    1.45,
                    0.9,
                    uu,
                    self.fit.upper[k],
                );
                fixed.insert(
                    idx_u,
                    (
                        pose.upper_dx.as_ref().map(|d| d[k]).unwrap_or(0.0),
                        pose.upper[k] - self.fit.upper[k],
                    ),
                );
                let idx_l = BiharmonicField::nearest(
                    25,
                    33,
                    -0.45,
                    -1.4,
                    1.45,
                    0.9,
                    uu,
                    self.fit.lower[k],
                );
                fixed.entry(idx_l).or_insert((
                    pose.lower_dx.as_ref().map(|d| d[k]).unwrap_or(0.0),
                    pose.lower[k] - self.fit.lower[k],
                ));
            }
            BiharmonicField::new(25, 33, -0.45, -1.4, 1.45, 0.9, &fixed)
        });
        field.at(u, v)
    }

    fn nearest_rim_u(rim: &[f32], u: f32, v: f32) -> f32 {
        let mut best = f32::INFINITY;
        let mut at = u.clamp(0.0, 1.0);
        for k in 0..EyeFit::SAMPLES - 1 {
            let x = EyeFit::u(k);
            let dx = EyeFit::u(k + 1) - x;
            let dy = rim[k + 1] - rim[k];
            let t = (((u - x) * dx + (v - rim[k]) * dy) / (dx * dx + dy * dy)).clamp(0.0, 1.0);
            let px = x + t * dx;
            let py = rim[k] + t * dy;
            let dist = (u - px) * (u - px) + (v - py) * (v - py);
            if dist < best {
                best = dist;
                at = px;
            }
        }
        at
    }

    fn widened_move(&self, mesh: EyeMesh, u: f32, v: f32, pose: &EyePose) -> (f32, f32) {
        let top = EyeFit::sample(&self.fit.upper, u);
        let bottom = EyeFit::sample(&self.fit.lower, u);
        let span = (bottom - top).max(1e-3);
        let t = ((v - top) / span).clamp(0.0, 1.0);
        let u_dx = pose.upper_dx.as_ref().unwrap();
        let l_dx = pose.lower_dx.as_ref().unwrap();
        let dx_upper = EyeFit::sample(u_dx, u);
        let dy_upper = EyeFit::sample(&pose.upper, u) - top;
        if mesh == EyeMesh::SKIN {
            let moved = self.field_at(pose, u, v);
            let dy = if v < top {
                moved.1.min(EyeFit::sample(&pose.upper, u) - v)
            } else {
                moved.1
            };
            return self.fit.canvas_delta(moved.0, dy);
        }
        let lower_weight = match mesh {
            EyeMesh::WHITE => t,
            EyeMesh::LOWER_LASH => 1.0,
            EyeMesh::UPPER_LASH => {
                let s = ((t - 0.35) / 0.3).clamp(0.0, 1.0);
                s * s * (3.0 - 2.0 * s)
            }
            EyeMesh::SKIN => unreachable!(),
        };
        let lower_u = if mesh != EyeMesh::WHITE && lower_weight > 0.0 {
            Self::nearest_rim_u(&self.fit.lower, u, v)
        } else {
            u
        };
        let dx_lower = EyeFit::sample(l_dx, lower_u);
        let dy_lower =
            EyeFit::sample(&pose.lower, lower_u) - EyeFit::sample(&self.fit.lower, lower_u);
        self.fit.canvas_delta(
            dx_upper + (dx_lower - dx_upper) * lower_weight,
            dy_upper + (dy_lower - dy_upper) * lower_weight,
        )
    }

    pub fn move_vertex(
        &self,
        mesh: EyeMesh,
        x: f32,
        y: f32,
        pose: &EyePose,
        layer_id: Option<&str>,
    ) -> (f32, f32) {
        let (u, v) = self.fit.to_eye(x, y);
        if let (Some(lid), true, true) = (
            layer_id,
            matches!(mesh, EyeMesh::UPPER_LASH | EyeMesh::LOWER_LASH),
            pose.open.map(|o| o <= 1.0).unwrap_or(true),
        ) {
            if let Some(ref art) = self.fit.artwork {
                let comps = art.components_for_layer(lid);
                let pt = EyePoint { x: u, y: v };
                let component = comps
                    .into_iter()
                    .min_by(|a, b| {
                        let da = sample_min_dist(&a.samples, pt);
                        let db = sample_min_dist(&b.samples, pt);
                        da.total_cmp(&db)
                    });
                if let Some(comp) = component {
                    if comp.primitive == EyePrimitive::SKIN
                        || (comp.primitive == EyePrimitive::AUTO && comp.contacts.is_empty())
                    {
                        return self.move_vertex(EyeMesh::SKIN, x, y, pose, None);
                    }
                    let band = if comp.region == EyeRegion::LOWER {
                        self.lower_band.as_ref()
                    } else {
                        self.upper_band.as_ref()
                    };
                    if let Some(b) = band {
                        if pose.open.is_none() {
                            let is_lower = comp.region == EyeRegion::LOWER;
                            let rest = if is_lower {
                                &self.fit.lower
                            } else {
                                &self.fit.upper
                            };
                            let target = if is_lower { &pose.lower } else { &pose.upper };
                            let dx = if is_lower {
                                pose.lower_dx.as_ref()
                            } else {
                                pose.upper_dx.as_ref()
                            }
                            .map(|c| EyeBand::sample_hermite(c, u))
                            .unwrap_or(0.0);
                            let diff: Vec<f32> =
                                (0..target.len()).map(|k| target[k] - rest[k]).collect();
                            return self
                                .fit
                                .canvas_delta(dx, EyeBand::sample_hermite(&diff, u));
                        }
                        let open_val = pose.open.unwrap();
                        let weight = if comp.primitive == EyePrimitive::TUFT {
                            1.0
                        } else {
                            comp.branches
                                .iter()
                                .filter(|br| {
                                    br.tip.y < br.root.y - b.half_width
                                        && (br.root.y - b.centre(br.root.x)).abs()
                                            <= b.half_width * 2.0
                                })
                                .map(|br| {
                                    let mut min_d = f32::INFINITY;
                                    for w in br.points.windows(2) {
                                        let a = w[0];
                                        let bb = w[1];
                                        let dx = bb.x - a.x;
                                        let dy = bb.y - a.y;
                                        let t = (((u - a.x) * dx + (v - a.y) * dy)
                                            / (dx * dx + dy * dy).max(1e-8))
                                            .clamp(0.0, 1.0);
                                        let proj = EyePoint {
                                            x: a.x + dx * t,
                                            y: a.y + dy * t,
                                        };
                                        min_d = min_d.min(pt.distance(proj));
                                    }
                                    (-min_d * min_d / (b.half_width * b.half_width * 4.0)).exp()
                                })
                                .fold(0.0f32, f32::max)
                        };
                        let mut p = b.move_pt(
                            u,
                            v,
                            open_val,
                            pose.smiling,
                            comp.region == EyeRegion::UPPER,
                            weight,
                        );
                        let distinct_regions: HashSet<EyeRegion> =
                            comp.parts.iter().map(|part| part.region).collect();
                        if distinct_regions.len() > 1 {
                            if let (Some(top), Some(low)) =
                                (self.upper_band.as_ref(), self.lower_band.as_ref())
                            {
                                let w = ((v - top.centre(u))
                                    / (low.centre(u) - top.centre(u)).max(1e-5))
                                .clamp(0.0, 1.0);
                                let smooth = w * w * (3.0 - 2.0 * w);
                                let a = top.move_pt(u, v, open_val, pose.smiling, true, weight);
                                let bb = low.move_pt(u, v, open_val, pose.smiling, false, 1.0);
                                p = EyePoint {
                                    x: a.x + (bb.x - a.x) * smooth,
                                    y: a.y + (bb.y - a.y) * smooth,
                                };
                            }
                        }
                        return self.fit.canvas_delta(p.x - u, p.y - v);
                    }
                }
            }
        }

        if pose.upper_dx.is_some() {
            return self.widened_move(mesh, u, v, pose);
        }
        let rest_upper = EyeFit::sample(&self.fit.upper, u);
        let rest_lower = EyeFit::sample(&self.fit.lower, u);
        let d_upper = EyeFit::sample(&pose.upper, u) - rest_upper;
        let d_lower = EyeFit::sample(&pose.lower, u) - rest_lower;
        let d_lower_closing =
            d_lower + (EyeFit::sample(&pose.upper, u) - (rest_lower + d_lower)) * pose.fold;
        let dv = match mesh {
            EyeMesh::WHITE => {
                let w = u.clamp(Self::TIP, 1.0 - Self::TIP);
                let top = EyeFit::sample(&self.fit.upper, w);
                let base = EyeFit::sample(&self.fit.lower, w);
                let span = (base - top).max(1e-3);
                let t = ((v - top) / span).clamp(-0.5, 1.5);
                let move_top = EyeFit::sample(&pose.upper, w) - top - Self::TUCK * pose.fold;
                let move_base = EyeFit::sample(&pose.lower, w) - base;
                let bottom =
                    move_base + (top + move_top - (base + move_base)) * pose.fold;
                move_top + t * (bottom - move_top)
            }
            EyeMesh::UPPER_LASH => {
                let reach = u.clamp(Self::TAIL, 1.0 - Self::TAIL);
                let upper_move = if reach == u {
                    d_upper + (v - rest_upper) * (pose.lashes - 1.0)
                } else {
                    let closing = (1.0 - pose.lashes) / (1.0 - self.squash).max(1e-3);
                    let turn =
                        Self::WING_DROOP * closing.clamp(0.0, 1.0) * if u > reach { 1.0 } else { -1.0 };
                    let du0 = u - reach;
                    let dv0 = (v - EyeFit::sample(&self.fit.upper, reach)) * pose.lashes;
                    let du1 = du0 * turn.cos() - dv0 * turn.sin();
                    let dv1 = du0 * turn.sin() + dv0 * turn.cos();
                    let anchor = EyeFit::sample(&self.fit.upper, reach);
                    return self.fit.canvas_delta(reach + du1 - u, anchor + dv1 - v);
                };
                let t = ((v - rest_upper) / (rest_lower - rest_upper).max(1e-3)).clamp(0.0, 1.0);
                let s = ((t - 0.35) / 0.3).clamp(0.0, 1.0);
                let toward_lower = s * s * (3.0 - 2.0 * s);
                upper_move + (d_lower_closing - upper_move) * toward_lower
            }
            EyeMesh::LOWER_LASH => d_lower_closing,
            EyeMesh::SKIN => {
                let moved = self.field_at(pose, u, v).1;
                let edge = EyeFit::sample(&pose.upper, u);
                if v < rest_upper && v + moved > edge {
                    edge - v
                } else {
                    moved
                }
            }
        };
        self.fit.canvas_delta(0.0, dv)
    }
}

fn sample_min_dist(samples: &[EyePoint], pt: EyePoint) -> f32 {
    let step = (samples.len() / 256).max(1);
    samples
        .iter()
        .step_by(step)
        .map(|s| s.distance(pt))
        .fold(f32::INFINITY, f32::min)
}

pub struct SourceLayer {
    pub id: String,
    pub tag: String,
    pub side: String,
    pub role: Option<String>,
    pub left: f32,
    pub top: f32,
    pub width: usize,
    pub height: usize,
    pub rgba_key: String,
    pub bounds: Bounds,
    pub centroid_x: f32,
    pub centroid_y: f32,
    pub opaque_pixels: usize,
}

impl SourceLayer {
    pub fn alpha(&self, rgba: &[u8], x: isize, y: isize) -> u8 {
        if x < 0 || y < 0 || x >= self.width as isize || y >= self.height as isize {
            return 0;
        }
        rgba[((y as usize) * self.width + (x as usize)) * 4 + 3]
    }
}

pub struct Alignment {
    pub origin_x: f32,
    pub origin_y: f32,
    pub sin_a: f32,
    pub cos_a: f32,
}

impl Alignment {
    pub fn point(&self, x: f32, y: f32) -> (f32, f32) {
        let dx = x - self.origin_x;
        let dy = y - self.origin_y;
        (
            self.origin_x + dx * self.cos_a + dy * self.sin_a,
            self.origin_y - dx * self.sin_a + dy * self.cos_a,
        )
    }

    pub fn canvas(&self, x: f32, y: f32) -> (f32, f32) {
        let dx = x - self.origin_x;
        let dy = y - self.origin_y;
        (
            self.origin_x + dx * self.cos_a - dy * self.sin_a,
            self.origin_y + dx * self.sin_a + dy * self.cos_a,
        )
    }
}

pub struct EyeTargetMaterial {
    pub layer: String,
    pub parent: Option<String>,
    pub frame: Bounds,
    pub centroid_x: f32,
    pub centroid_y: f32,
    pub preset: bool,
    pub automatic_opacity: bool,
    pub opacity: f32,
}

pub struct PartOverride {
    pub source_stamp: String,
    pub side: Option<Side>,
    pub region: Option<EyeRegion>,
    pub primitive: Option<EyePrimitive>,
    pub path: Option<Vec<EyePoint>>,
    pub contacts: Option<Vec<EyeContact>>,
}

pub struct DecodedEyeInputs {
    pub face_center_x: f32,
    pub alignment: Option<Alignment>,
    pub artwork_stamps: HashMap<String, String>,
    pub moves: HashMap<Side, HashMap<String, (f32, f32)>>,
    pub corrections: HashMap<String, HashMap<String, PartOverride>>,
    pub targets: HashMap<String, EyeTargetMaterial>,
    pub jelly_squash: f32,
    pub jelly_stretch: f32,
    pub layers: Vec<SourceLayer>,
    pub art_targets: HashMap<String, String>,
    pub rasters: HashMap<String, Vec<u8>>,
    pub topology: HashMap<String, MeshGeometry>,
    pub folder_name: Option<String>,
    pub parameter_names: HashMap<String, String>,
}

impl DecodedEyeInputs {
    pub fn layer_for_target(&self, id: &str) -> Result<&SourceLayer, String> {
        let lid = self
            .art_targets
            .get(id)
            .ok_or_else(|| format!("Missing art target {id}"))?;
        self.layers
            .iter()
            .find(|l| l.id == *lid)
            .ok_or_else(|| format!("Missing source layer {lid}"))
    }

    pub fn align(&self, x: f32, y: f32) -> (f32, f32) {
        match &self.alignment {
            Some(a) => a.point(x, y),
            None => (x, y),
        }
    }
}

fn parse_bounds(v: &Json) -> Result<Bounds, String> {
    if v.as_obj().is_none() {
        return Err("Expected bounds object".into());
    }
    Ok(Bounds {
        left: v.get("left").and_then(Json::as_f64).ok_or("left")? as f32,
        top: v.get("top").and_then(Json::as_f64).ok_or("top")? as f32,
        right: v.get("right").and_then(Json::as_f64).ok_or("right")? as f32,
        bottom: v.get("bottom").and_then(Json::as_f64).ok_or("bottom")? as f32,
    })
}

fn parse_point(v: &Json) -> Result<EyePoint, String> {
    if v.as_obj().is_none() {
        return Err("Expected point".into());
    }
    Ok(EyePoint {
        x: v.get("x").and_then(Json::as_f64).ok_or("x")? as f32,
        y: v.get("y").and_then(Json::as_f64).ok_or("y")? as f32,
    })
}

fn parse_contact(v: &Json) -> Result<EyeContact, String> {
    if v.as_obj().is_none() {
        return Err("Expected contact".into());
    }
    Ok(EyeContact {
        region: EyeRegion::from_str(v.get("region").and_then(Json::as_str).ok_or("region")?)?,
        from: v.get("from").and_then(Json::as_f64).ok_or("from")? as f32,
        to: v.get("to").and_then(Json::as_f64).ok_or("to")? as f32,
        centre: parse_point(v.get("centre").ok_or("centre")?)?,
    })
}

pub fn decode_eye_inputs(req: &Request, is_smile_schema: bool) -> Result<DecodedEyeInputs, String> {
    let ctx = req.context_values().ok_or("Missing eye context")?;
    decode_eye_inputs_from(&ctx, req, is_smile_schema)
}

pub fn decode_eye_inputs_from(
    ctx: &Json,
    req: &Request,
    is_smile_schema: bool,
) -> Result<DecodedEyeInputs, String> {
    let topo_key = if is_smile_schema {
        "eye_smile_topology"
    } else {
        "eye_wide_topology"
    };
    let meas_val = ctx.get("measurements").ok_or("Missing measurements")?;
    let art_val = ctx.get("art").ok_or("Missing art")?;

    if meas_val.as_obj().is_none() {
        return Err("Invalid measurements".into());
    }
    let folder_name = meas_val
        .get("folderName")
        .and_then(Json::as_str)
        .map(str::to_string);
    let mut parameter_names = HashMap::new();
    if let Some(pnames) = meas_val.get("parameterNames").and_then(Json::as_obj) {
        for (k, v) in pnames {
            if let Some(s) = v.as_str() {
                parameter_names.insert(k.clone(), s.to_string());
            }
        }
    }
    let face_center_x = meas_val
        .get("faceCenterX")
        .and_then(Json::as_f64)
        .ok_or("faceCenterX")? as f32;
    let alignment = match meas_val.get("alignment") {
        Some(Json::Null) | None => None,
        Some(v) => {
            if v.as_obj().is_none() {
                return Err("Invalid alignment".into());
            }
            let angle = v.get("angle").and_then(Json::as_f64).unwrap_or(0.0) as f32;
            Some(Alignment {
                origin_x: v.get("originX").and_then(Json::as_f64).unwrap_or(0.0) as f32,
                origin_y: v.get("originY").and_then(Json::as_f64).unwrap_or(0.0) as f32,
                sin_a: angle.sin(),
                cos_a: angle.cos(),
            })
        }
    };
    let mut artwork_stamps = HashMap::new();
    if let Some(stamps) = meas_val.get("artworkStamps").and_then(Json::as_obj) {
        for (k, v) in stamps {
            if let Some(s) = v.as_str() {
                artwork_stamps.insert(k.clone(), s.to_string());
            }
        }
    }
    let mut moves = HashMap::new();
    if let Some(mv_obj) = meas_val.get("moves").and_then(Json::as_obj) {
        for (side_k, side_v) in mv_obj {
            let side = Side::from_str(side_k)?;
            let mut inner_map = HashMap::new();
            if let Some(so) = side_v.as_obj() {
                for (handle, pair_v) in so {
                    if pair_v.as_obj().is_some() {
                        let first = pair_v.get("first").and_then(Json::as_f64).unwrap_or(0.0) as f32;
                        let second = pair_v.get("second").and_then(Json::as_f64).unwrap_or(0.0) as f32;
                        inner_map.insert(handle.clone(), (first, second));
                    }
                }
            }
            moves.insert(side, inner_map);
        }
    }
    let mut corrections = HashMap::new();
    if let Some(corr_obj) = meas_val.get("corrections").and_then(Json::as_obj) {
        for (lid, map_v) in corr_obj {
            let mut inner = HashMap::new();
            if let Some(mo) = map_v.as_obj() {
                for (cid, ov) in mo {
                    if ov.as_obj().is_some() {
                        let source_stamp = ov
                            .get("sourceStamp")
                            .and_then(Json::as_str)
                            .unwrap_or("")
                            .to_string();
                        let side = match ov.get("side").and_then(Json::as_str) {
                            Some(s) => Some(Side::from_str(s)?),
                            None => None,
                        };
                        let region = match ov.get("region").and_then(Json::as_str) {
                            Some(s) => Some(EyeRegion::from_str(s)?),
                            None => None,
                        };
                        let primitive = match ov.get("primitive").and_then(Json::as_str) {
                            Some(s) => Some(EyePrimitive::from_str(s)?),
                            None => None,
                        };
                        let path = match ov.get("path").and_then(Json::as_arr) {
                            Some(arr) => Some(
                                arr.iter()
                                    .map(parse_point)
                                    .collect::<Result<Vec<_>, _>>()?,
                            ),
                            None => None,
                        };
                        let contacts = match ov.get("contacts").and_then(Json::as_arr) {
                            Some(arr) => Some(
                                arr.iter()
                                    .map(parse_contact)
                                    .collect::<Result<Vec<_>, _>>()?,
                            ),
                            None => None,
                        };
                        inner.insert(
                            cid.clone(),
                            PartOverride {
                                source_stamp,
                                side,
                                region,
                                primitive,
                                path,
                                contacts,
                            },
                        );
                    }
                }
            }
            corrections.insert(lid.clone(), inner);
        }
    }
    let mut targets = HashMap::new();
    let t_obj = meas_val
        .get("targets")
        .and_then(Json::as_obj)
        .ok_or("Missing targets")?;
    for (tid, tv) in t_obj {
        if tv.as_obj().is_none() {
            return Err("Invalid target".into());
        }
        targets.insert(
            tid.clone(),
            EyeTargetMaterial {
                layer: tv
                    .get("layer")
                    .and_then(Json::as_str)
                    .ok_or("target.layer")?
                    .to_string(),
                parent: tv
                    .get("parent")
                    .and_then(Json::as_str)
                    .map(str::to_string),
                frame: parse_bounds(tv.get("frame").ok_or("target.frame")?)?,
                centroid_x: tv
                    .get("centroidX")
                    .and_then(Json::as_f64)
                    .unwrap_or(0.0) as f32,
                centroid_y: tv
                    .get("centroidY")
                    .and_then(Json::as_f64)
                    .unwrap_or(0.0) as f32,
                preset: tv
                    .get("preset")
                    .and_then(Json::as_bool)
                    .unwrap_or(false),
                automatic_opacity: tv
                    .get("automaticOpacity")
                    .and_then(Json::as_bool)
                    .unwrap_or(false),
                opacity: tv
                    .get("opacity")
                    .and_then(Json::as_f64)
                    .unwrap_or(1.0) as f32,
            },
        );
    }
    let jelly_squash = meas_val
        .get("jellySquash")
        .and_then(Json::as_f64)
        .unwrap_or(0.0) as f32;
    let jelly_stretch = meas_val
        .get("jellyStretch")
        .and_then(Json::as_f64)
        .unwrap_or(0.0) as f32;

    if art_val.as_obj().is_none() {
        return Err("Invalid art".into());
    }
    let mut layers = Vec::new();
    let mut rasters = HashMap::new();
    for lv in art_val
        .get("layers")
        .and_then(Json::as_arr)
        .ok_or("art.layers")?
    {
        if lv.as_obj().is_none() {
            return Err("layer".into());
        }
        let rgba_key = lv
            .get("rgba")
            .and_then(Json::as_str)
            .ok_or("layer.rgba")?
            .to_string();
        if !rasters.contains_key(&rgba_key) {
            let bytes = req
                .context_resource(&rgba_key)
                .ok_or_else(|| format!("Missing raster resource {rgba_key}"))?;
            rasters.insert(rgba_key.clone(), bytes.to_vec());
        }
        layers.push(SourceLayer {
            id: lv.get("id").and_then(Json::as_str).ok_or("id")?.to_string(),
            tag: lv
                .get("tag")
                .and_then(Json::as_str)
                .ok_or("tag")?
                .to_string(),
            side: lv
                .get("side")
                .and_then(Json::as_str)
                .ok_or("side")?
                .to_string(),
            role: lv.get("role").and_then(Json::as_str).map(str::to_string),
            left: lv.get("left").and_then(Json::as_f64).ok_or("left")? as f32,
            top: lv.get("top").and_then(Json::as_f64).ok_or("top")? as f32,
            width: lv.get("width").and_then(Json::as_i64).ok_or("width")? as usize,
            height: lv.get("height").and_then(Json::as_i64).ok_or("height")? as usize,
            rgba_key,
            bounds: parse_bounds(lv.get("bounds").ok_or("bounds")?)?,
            centroid_x: lv
                .get("centroidX")
                .and_then(Json::as_f64)
                .ok_or("centroidX")? as f32,
            centroid_y: lv
                .get("centroidY")
                .and_then(Json::as_f64)
                .ok_or("centroidY")? as f32,
            opaque_pixels: lv
                .get("opaquePixels")
                .and_then(Json::as_i64)
                .ok_or("opaquePixels")? as usize,
        });
    }
    let mut art_targets = HashMap::new();
    if let Some(at) = art_val.get("targets").and_then(Json::as_obj) {
        for (k, v) in at {
            if let Some(s) = v.as_str() {
                art_targets.insert(k.clone(), s.to_string());
            }
        }
    }

    let topo_bytes = req
        .context_resource(topo_key)
        .ok_or_else(|| format!("Missing {topo_key}"))?;
    let topo_rig = decode_rig_binary(&topo_bytes).map_err(|e| format!("Invalid topology: {e}"))?;
    let mut topology = HashMap::new();
    for mesh in topo_rig.meshes {
        if let Some(geom) = mesh.geometry {
            topology.insert(mesh.id, geom);
        }
    }

    Ok(DecodedEyeInputs {
        face_center_x,
        alignment,
        artwork_stamps,
        moves,
        corrections,
        targets,
        jelly_squash,
        jelly_stretch,
        layers,
        art_targets,
        rasters,
        topology,
        folder_name,
        parameter_names,
    })
}

const EYE_ALPHA: u8 = 96;

pub fn eye_fitting_fit(inputs: &DecodedEyeInputs) -> HashMap<Side, EyeFit> {
    let on_side = |side: Side, x: f32| -> bool {
        if side == Side::LEFT {
            x >= inputs.face_center_x
        } else {
            x < inputs.face_center_x
        }
    };
    let pixels_of = |layer: &SourceLayer, side_filter: Option<Side>| -> Vec<(f32, f32)> {
        let rgba = &inputs.rasters[&layer.rgba_key];
        let mut out = Vec::new();
        for y in 0..layer.height {
            for x in 0..layer.width {
                if rgba[(y * layer.width + x) * 4 + 3] < EYE_ALPHA {
                    continue;
                }
                let cx = layer.left + x as f32 + 0.5;
                let keep = match side_filter {
                    Some(s) if layer.side == "NONE" => on_side(s, cx),
                    _ => true,
                };
                if keep {
                    out.push((cx, layer.top + y as f32 + 0.5));
                }
            }
        }
        out
    };

    let mut whites: HashMap<Side, Option<(&SourceLayer, Vec<(f32, f32)>)>> = HashMap::new();
    let mut centres: HashMap<Side, (f32, f32)> = HashMap::new();
    for side in [Side::LEFT, Side::RIGHT] {
        let mut best: Option<(&SourceLayer, Vec<(f32, f32)>)> = None;
        for layer in &inputs.layers {
            if layer.opaque_pixels == 0 || layer.tag != "EYEWHITE" || layer.role.is_some() {
                continue;
            }
            if layer.side != side.name() && layer.side != "NONE" {
                continue;
            }
            let pts = pixels_of(layer, Some(side));
            if !pts.is_empty() && best.as_ref().map(|b| pts.len() > b.1.len()).unwrap_or(true) {
                best = Some((layer, pts));
            }
        }
        if let Some((_, ref pts)) = best {
            let sx: f64 = pts.iter().map(|p| p.0 as f64).sum();
            let sy: f64 = pts.iter().map(|p| p.1 as f64).sum();
            centres.insert(side, ((sx / pts.len() as f64) as f32, (sy / pts.len() as f64) as f32));
        }
        whites.insert(side, best);
    }

    let normalized = |v: (f32, f32)| -> Option<(f32, f32)> {
        let len = v.0.hypot(v.1);
        if len < 1e-3 {
            None
        } else {
            Some((v.0 / len, v.1 / len))
        }
    };
    let level_pair = centres.get(&Side::LEFT).and_then(|&l| {
        centres.get(&Side::RIGHT).map(|&r| {
            let canvas = (l.0 - r.0, l.1 - r.1);
            let rl = inputs.align(l.0, l.1);
            let rr = inputs.align(r.0, r.1);
            (canvas, (rl.0 - rr.0, rl.1 - rr.1))
        })
    });
    let canvas_level = level_pair
        .and_then(|(c, _)| normalized(c))
        .filter(|v| v.1.abs() < 0.7)
        .unwrap_or((1.0, 0.0));
    let rig_level = level_pair
        .and_then(|(_, r)| normalized(r))
        .filter(|v| v.1.abs() < 0.7)
        .unwrap_or_else(|| {
            let a = inputs.align(0.0, 0.0);
            let b = inputs.align(100.0, 0.0);
            normalized((b.0 - a.0, b.1 - a.1)).unwrap_or((1.0, 0.0))
        });

    let mut fits = HashMap::new();
    for side in [Side::LEFT, Side::RIGHT] {
        let Some((white, pts)) = whites.get(&side).and_then(|w| w.as_ref()) else {
            continue;
        };
        if pts.len() < 12 {
            continue;
        }
        let candidate = |tag: &str| -> Option<&SourceLayer> {
            inputs
                .layers
                .iter()
                .filter(|it| {
                    it.opaque_pixels > 0
                        && it.tag == tag
                        && it.role.is_none()
                        && (it.side == side.name() || (it.side == "NONE" && on_side(side, it.centroid_x)))
                })
                .max_by_key(|it| it.opaque_pixels)
        };
        if let Some(fit) = read_eye_fit(
            inputs,
            side,
            white,
            pts,
            canvas_level,
            rig_level,
            candidate("EYE_CLOSE"),
            candidate("IRIDES"),
        ) {
            if check_eye_fit(&fit) {
                let mut f = fit;
                f.opening_layers.insert(white.id.clone());
                fits.insert(side, f);
            }
        }
    }
    fits
}

fn read_eye_fit(
    inputs: &DecodedEyeInputs,
    side: Side,
    white: &SourceLayer,
    points: &[(f32, f32)],
    level: (f32, f32),
    rig_level: (f32, f32),
    closed_layer: Option<&SourceLayer>,
    iris_layer: Option<&SourceLayer>,
) -> Option<EyeFit> {
    let (lx, ly) = level;
    let dx = -ly;
    let dy = lx;
    let along = |p: (f32, f32)| p.0 * lx + p.1 * ly;
    let mut lo = f32::INFINITY;
    let mut hi = f32::NEG_INFINITY;
    for &p in points {
        let a = along(p);
        lo = lo.min(a);
        hi = hi.max(a);
    }
    if hi - lo < 4.0 {
        return None;
    }
    let band = (hi - lo) * 0.03;
    let avg = |pts: &[(f32, f32)]| -> (f32, f32) {
        let sx: f64 = pts.iter().map(|p| p.0 as f64).sum();
        let sy: f64 = pts.iter().map(|p| p.1 as f64).sum();
        ((sx / pts.len() as f64) as f32, (sy / pts.len() as f64) as f32)
    };
    let corner = |at: f32| -> (f32, f32) {
        let sub: Vec<(f32, f32)> = points
            .iter()
            .copied()
            .filter(|&p| (along(p) - at).abs() <= band)
            .collect();
        avg(&sub)
    };
    let low = corner(lo);
    let high = corner(hi);
    let low_inner = (low.0 - inputs.face_center_x).abs() < (high.0 - inputs.face_center_x).abs();
    let inner = if low_inner { low } else { high };
    let outer = if low_inner { high } else { low };
    let span = (along(outer) - along(inner)).abs();
    let r_inner = inputs.align(inner.0, inner.1);
    let r_outer = inputs.align(outer.0, outer.1);
    let sign = if (r_outer.0 - r_inner.0) * rig_level.0 + (r_outer.1 - r_inner.1) * rig_level.1 >= 0.0 {
        1.0
    } else {
        -1.0
    };
    let axis = (rig_level.0 * sign, rig_level.1 * sign);
    let width = ((r_outer.0 - r_inner.0) * axis.0 + (r_outer.1 - r_inner.1) * axis.1).max(1e-3);
    let frame = EyeFit {
        side,
        origin_x: r_inner.0,
        origin_y: r_inner.1,
        axis_x: axis.0,
        axis_y: axis.1,
        width,
        upper: vec![0.0],
        lower: vec![0.0],
        closed: None,
        iris: None,
        artwork: None,
        opening_layers: HashSet::new(),
        upper_correction: None,
    };
    let to_eye = |x: f32, y: f32| {
        let rx = inputs.align(x, y);
        frame.to_eye(rx.0, rx.1)
    };
    let corner_v = to_eye(outer.0, outer.1).1;
    let white_rgba = &inputs.rasters[&white.rgba_key];
    let n_samples = EyeFit::SAMPLES * 3;
    let mut samples = Vec::new();
    for i in 0..n_samples {
        let u = i as f32 / (n_samples - 1) as f32;
        let sign2 = if low_inner { 1.0 } else { -1.0 };
        let bx = inner.0 + sign2 * lx * u * span;
        let by = inner.1 + sign2 * ly * u * span;
        let reach = span * 1.2;
        let mut first = f32::NAN;
        let mut last = f32::NAN;
        let mut t = -reach;
        while t <= reach {
            let px = (bx + dx * t - white.left) as isize;
            let py = (by + dy * t - white.top) as isize;
            if white.alpha(white_rgba, px, py) >= EYE_ALPHA {
                if first.is_nan() {
                    first = t;
                }
                last = t;
            }
            t += 0.5;
        }
        if !first.is_nan() {
            samples.push((u, to_eye(bx + dx * first, by + dy * first), to_eye(bx + dx * last, by + dy * last)));
        }
    }
    if samples.len() < 6 {
        return None;
    }
    let interp = |pts: &[(f32, f32)], u: f32| -> f32 {
        match pts.iter().position(|p| p.0 >= u) {
            None | Some(0) => pts[0].1,
            Some(i) => {
                let a = pts[i - 1];
                let b = pts[i];
                let t = if b.0 - a.0 < 1e-6 { 0.0 } else { (u - a.0) / (b.0 - a.0) };
                a.1 + (b.1 - a.1) * t
            }
        }
    };
    let make_curve = |pick: fn(&(f32, (f32, f32), (f32, f32))) -> (f32, f32)| -> Vec<f32> {
        let mut raw: Vec<(f32, f32)> = samples.iter().map(pick).collect();
        raw.sort_by(|a, b| a.0.total_cmp(&b.0));
        let reach = (raw.len() / 12).max(1);
        let smooth: Vec<(f32, f32)> = (0..raw.len())
            .map(|i| {
                let a = i.saturating_sub(reach);
                let b = (i + reach + 1).min(raw.len());
                let avg_y = raw[a..b].iter().map(|p| p.1 as f64).sum::<f64>() / (b - a) as f64;
                (raw[i].0, avg_y as f32)
            })
            .collect();
        (0..EyeFit::SAMPLES)
            .map(|k| {
                if k == 0 {
                    0.0
                } else if k == EyeFit::SAMPLES - 1 {
                    corner_v
                } else {
                    interp(&smooth, EyeFit::u(k))
                }
            })
            .collect()
    };
    let upper = make_curve(|s| s.1);
    let lower = make_curve(|s| s.2);
    let closed = closed_layer.and_then(|layer| {
        let rgba = &inputs.rasters[&layer.rgba_key];
        let mut buckets: HashMap<i32, [f64; 3]> = HashMap::new();
        for y in 0..layer.height {
            for x in 0..layer.width {
                let a = rgba[(y * layer.width + x) * 4 + 3];
                if a < EYE_ALPHA {
                    continue;
                }
                let cx = layer.left + x as f32 + 0.5;
                let cy = layer.top + y as f32 + 0.5;
                let key = (cx * lx + cy * ly).round() as i32;
                let b = buckets.entry(key).or_insert([0.0; 3]);
                b[0] += a as f64;
                b[1] += cx as f64 * a as f64;
                b[2] += cy as f64 * a as f64;
            }
        }
        let mut line: Vec<(f32, f32)> = buckets
            .values()
            .map(|b| to_eye((b[1] / b[0]) as f32, (b[2] / b[0]) as f32))
            .collect();
        line.sort_by(|a, b| a.0.total_cmp(&b.0));
        if line.len() < 4 || line.last().unwrap().0 - line[0].0 < 0.6 {
            None
        } else {
            Some(
                (0..EyeFit::SAMPLES)
                    .map(|k| {
                        let u = EyeFit::u(k);
                        if u <= line[0].0 {
                            line[0].1 * (u / line[0].0.max(1e-3)).clamp(0.0, 1.0)
                        } else if u >= line.last().unwrap().0 {
                            let lx_last = line.last().unwrap().0;
                            let ly_last = line.last().unwrap().1;
                            corner_v + (ly_last - corner_v) * ((1.0 - u) / (1.0 - lx_last).max(1e-3)).clamp(0.0, 1.0)
                        } else {
                            interp(&line, u)
                        }
                    })
                    .collect(),
            )
        }
    });
    Some(EyeFit {
        upper,
        lower,
        closed,
        iris: iris_layer.map(|l| l.bounds),
        ..frame
    })
}

fn check_eye_fit(fit: &EyeFit) -> bool {
    if fit.width < 6.0 {
        return false;
    }
    let mut opening = f32::NEG_INFINITY;
    for k in 2..EyeFit::SAMPLES - 2 {
        opening = opening.max(fit.lower[k] - fit.upper[k]);
        if fit.upper[k] >= fit.lower[k] {
            return false;
        }
    }
    if !(0.12..=1.3).contains(&opening) {
        return false;
    }
    if fit.corner_v().abs() > 0.6 {
        return false;
    }
    true
}

struct Piece<'a> {
    layer: &'a SourceLayer,
    key: String,
    rw: usize,
    rh: usize,
    step: usize,
    pixels: Vec<usize>,
    samples: Vec<EyePoint>,
    side: Side,
    hint: Option<&'a PartOverride>,
}

pub fn eye_artwork_fitting_fit(
    inputs: &DecodedEyeInputs,
    fits: &HashMap<Side, EyeFit>,
) -> HashMap<Side, EyeArtwork> {
    if fits.is_empty() {
        return HashMap::new();
    }
    let mut pieces: Vec<Piece> = Vec::new();
    for layer in &inputs.layers {
        if layer.tag != "EYELASH" || layer.opaque_pixels == 0 {
            continue;
        }
        let lside = Side::from_str(&layer.side).unwrap_or(Side::NONE);
        if lside != Side::NONE && !fits.contains_key(&lside) {
            continue;
        }
        let rgba = &inputs.rasters[&layer.rgba_key];
        let step = ((layer.width.max(layer.height) as f32) / 512.0).ceil().max(1.0) as usize;
        let rw = (layer.width + step - 1) / step;
        let rh = (layer.height + step - 1) / step;
        let mut mask = vec![false; rw * rh];
        for y in 0..rh {
            for x in 0..rw {
                let mut opaque = false;
                'cell: for sy in y * step..layer.height.min((y + 1) * step) {
                    for sx in x * step..layer.width.min((x + 1) * step) {
                        if rgba[(sy * layer.width + sx) * 4 + 3] >= EYE_ALPHA {
                            opaque = true;
                            break 'cell;
                        }
                    }
                }
                mask[y * rw + x] = opaque;
            }
        }
        let islands = if step == 1 {
            let mut visited = vec![false; rw * rh];
            let mut res = Vec::new();
            let mut queue = Vec::new();
            for start in 0..rw * rh {
                if !mask[start] || visited[start] {
                    continue;
                }
                queue.clear();
                queue.push(start);
                visited[start] = true;
                let mut head = 0;
                while head < queue.len() {
                    let p = queue[head];
                    head += 1;
                    let x = (p % rw) as isize;
                    let y = (p / rw) as isize;
                    for dy in -1..=1 {
                        for dx in -1..=1 {
                            let xx = x + dx;
                            let yy = y + dy;
                            if xx < 0 || yy < 0 || xx >= rw as isize || yy >= rh as isize {
                                continue;
                            }
                            let q = yy as usize * rw + xx as usize;
                            if mask[q] && !visited[q] {
                                visited[q] = true;
                                queue.push(q);
                            }
                        }
                    }
                }
                res.push(queue.clone());
            }
            res
        } else {
            let total = layer.width * layer.height;
            let mut visited = vec![false; total];
            let mut res = Vec::new();
            let mut queue = Vec::new();
            for start in 0..total {
                if visited[start] || rgba[start * 4 + 3] < EYE_ALPHA {
                    continue;
                }
                queue.clear();
                queue.push(start);
                visited[start] = true;
                let mut reduced = HashSet::new();
                let mut head = 0;
                while head < queue.len() {
                    let p = queue[head];
                    head += 1;
                    let x = p % layer.width;
                    let y = p / layer.width;
                    reduced.insert((y / step) * rw + x / step);
                    for dy in -1isize..=1 {
                        for dx in -1isize..=1 {
                            let xx = x as isize + dx;
                            let yy = y as isize + dy;
                            if xx < 0 || yy < 0 || xx >= layer.width as isize || yy >= layer.height as isize {
                                continue;
                            }
                            let q = yy as usize * layer.width + xx as usize;
                            if !visited[q] && rgba[q * 4 + 3] >= EYE_ALPHA {
                                visited[q] = true;
                                queue.push(q);
                            }
                        }
                    }
                }
                if queue.len() >= 2 {
                    let mut sorted: Vec<usize> = reduced.into_iter().collect();
                    sorted.sort_unstable();
                    res.push(sorted);
                }
            }
            res
        };

        let digest = inputs
            .artwork_stamps
            .get(&layer.id)
            .map(String::as_str)
            .unwrap_or("");
        for (index, isl) in islands.into_iter().enumerate() {
            if isl.is_empty() {
                continue;
            }
            let canvas: Vec<(f32, f32)> = isl
                .iter()
                .map(|&p| {
                    inputs.align(
                        layer.left + ((p % rw) as f32 + 0.5) * step as f32,
                        layer.top + ((p / rw) as f32 + 0.5) * step as f32,
                    )
                })
                .collect();
            let mut by_side: HashMap<Side, Vec<usize>> = HashMap::new();
            for i in 0..canvas.len() {
                let assigned = if lside != Side::NONE {
                    lside
                } else {
                    *fits
                        .iter()
                        .min_by(|(_, a), (_, b)| {
                            let da = {
                                let (u, v) = a.to_eye(canvas[i].0, canvas[i].1);
                                let mid_v = (EyeFit::sample(&a.upper, u) + EyeFit::sample(&a.lower, u)) * 0.5;
                                (u - u.clamp(0.0, 1.0)).hypot(v - mid_v) * a.width
                            };
                            let db = {
                                let (u, v) = b.to_eye(canvas[i].0, canvas[i].1);
                                let mid_v = (EyeFit::sample(&b.upper, u) + EyeFit::sample(&b.lower, u)) * 0.5;
                                (u - u.clamp(0.0, 1.0)).hypot(v - mid_v) * b.width
                            };
                            da.total_cmp(&db)
                        })
                        .unwrap()
                        .0
                };
                by_side.entry(assigned).or_default().push(i);
            }
            for (assigned, indices) in by_side {
                let key = format!("c{index}-{}-{digest}", assigned.name());
                let correction = inputs
                    .corrections
                    .get(&layer.id)
                    .and_then(|m| m.get(&key))
                    .filter(|c| c.source_stamp == digest);
                let side = correction.and_then(|c| c.side).unwrap_or(assigned);
                let Some(eye) = fits.get(&side) else {
                    continue;
                };
                let sub_pixels = indices.iter().map(|&i| isl[i]).collect();
                let samples = indices
                    .iter()
                    .map(|&i| {
                        let p = eye.to_eye(canvas[i].0, canvas[i].1);
                        EyePoint { x: p.0, y: p.1 }
                    })
                    .collect();
                pieces.push(Piece {
                    layer,
                    key,
                    rw,
                    rh,
                    step,
                    pixels: sub_pixels,
                    samples,
                    side,
                    hint: correction,
                });
            }
        }
    }

    let mut result = HashMap::new();
    for (&side, eye) in fits {
        let members: Vec<&Piece> = pieces.iter().filter(|p| p.side == side).collect();
        let ink: Vec<&Piece> = members
            .iter()
            .copied()
            .filter(|p| {
                !matches!(p.layer.role.as_deref(), Some("LID") | Some("SHADOW"))
                    && p.hint.and_then(|h| h.primitive) != Some(EyePrimitive::SKIN)
            })
            .collect();
        let corrected_stroke = |region: EyeRegion| -> EyeStroke {
            let observed = fit_stroke(eye, region, &ink);
            let corr_path = members.iter().rev().find_map(|p| {
                let stamp = p.key.rsplit('-').next().unwrap_or("");
                inputs
                    .corrections
                    .get(&p.layer.id)
                    .and_then(|m| m.get(&p.key))
                    .filter(|c| c.source_stamp == stamp && c.region == Some(region))
                    .and_then(|c| c.path.as_ref())
            });
            if let Some(path) = corr_path {
                EyeStroke {
                    region,
                    half_widths: path.iter().map(|pt| observed.half_width(pt.x)).collect(),
                    observed: vec![true; path.len()],
                    points: path.clone(),
                }
            } else {
                observed
            }
        };
        let upper = corrected_stroke(EyeRegion::UPPER);
        let lower = corrected_stroke(EyeRegion::LOWER);
        let components: Vec<EyeComponent> = members
            .iter()
            .map(|&p| {
                let mut c = build_component(inputs, eye, p, &upper, &lower, true);
                if let Some(corr) = inputs
                    .corrections
                    .get(&c.layer_id)
                    .and_then(|m| m.get(&c.id))
                    .filter(|o| o.source_stamp == c.source_stamp)
                {
                    if let Some(r) = corr.region {
                        c.region = r;
                    }
                    if let Some(pr) = corr.primitive {
                        c.primitive = pr;
                    }
                    if let Some(ref cts) = corr.contacts {
                        c.contacts = cts.clone();
                        if let Some(first) = cts.first() {
                            c.contact = first.clone();
                        }
                    }
                    for part in &mut c.parts {
                        if let Some(r) = corr.region {
                            part.region = r;
                        }
                        if let Some(pr) = corr.primitive {
                            part.primitive = pr;
                        }
                        if let Some(ref cts) = corr.contacts {
                            part.contacts = cts.clone();
                        }
                    }
                }
                c
            })
            .collect();
        result.insert(
            side,
            EyeArtwork {
                side,
                upper,
                lower,
                components,
                opening_layers: eye.opening_layers.clone(),
            },
        );
    }
    result
}

fn fit_stroke(eye: &EyeFit, region: EyeRegion, pieces: &[&Piece]) -> EyeStroke {
    let rim = if region == EyeRegion::UPPER {
        &eye.upper
    } else {
        &eye.lower
    };
    let mut points = Vec::new();
    for &p in pieces {
        let mut min_x = f32::INFINITY;
        let mut max_x = f32::NEG_INFINITY;
        let mut min_y = f32::INFINITY;
        let mut max_y = f32::NEG_INFINITY;
        for pt in &p.samples {
            min_x = min_x.min(pt.x);
            max_x = max_x.max(pt.x);
            min_y = min_y.min(pt.y);
            max_y = max_y.max(pt.y);
        }
        let w = max_x - min_x;
        let h = max_y - min_y;
        let ok_role = region != EyeRegion::UPPER || p.layer.role.as_deref() != Some("LOWER");
        let ok_hint_r = p.hint.and_then(|h| h.region).map(|r| r == region).unwrap_or(true);
        let ok_prim =
            p.hint.and_then(|h| h.primitive) == Some(EyePrimitive::LINE) || w > 0.4 || w > h * 2.0;
        if ok_role && ok_hint_r && ok_prim {
            for &pt in &p.samples {
                let top = (pt.y - EyeFit::sample(&eye.upper, pt.x)).abs();
                let bottom = (pt.y - EyeFit::sample(&eye.lower, pt.x)).abs();
                if (region == EyeRegion::UPPER && top <= bottom)
                    || (region == EyeRegion::LOWER && bottom <= top)
                {
                    points.push(pt);
                }
            }
        }
    }
    let aperture = (eye.lower[EyeFit::SAMPLES / 2] - eye.upper[EyeFit::SAMPLES / 2]).max(0.01);
    let mut lo = 0.0f32;
    let mut hi = 1.0f32;
    for pt in &points {
        lo = lo.min(pt.x);
        hi = hi.max(pt.x);
    }
    lo = lo.max(-0.5);
    hi = hi.min(1.5);
    let stations = 65usize;
    let step = (hi - lo) / (stations - 1) as f32;
    let mut raw: Vec<Option<(EyePoint, f32)>> = Vec::with_capacity(stations);
    for k in 0..stations {
        let u = lo + k as f32 * step;
        let edge = EyeFit::sample(rim, u);
        let mut ys: Vec<f32> = points
            .iter()
            .filter(|pt| (pt.x - u).abs() <= step * 0.6 && (pt.y - edge).abs() <= aperture * 0.55)
            .map(|pt| pt.y)
            .collect();
        if ys.is_empty() {
            raw.push(None);
        } else {
            ys.sort_by(|a, b| a.total_cmp(b));
            let gap = (1.5 / eye.width).max(step * 0.6);
            let mut runs: Vec<(f32, f32)> = Vec::new();
            let mut start = ys[0];
            let mut last = ys[0];
            for &v in &ys[1..] {
                if v - last > gap {
                    runs.push((start, last));
                    start = v;
                }
                last = v;
            }
            runs.push((start, last));
            let best = runs
                .into_iter()
                .min_by(|a, b| {
                    let da = (a.0 - edge).abs().min((a.1 - edge).abs());
                    let db = (b.0 - edge).abs().min((b.1 - edge).abs());
                    da.total_cmp(&db)
                })
                .unwrap();
            raw.push(Some((
                EyePoint {
                    x: u,
                    y: (best.0 + best.1) * 0.5,
                },
                (0.5 / eye.width).max((best.1 - best.0) * 0.5),
            )));
        }
    }
    let known: Vec<usize> = (0..stations).filter(|&i| raw[i].is_some()).collect();
    let mut out_pts = Vec::with_capacity(stations);
    let mut out_hw = Vec::with_capacity(stations);
    let mut out_obs = Vec::with_capacity(stations);
    for k in 0..stations {
        let x = lo + k as f32 * step;
        out_obs.push(raw[k].is_some());
        if known.is_empty() {
            out_pts.push(EyePoint {
                x,
                y: EyeFit::sample(rim, x),
            });
            out_hw.push(0.0);
            continue;
        }
        let a = known.iter().copied().rfind(|&i| i <= k).unwrap_or(known[0]);
        let b = known
            .iter()
            .copied()
            .find(|&i| i >= k)
            .unwrap_or(*known.last().unwrap());
        if (b.abs_diff(a) > 4 && k != a && k != b) || k.abs_diff(a) > 4 || k.abs_diff(b) > 4 {
            out_pts.push(EyePoint {
                x,
                y: EyeFit::sample(rim, x),
            });
            out_hw.push(0.0);
        } else {
            let t = if a == b {
                0.0
            } else {
                (k as f32 - a as f32) / (b as f32 - a as f32)
            };
            let pa = raw[a].unwrap();
            let pb = raw[b].unwrap();
            out_pts.push(EyePoint {
                x,
                y: pa.0.y + (pb.0.y - pa.0.y) * t,
            });
            out_hw.push(pa.1 + (pb.1 - pa.1) * t);
        }
    }
    EyeStroke {
        region,
        points: out_pts,
        half_widths: out_hw,
        observed: out_obs,
    }
}

fn build_component(
    inputs: &DecodedEyeInputs,
    eye: &EyeFit,
    piece: &Piece,
    upper: &EyeStroke,
    lower: &EyeStroke,
    split: bool,
) -> EyeComponent {
    let p = &piece.samples;
    let cx = p.iter().map(|q| q.x as f64).sum::<f64>() / p.len() as f64;
    let cy = p.iter().map(|q| q.y as f64).sum::<f64>() / p.len() as f64;
    let centre = EyePoint {
        x: cx as f32,
        y: cy as f32,
    };
    let mut top_d: Vec<f32> = p.iter().map(|q| (q.y - upper.at(q.x).y).abs()).collect();
    let mut low_d: Vec<f32> = p.iter().map(|q| (q.y - lower.at(q.x).y).abs()).collect();
    top_d.sort_by(|a, b| a.total_cmp(b));
    low_d.sort_by(|a, b| a.total_cmp(b));
    let region = if piece.layer.role.as_deref() == Some("LOWER") {
        EyeRegion::LOWER
    } else if low_d[low_d.len() / 4] < top_d[top_d.len() / 4] {
        EyeRegion::LOWER
    } else {
        EyeRegion::UPPER
    };
    let stroke = if region == EyeRegion::UPPER {
        upper
    } else {
        lower
    };
    let mut contact_points: Vec<EyePoint> = p
        .iter()
        .copied()
        .filter(|q| {
            stroke.is_observed(q.x)
                && (q.y - stroke.at(q.x).y).abs() <= stroke.half_width(q.x).max(1.5 / eye.width)
        })
        .collect();
    let contact_set = if contact_points.is_empty() {
        let mut sorted = p.clone();
        sorted.sort_by(|a, b| {
            (a.y - stroke.at(a.x).y)
                .abs()
                .total_cmp(&(b.y - stroke.at(b.x).y).abs())
        });
        sorted.truncate((p.len() / 10).max(1));
        sorted
    } else {
        contact_points.clone()
    };
    let make_contact = |pts: &[EyePoint]| -> EyeContact {
        let mut f = f32::INFINITY;
        let mut t = f32::NEG_INFINITY;
        let mut sx = 0.0f64;
        let mut sy = 0.0f64;
        for pt in pts {
            f = f.min(pt.x);
            t = t.max(pt.x);
            sx += pt.x as f64;
            sy += pt.y as f64;
        }
        EyeContact {
            region,
            from: f,
            to: t,
            centre: EyePoint {
                x: (sx / pts.len() as f64) as f32,
                y: (sy / pts.len() as f64) as f32,
            },
        }
    };
    contact_points.sort_by(|a, b| a.x.total_cmp(&b.x));
    let contact_gap = (3.0 / eye.width).max((stroke.points[1].x - stroke.points[0].x) * 2.0);
    let mut groups: Vec<Vec<EyePoint>> = Vec::new();
    let mut group: Vec<EyePoint> = Vec::new();
    for &q in &contact_points {
        if !group.is_empty() && q.x - group.last().unwrap().x > contact_gap {
            groups.push(group);
            group = Vec::new();
        }
        group.push(q);
    }
    if !group.is_empty() {
        groups.push(group);
    }
    let contacts: Vec<EyeContact> = groups.iter().map(|g| make_contact(g)).collect();
    let contact = groups
        .iter()
        .max_by_key(|g| g.len())
        .map(|g| make_contact(g))
        .unwrap_or_else(|| make_contact(&contact_set));
    let mut min_x = f32::INFINITY;
    let mut max_x = f32::NEG_INFINITY;
    let mut min_y = f32::INFINITY;
    let mut max_y = f32::NEG_INFINITY;
    for pt in p {
        min_x = min_x.min(pt.x);
        max_x = max_x.max(pt.x);
        min_y = min_y.min(pt.y);
        max_y = max_y.max(pt.y);
    }
    let span = max_x - min_x;
    let primitive = if matches!(piece.layer.role.as_deref(), Some("LID") | Some("SHADOW")) {
        EyePrimitive::SKIN
    } else if contact_points.is_empty() {
        EyePrimitive::AUTO
    } else if span > 0.45 && contact.to - contact.from > span * 0.65 {
        EyePrimitive::LINE
    } else if centre.x > 0.78 && max_y - min_y > span {
        EyePrimitive::SIDE
    } else {
        EyePrimitive::TUFT
    };
    let branches = if primitive == EyePrimitive::SKIN {
        Vec::new()
    } else {
        trace_branches(inputs, piece, eye, stroke)
    };
    let confidence = if contact_points.is_empty() || !stroke.observed.iter().any(|&o| o) {
        0.3
    } else {
        0.9
    };
    let stamp = piece.key.rsplit('-').next().unwrap_or("").to_string();
    let obs = EyeComponent {
        id: piece.key.clone(),
        layer_id: piece.layer.id.clone(),
        side: piece.side,
        region,
        primitive,
        samples: p.clone(),
        centre,
        contact,
        branches,
        confidence,
        source_stamp: stamp,
        contacts,
        parts: Vec::new(),
    };
    let as_part = |c: &EyeComponent| EyeMaterialPart {
        id: c.id.clone(),
        region: c.region,
        primitive: c.primitive,
        samples: c.samples.clone(),
        centre: c.centre,
        contacts: c.contacts.clone(),
        branches: c.branches.clone(),
    };
    if !split || primitive == EyePrimitive::SKIN || piece.layer.role.as_deref() == Some("LOWER") {
        let mut out = obs;
        out.parts = vec![as_part(&out)];
        return out;
    }
    let mut upper_idx = Vec::new();
    let mut lower_idx = Vec::new();
    for (i, q) in p.iter().enumerate() {
        if (q.y - upper.at(q.x).y).abs() <= (q.y - lower.at(q.x).y).abs() {
            upper_idx.push(i);
        } else {
            lower_idx.push(i);
        }
    }
    let lower_stations = lower
        .points
        .iter()
        .enumerate()
        .filter(|&(i, pt)| lower.observed[i] && (0.15..=0.85).contains(&pt.x))
        .count();
    let lower_span = if lower_idx.is_empty() {
        0.0
    } else {
        let mut mn = f32::INFINITY;
        let mut mx = f32::NEG_INFINITY;
        for &i in &lower_idx {
            mn = mn.min(p[i].x);
            mx = mx.max(p[i].x);
        }
        mx - mn
    };
    let min_grp = (p.len() / 20).max(3);
    let has_both = !upper_idx.is_empty()
        && !lower_idx.is_empty()
        && lower_stations >= 6
        && lower_span > 0.25
        && upper_idx.len() >= min_grp
        && lower_idx.len() >= min_grp;
    if !has_both {
        let mut out = obs;
        out.parts = vec![as_part(&out)];
        return out;
    }
    let mut parts = Vec::with_capacity(2);
    for (rim, idxs) in [(EyeRegion::UPPER, upper_idx), (EyeRegion::LOWER, lower_idx)] {
        let sub = Piece {
            layer: piece.layer,
            key: format!("{}:{}", piece.key, rim.lower_name()),
            rw: piece.rw,
            rh: piece.rh,
            step: piece.step,
            pixels: idxs.iter().map(|&i| piece.pixels[i]).collect(),
            samples: idxs.iter().map(|&i| p[i]).collect(),
            side: piece.side,
            hint: piece.hint,
        };
        let sub_comp = build_component(inputs, eye, &sub, upper, lower, false);
        let mut pt = as_part(&sub_comp);
        pt.region = rim;
        parts.push(pt);
    }
    let mut out = obs;
    out.parts = parts;
    out
}

fn trace_branches(
    inputs: &DecodedEyeInputs,
    piece: &Piece,
    eye: &EyeFit,
    stroke: &EyeStroke,
) -> Vec<EyeBranch> {
    let mut skeleton = vec![false; piece.rw * piece.rh];
    for &p in &piece.pixels {
        skeleton[p] = true;
    }
    let on = |skel: &[bool], x: isize, y: isize| -> bool {
        x >= 0
            && y >= 0
            && x < piece.rw as isize
            && y < piece.rh as isize
            && skel[y as usize * piece.rw + x as usize]
    };
    let mut removed = Vec::with_capacity(piece.pixels.len());
    let mut changed = true;
    while changed {
        changed = false;
        for phase in 0..2 {
            removed.clear();
            for &p in &piece.pixels {
                if !skeleton[p] {
                    continue;
                }
                let x = (p % piece.rw) as isize;
                let y = (p / piece.rw) as isize;
                let n = [
                    on(&skeleton, x, y - 1),
                    on(&skeleton, x + 1, y - 1),
                    on(&skeleton, x + 1, y),
                    on(&skeleton, x + 1, y + 1),
                    on(&skeleton, x, y + 1),
                    on(&skeleton, x - 1, y + 1),
                    on(&skeleton, x - 1, y),
                    on(&skeleton, x - 1, y - 1),
                ];
                let neighbours = n.iter().filter(|&&b| b).count();
                let transitions = (0..8).filter(|&i| !n[i] && n[(i + 1) % 8]).count();
                let keep = if phase == 0 {
                    (n[0] && n[2] && n[4]) || (n[2] && n[4] && n[6])
                } else {
                    (n[0] && n[2] && n[6]) || (n[0] && n[4] && n[6])
                };
                if (2..=6).contains(&neighbours) && transitions == 1 && !keep {
                    removed.push(p);
                }
            }
            if !removed.is_empty() {
                for &p in &removed {
                    skeleton[p] = false;
                }
                changed = true;
            }
        }
    }
    let neighbours = |skel: &[bool], p: usize| -> Vec<usize> {
        let x = (p % piece.rw) as isize;
        let y = (p / piece.rw) as isize;
        let mut out = Vec::new();
        for dy in -1..=1 {
            for dx in -1..=1 {
                if (dx != 0 || dy != 0) && on(skel, x + dx, y + dy) {
                    out.push((y + dy) as usize * piece.rw + (x + dx) as usize);
                }
            }
        }
        out
    };
    let to_pt = |p: usize| -> EyePoint {
        let rx = inputs.align(
            piece.layer.left + ((p % piece.rw) as f32 + 0.5) * piece.step as f32,
            piece.layer.top + ((p / piece.rw) as f32 + 0.5) * piece.step as f32,
        );
        let q = eye.to_eye(rx.0, rx.1);
        EyePoint { x: q.0, y: q.1 }
    };
    let endpoints: Vec<usize> = piece
        .pixels
        .iter()
        .copied()
        .filter(|&p| skeleton[p] && neighbours(&skeleton, p).len() == 1)
        .collect();
    let Some(root) = piece
        .pixels
        .iter()
        .copied()
        .filter(|&p| skeleton[p])
        .min_by(|&a, &b| {
            let qa = to_pt(a);
            let qb = to_pt(b);
            (qa.y - stroke.at(qa.x).y)
                .abs()
                .total_cmp(&(qb.y - stroke.at(qb.x).y).abs())
        })
    else {
        return Vec::new();
    };
    let mut parents = vec![-1isize; skeleton.len()];
    let mut queue = Vec::with_capacity(piece.pixels.len());
    queue.push(root);
    parents[root] = root as isize;
    let mut head = 0;
    while head < queue.len() {
        let p = queue[head];
        head += 1;
        for q in neighbours(&skeleton, p) {
            if parents[q] < 0 {
                parents[q] = p as isize;
                queue.push(q);
            }
        }
    }
    let mut result = Vec::new();
    for tip in endpoints {
        if tip == root || parents[tip] < 0 {
            continue;
        }
        let tip_pt = to_pt(tip);
        if (tip_pt.y - stroke.at(tip_pt.x).y).abs() <= stroke.half_width(tip_pt.x) {
            continue;
        }
        let mut chain = Vec::new();
        let mut cursor = tip;
        loop {
            chain.push(cursor);
            let q = to_pt(cursor);
            if (q.y - stroke.at(q.x).y).abs() <= stroke.half_width(q.x) && chain.len() > 1 {
                break;
            }
            if cursor == root {
                break;
            }
            cursor = parents[cursor] as usize;
        }
        let q: Vec<EyePoint> = chain.into_iter().map(to_pt).collect();
        if q.len() >= 2 && q[0].distance(*q.last().unwrap()) > 1.5 / eye.width {
            let root_pt = *q.last().unwrap();
            let tip_p = q[0];
            let rev: Vec<EyePoint> = q.into_iter().rev().collect();
            result.push(EyeBranch {
                root: root_pt,
                tip: tip_p,
                points: rev,
            });
        }
    }
    result
}

pub struct FittedEyeMotion {
    pub rigs: HashMap<Side, EyeRig>,
    pub options: EyeOptions,
}

impl FittedEyeMotion {
    pub fn side_at(&self, layer_id: &str, x: f32, y: f32) -> Option<Side> {
        let mut best: Option<(Side, f32)> = None;
        for (&side, eye) in &self.rigs {
            let (u, v) = eye.fit.to_eye(x, y);
            let pt = EyePoint { x: u, y: v };
            let mut min_d = f32::INFINITY;
            let mut has_samples = false;
            if let Some(ref art) = eye.fit.artwork {
                for c in art.components_for_layer(layer_id) {
                    if !c.samples.is_empty() {
                        has_samples = true;
                        min_d = min_d.min(sample_min_dist(&c.samples, pt));
                    }
                }
            }
            let d = if has_samples {
                min_d
            } else {
                let cu = u.clamp(0.0, 1.0);
                let cv = v.clamp(
                    EyeFit::sample(&eye.fit.upper, u),
                    EyeFit::sample(&eye.fit.lower, u),
                );
                (u - cu).hypot(v - cv)
            } * eye.fit.width;
            if best.map(|b| d < b.1).unwrap_or(true) {
                best = Some((side, d));
            }
        }
        best.map(|b| b.0)
    }
}

pub fn open_param(side: Side) -> &'static str {
    if side == Side::LEFT {
        "ParamEyeLOpen"
    } else {
        "ParamEyeROpen"
    }
}

pub fn smile_param(side: Side) -> &'static str {
    if side == Side::LEFT {
        "ParamEyeLSmile"
    } else {
        "ParamEyeRSmile"
    }
}

pub fn form_param(side: Side) -> &'static str {
    if side == Side::LEFT {
        "ParamEyeLForm"
    } else {
        "ParamEyeRForm"
    }
}

pub fn squint_param(side: Side) -> &'static str {
    if side == Side::LEFT {
        "ParamEyeLSquint"
    } else {
        "ParamEyeRSquint"
    }
}

pub fn bounce_param(side: Side) -> &'static str {
    if side == Side::LEFT {
        "ParamEyeLBounce"
    } else {
        "ParamEyeRBounce"
    }
}

pub fn open_keys(eye: &EyeRig, options: &EyeOptions) -> Vec<f32> {
    let mut keys = eye.keys.clone();
    if options.wide {
        keys.push(1.5);
        keys.push(2.0);
    }
    keys
}

fn form_deltas(
    mesh: EyeMesh,
    positions: &[f32],
    frame: Bounds,
    eye: &EyeRig,
    pose: &EyePose,
    layer_id: Option<&str>,
) -> Vec<f32> {
    let mut delta = vec![0.0f32; positions.len()];
    let fw = frame.width().max(1e-4);
    let fh = frame.height().max(1e-4);
    for i in (0..positions.len()).step_by(2) {
        let (dx, dy) = eye.move_vertex(mesh, positions[i], positions[i + 1], pose, layer_id);
        delta[i] = dx / fw;
        delta[i + 1] = dy / fh;
    }
    delta
}

pub fn lid_grid(
    mesh: EyeMesh,
    side: Side,
    positions: &[f32],
    frame: Bounds,
    eye: &EyeRig,
    options: &EyeOptions,
    layer_id: Option<&str>,
) -> KeyGrid<Vec<f32>> {
    let keys = open_keys(eye, options);
    let mut axes = vec![KeyAxis {
        parameter: open_param(side).to_string(),
        keys: keys.clone(),
    }];
    if options.smile {
        axes.push(KeyAxis {
            parameter: smile_param(side).to_string(),
            keys: vec![0.0, 1.0],
        });
    }
    let smiles: &[f32] = if options.smile { &[0.0, 1.0] } else { &[0.0] };
    let mut cells = Vec::with_capacity(smiles.len() * keys.len());
    for (s, &smile_val) in smiles.iter().enumerate() {
        for (o, &open_val) in keys.iter().enumerate() {
            let d = if open_val == 1.0 && smile_val == 0.0 {
                vec![0.0f32; positions.len()]
            } else {
                let pose = if smile_val == 0.0 || open_val > 1.0 {
                    eye.pose(open_val)
                } else {
                    eye.smile_pose(open_val)
                };
                form_deltas(mesh, positions, frame, eye, &pose, layer_id)
            };
            cells.push(KeyCell {
                coordinate: if options.smile {
                    vec![o as i32, s as i32]
                } else {
                    vec![o as i32]
                },
                form: d,
            });
        }
    }
    KeyGrid { axes, cells }
}

pub fn paired_lid_grid(
    mesh: EyeMesh,
    layer_id: &str,
    positions: &[f32],
    frame: Bounds,
    eyes: &FittedEyeMotion,
) -> Option<KeyGrid<Vec<f32>>> {
    if eyes.rigs.is_empty() {
        return None;
    }
    let mut ordered: Vec<(Side, &EyeRig)> = eyes.rigs.iter().map(|(&s, r)| (s, r)).collect();
    ordered.sort_by_key(|(s, _)| *s);
    let owner: Vec<Option<usize>> = (0..positions.len() / 2)
        .map(|i| {
            eyes.side_at(layer_id, positions[i * 2], positions[i * 2 + 1])
                .and_then(|s| ordered.iter().position(|(os, _)| *os == s))
        })
        .collect();
    let grids: Vec<KeyGrid<Vec<f32>>> = ordered
        .iter()
        .map(|&(s, r)| lid_grid(mesh, s, positions, frame, r, &eyes.options, Some(layer_id)))
        .collect();
    let mut cells = vec![KeyCell {
        coordinate: Vec::new(),
        form: vec![0.0f32; positions.len()],
    }];
    for (index, grid) in grids.iter().enumerate() {
        let mut next = Vec::with_capacity(cells.len() * grid.cells.len());
        for prefix in &cells {
            for cell in &grid.cells {
                let mut delta = prefix.form.clone();
                for i in 0..delta.len() {
                    if owner[i / 2] == Some(index) {
                        delta[i] = cell.form[i];
                    }
                }
                let mut coordinate = prefix.coordinate.clone();
                coordinate.extend_from_slice(&cell.coordinate);
                next.push(KeyCell { coordinate, form: delta });
            }
        }
        cells = next;
    }
    let axes = grids.into_iter().flat_map(|g| g.axes).collect();
    Some(KeyGrid { axes, cells })
}

pub fn iris_grid(
    side: Side,
    positions: &[f32],
    frame: Bounds,
    iris_bottom: f32,
    eye: &EyeRig,
    options: &EyeOptions,
    centroid_x: f32,
    centroid_y: f32,
    jelly_squash: f32,
    jelly_stretch: f32,
) -> Option<KeyGrid<Vec<f32>>> {
    if !options.squash {
        return None;
    }
    let mut opens = vec![0.0f32, eye.crease, 1.0];
    if options.wide {
        opens.push(2.0);
    }
    let jellies = [-1.0f32, 0.0, 1.0];
    let fw = frame.width().max(1e-4);
    let fh = frame.height().max(1e-4);
    let mut cells = Vec::with_capacity(opens.len() * jellies.len());
    for (o, &open) in opens.iter().enumerate() {
        let scale_y = if open < 1.0 {
            0.82 + 0.18 * (open / eye.crease).clamp(0.0, 1.0)
        } else {
            1.0
        };
        for (j, &val) in jellies.iter().enumerate() {
            let sx = 1.0 - val * jelly_squash / 100.0;
            let sy = 1.0 + val * jelly_stretch / 100.0;
            let mut delta = vec![0.0f32; positions.len()];
            for i in (0..positions.len()).step_by(2) {
                let jx = centroid_x + (positions[i] - centroid_x) * sx;
                let jy = centroid_y + (positions[i + 1] - centroid_y) * sy;
                let y = iris_bottom + (jy - iris_bottom) * scale_y;
                delta[i] = (jx - positions[i]) / fw;
                delta[i + 1] = (y - positions[i + 1]) / fh;
            }
            cells.push(KeyCell {
                coordinate: vec![j as i32, o as i32],
                form: delta,
            });
        }
    }
    Some(KeyGrid {
        axes: vec![
            KeyAxis {
                parameter: "ParamEyeBallForm".into(),
                keys: jellies.to_vec(),
            },
            KeyAxis {
                parameter: open_param(side).into(),
                keys: opens,
            },
        ],
        cells,
    })
}

pub fn drawing_opacity(
    side: Side,
    closed_line: bool,
    swap: bool,
    max_open: f32,
) -> KeyGrid<ChannelValue> {
    let mut keys = if swap {
        vec![0.0, EyeRig::SWAP, 1.0]
    } else {
        vec![0.0, 1.0]
    };
    if max_open > 1.0 {
        keys.push(max_open);
    }
    let cells = keys
        .iter()
        .enumerate()
        .map(|(i, &v)| {
            let alpha = if !closed_line {
                if v == 0.0 {
                    0.0
                } else {
                    1.0
                }
            } else if swap {
                if v == 0.0 {
                    1.0
                } else {
                    0.0
                }
            } else {
                (1.0 - v).clamp(0.0, 1.0)
            };
            KeyCell {
                coordinate: vec![i as i32],
                form: ChannelValue::Scalar(alpha),
            }
        })
        .collect();
    KeyGrid {
        axes: vec![KeyAxis {
            parameter: open_param(side).into(),
            keys,
        }],
        cells,
    }
}

pub fn opacity_grid(
    tag: &str,
    role: Option<&str>,
    side: Side,
    eye: &EyeRig,
    options: &EyeOptions,
) -> Option<KeyGrid<ChannelValue>> {
    let (mut keys, mut vals) = if options.lid_fade
        && tag == "EYELASH"
        && matches!(role, Some("LID") | Some("SHADOW"))
    {
        (
            vec![0.0, eye.crease + 0.15, 0.75, 1.0],
            vec![0.0, 0.0, 1.0, 1.0],
        )
    } else if options.highlight && tag == "IRIDES" && role == Some("HIGHLIGHT") {
        (
            vec![0.0, eye.crease, (eye.crease + 1.0) * 0.5, 1.0],
            vec![0.0, 0.0, 1.0, 1.0],
        )
    } else {
        return None;
    };
    if options.wide {
        keys.push(2.0);
        vals.push(*vals.last().unwrap());
    }
    let cells = vals
        .into_iter()
        .enumerate()
        .map(|(i, v)| KeyCell {
            coordinate: vec![i as i32],
            form: ChannelValue::Scalar(v),
        })
        .collect();
    Some(KeyGrid {
        axes: vec![KeyAxis {
            parameter: open_param(side).into(),
            keys,
        }],
        cells,
    })
}

pub fn closed_line_grid(
    side: Side,
    positions: &[f32],
    frame: Bounds,
    eye: &EyeRig,
    options: &EyeOptions,
) -> Option<KeyGrid<Vec<f32>>> {
    let drawn = eye.fit.closed.as_ref()?;
    if !options.smile {
        return None;
    }
    let fw = frame.width().max(1e-4);
    let fh = frame.height().max(1e-4);
    let mut bent = vec![0.0f32; positions.len()];
    for i in (0..positions.len()).step_by(2) {
        let (u, _) = eye.fit.to_eye(positions[i], positions[i + 1]);
        let (dx, dy) = eye.fit.canvas_delta(
            0.0,
            EyeFit::sample(&eye.smile_stroke_closed, u) - EyeFit::sample(drawn, u),
        );
        bent[i] = dx / fw;
        bent[i + 1] = dy / fh;
    }
    Some(KeyGrid {
        axes: vec![KeyAxis {
            parameter: smile_param(side).into(),
            keys: vec![0.0, 1.0],
        }],
        cells: vec![
            KeyCell {
                coordinate: vec![0],
                form: vec![0.0; positions.len()],
            },
            KeyCell {
                coordinate: vec![1],
                form: bent,
            },
        ],
    })
}

pub fn lid_shapes(
    mesh: EyeMesh,
    side: Side,
    positions: &[f32],
    frame: Bounds,
    eye: &EyeRig,
    options: &EyeOptions,
    layer_id: Option<&str>,
    rest_order: f32,
    rest_opacity: f32,
    rest_mul: Rgb,
    rest_scr: Rgb,
) -> Vec<BlendBinding<MeshShape>> {
    let h = eye.opening;
    let fade = vec![BlendLimit {
        parameter: open_param(side).into(),
        points: vec![
            BlendLimitPoint {
                value: eye.crease,
                weight: 0.0,
            },
            BlendLimitPoint {
                value: (eye.crease + 1.0) * 0.5,
                weight: 1.0,
            },
        ],
    }];
    let make_shape = |param: &str,
                      keys: &[f32],
                      neutral: usize,
                      limits: Vec<BlendLimit>,
                      pose_at: &dyn Fn(f32) -> EyePose|
     -> BlendBinding<MeshShape> {
        let shapes = keys
            .iter()
            .enumerate()
            .map(|(i, &key)| {
                if i == neutral {
                    None
                } else {
                    let pose = pose_at(key);
                    let deltas = form_deltas(mesh, positions, frame, eye, &pose, layer_id);
                    Some(MeshShape {
                        deltas,
                        draw_order: rest_order,
                        opacity: rest_opacity,
                        multiply: rest_mul.clone(),
                        screen: rest_scr.clone(),
                    })
                }
            })
            .collect();
        BlendBinding {
            parameter: param.to_string(),
            keys: keys.to_vec(),
            neutral_index: neutral as i32,
            shapes,
            limits,
        }
    };

    let mut out = Vec::new();
    if options.gaze {
        let k = options.gaze_follow;
        let by = make_shape("ParamEyeBallY", &[-1.0, 0.0, 1.0], 1, fade.clone(), &|y| {
            if y > 0.0 {
                eye.shape(
                    |u| -0.14 * k * h * hump(u),
                    |u| -0.05 * k * h * hump(u),
                )
            } else {
                eye.shape(
                    |u| 0.22 * k * h * hump(u),
                    |u| 0.06 * k * h * hump(u),
                )
            }
        });
        let bx = make_shape("ParamEyeBallX", &[-1.0, 0.0, 1.0], 1, fade.clone(), &|x| {
            let toward = x * if eye.fit.axis_x >= 0.0 { 1.0 } else { -1.0 };
            eye.shape(
                |u| -0.06 * k * h * hump(u) * (u - 0.5) * 2.0 * toward,
                |_| 0.0,
            )
        });
        out.push(by.clone());
        out.push(bx.clone());
        if side != Side::NONE {
            let mut sx = bx;
            sx.parameter = if side == Side::LEFT {
                "ParamEyeBallLX"
            } else {
                "ParamEyeBallRX"
            }
            .into();
            let mut sy = by;
            sy.parameter = if side == Side::LEFT {
                "ParamEyeBallLY"
            } else {
                "ParamEyeBallRY"
            }
            .into();
            out.push(sy);
            out.push(sx);
        }
    }
    if options.emotion {
        let k = options.emotion_strength;
        out.push(make_shape(
            form_param(side),
            &[-1.0, 0.0, 1.0],
            1,
            fade.clone(),
            &|f| {
                if f > 0.0 {
                    eye.shape(
                        |u| 0.32 * k * h * hump(u) * (1.15 - u),
                        |u| -0.08 * k * h * hump(u),
                    )
                } else {
                    eye.shape(
                        |u| k * h * hump(u) * (-0.18 * (1.0 - u) + 0.22 * u),
                        |u| -0.04 * k * h * hump(u),
                    )
                }
            },
        ));
        out.push(make_shape(
            squint_param(side),
            &[0.0, 1.0],
            0,
            fade.clone(),
            &|_| {
                eye.shape(
                    |u| 0.1 * k * h * hump(u),
                    |u| -0.32 * k * h * hump(u),
                )
            },
        ));
    }
    if options.brow_coupling {
        let k = options.brow_strength;
        let yp = if side == Side::LEFT {
            "ParamBrowLY"
        } else {
            "ParamBrowRY"
        };
        let ap = if side == Side::LEFT {
            "ParamBrowLAngle"
        } else {
            "ParamBrowRAngle"
        };
        out.push(make_shape(yp, &[-1.0, 0.0, 1.0], 1, fade.clone(), &|b| {
            if b < 0.0 {
                eye.shape(
                    |u| 0.16 * k * h * hump(u) * (1.1 - 0.4 * u),
                    |_| 0.0,
                )
            } else {
                eye.shape(|u| -0.1 * k * h * hump(u), |_| 0.0)
            }
        }));
        out.push(make_shape(ap, &[-1.0, 0.0, 1.0], 1, fade.clone(), &|a| {
            eye.shape(
                |u| -0.14 * k * a * h * hump(u) * (1.0 - u) * 1.6,
                |_| 0.0,
            )
        }));
    }
    if options.aftershock {
        let k = options.softness;
        out.push(make_shape(
            bounce_param(side),
            &[-1.0, 0.0, 1.0],
            1,
            Vec::new(),
            &|b| {
                eye.shape(
                    |u| -0.09 * k * b * h * hump(u),
                    |u| 0.02 * k * b * h * hump(u),
                )
            },
        ));
    }
    out
}

pub fn pupil_shapes(
    side: Side,
    positions: &[f32],
    frame: Bounds,
    iris: Bounds,
    options: &EyeOptions,
    rest_order: f32,
    rest_opacity: f32,
    rest_mul: Rgb,
    rest_scr: Rgb,
) -> Vec<BlendBinding<MeshShape>> {
    if !options.brow_coupling {
        return Vec::new();
    }
    let fw = frame.width().max(1e-4);
    let fh = frame.height().max(1e-4);
    let cx = iris.center_x();
    let cy = iris.center_y();
    let scaled = |k: f32| -> MeshShape {
        let mut deltas = vec![0.0f32; positions.len()];
        for i in (0..positions.len()).step_by(2) {
            deltas[i] = (positions[i] - cx) * (k - 1.0) / fw;
            deltas[i + 1] = (positions[i + 1] - cy) * (k - 1.0) / fh;
        }
        MeshShape {
            deltas,
            draw_order: rest_order,
            opacity: rest_opacity,
            multiply: rest_mul.clone(),
            screen: rest_scr.clone(),
        }
    };
    let y = if side == Side::LEFT {
        "ParamBrowLY"
    } else {
        "ParamBrowRY"
    };
    let k = 0.08 * options.brow_strength;
    vec![BlendBinding {
        parameter: y.to_string(),
        keys: vec![-1.0, 0.0, 1.0],
        neutral_index: 1,
        shapes: vec![Some(scaled(1.0 + k * 0.5)), None, Some(scaled(1.0 - k))],
        limits: Vec::new(),
    }]
}
