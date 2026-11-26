//! Evaluates a rig at parameter values: keyform grids, blend shapes, the deformer chain and glue,
//! producing every mesh's vertices in canvas pixels (y down) with its opacity, colors and draw order.

use crate::advanced::Advanced;
use crate::rig::*;
use crate::warp::Lattice;

/// One axis' bracketing keys and the weight of the upper one.
#[derive(Clone, Copy)]
pub(crate) struct Span {
    pub(crate) lo: usize,
    pub(crate) hi: usize,
    pub(crate) t: f32,
}

/// Keyform grids take a key exactly when the value is this close to it; blend shapes do not snap.
const KEY_SNAP: f32 = 0.001;

pub(crate) fn span(keys: &[f32], value: f32) -> Span {
    match keys.iter().find(|k| (value - **k).abs() < KEY_SNAP) {
        Some(k) => span_exact(keys, *k),
        None => span_exact(keys, value),
    }
}

fn span_exact(keys: &[f32], value: f32) -> Span {
    let last = keys.len() - 1;
    if last == 0 || value <= keys[0] {
        return Span { lo: 0, hi: 0, t: 0.0 };
    }
    if value >= keys[last] {
        return Span { lo: last, hi: last, t: 0.0 };
    }
    let mut j = 0;
    while j + 1 < last && keys[j + 1] <= value {
        j += 1;
    }
    let width = keys[j + 1] - keys[j];
    let t = if width > 0.0 { (value - keys[j]) / width } else { 0.0 };
    Span { lo: j, hi: j + 1, t }
}

/// The cells of [grid] that contribute at [values] and their weights. A missing cell of a sparse grid
/// contributes a zero form: no offset for a mesh, the origin for a deformer's absolute forms.
fn weights<F>(grid: &Grid<F>, values: &[f32], out: &mut Vec<(usize, f32)>) {
    out.clear();
    if grid.axes.is_empty() {
        out.push((0, 1.0));
        return;
    }
    let mut spans = [Span { lo: 0, hi: 0, t: 0.0 }; 16];
    let n = grid.axes.len().min(16);
    for (i, axis) in grid.axes.iter().take(n).enumerate() {
        spans[i] = span(&axis.keys, values[axis.parameter]);
    }
    for corner in 0..(1usize << n) {
        let mut weight = 1.0f32;
        let mut index = 0;
        for (i, s) in spans.iter().take(n).enumerate() {
            let upper = corner >> i & 1 == 1;
            if upper && s.hi == s.lo {
                weight = 0.0;
                break;
            }
            weight *= if upper { s.t } else { 1.0 - s.t };
            index += if upper { s.hi } else { s.lo } * grid.strides[i];
        }
        if weight <= 0.0 {
            continue;
        }
        let cell = grid.dense[index];
        if cell != u32::MAX {
            out.push((cell as usize, weight));
        }
    }
}

/// The cell of a flag grid at or below [values] on every axis: flags hold until the next key.
fn floor_cell<F>(grid: &Grid<F>, values: &[f32]) -> Option<usize> {
    let mut index = 0;
    for (i, axis) in grid.axes.iter().enumerate() {
        let s = span(&axis.keys, values[axis.parameter]);
        index += s.lo * grid.strides[i];
    }
    let cell = grid.dense[index];
    if cell == u32::MAX {
        None
    } else {
        Some(cell as usize)
    }
}


/// Channel values of one object at a pose, starting from its static values.
#[derive(Clone, Copy)]
pub(crate) struct ChannelState {
    draw_order: f32,
    opacity: f32,
    multiply: Rgb,
    screen: Rgb,
    flip_x: bool,
    flip_y: bool,
    glue_intensity: f32,
}

fn apply_channels(channels: &Channels, values: &[f32], state: &mut ChannelState, scratch: &mut Vec<(usize, f32)>) {
    for (channel, grid) in channels {
        if let Channel::FlipX | Channel::FlipY = channel {
            if let Some(cell) = floor_cell(grid, values) {
                if let ChannelValue::Flag(v) = grid.cells[cell].1 {
                    if *channel == Channel::FlipX {
                        state.flip_x = v
                    } else {
                        state.flip_y = v
                    }
                }
            }
            continue;
        }
        weights(grid, values, scratch);
        let mut scalar = 0.0;
        let mut color = [0.0f32; 3];
        for &(cell, w) in scratch.iter() {
            match grid.cells[cell].1 {
                ChannelValue::Scalar(v) => scalar += v * w,
                ChannelValue::Color(c) => {
                    for k in 0..3 {
                        color[k] += c[k] * w
                    }
                }
                ChannelValue::Flag(_) => {}
            }
        }
        match channel {
            Channel::DrawOrder => state.draw_order = scalar,
            Channel::Opacity => state.opacity = scalar,
            Channel::GlueIntensity => state.glue_intensity = scalar,
            Channel::MultiplyColor => state.multiply = color,
            Channel::ScreenColor => state.screen = color,
            Channel::FlipX | Channel::FlipY => {}
        }
    }
}

/// Moves channel values toward a blend shape's targets by [w], from the values before any shape.
fn toward(state: &mut ChannelState, base: &ChannelState, opacity: f32, draw_order: Option<f32>, multiply: Rgb, screen: Rgb, w: f32) {
    state.opacity += (opacity - base.opacity) * w;
    if let Some(d) = draw_order {
        state.draw_order += (d - base.draw_order) * w;
    }
    for k in 0..3 {
        state.multiply[k] += (multiply[k] - base.multiply[k]) * w;
        state.screen[k] += (screen[k] - base.screen[k]) * w;
    }
}

fn piecewise(points: &[(f32, f32)], x: f32) -> f32 {
    match points {
        [] => 1.0,
        [only] => only.1,
        _ => {
            if x <= points[0].0 {
                return points[0].1;
            }
            for w in points.windows(2) {
                if x <= w[1].0 {
                    let width = w[1].0 - w[0].0;
                    let t = if width > 0.0 { (x - w[0].0) / width } else { 1.0 };
                    return w[0].1 + (w[1].1 - w[0].1) * t;
                }
            }
            points[points.len() - 1].1
        }
    }
}

/// The shapes a binding mixes at [values] with their weights, its limits applied.
/// Keys without a shape, and the neutral key, add nothing.
fn blend<'a, S>(binding: &'a Binding<S>, values: &[f32], out: &mut Vec<(&'a S, f32)>) {
    out.clear();
    let s = span_exact(&binding.keys, values[binding.parameter]);
    let limit: f32 = binding.limits.iter().map(|l| piecewise(&l.points, values[l.parameter])).product();
    if limit == 0.0 {
        return;
    }
    for (i, (key, weight)) in [(s.lo, 1.0 - s.t), (s.hi, s.t)].into_iter().enumerate() {
        if weight <= 0.0 || key == binding.neutral || (i == 1 && s.hi == s.lo) {
            continue;
        }
        if let Some(shape) = &binding.shapes[key] {
            out.push((shape, weight * limit));
        }
    }
}

/// A draw order as the sort key: plus a thousandth, truncated, so nearly tied blended orders tie.
fn order_key(value: f32) -> i64 {
    (value + 0.001).floor() as i64
}

/// Meshes back to front: each render group sorts its children by draw order (a group by its part's
/// pose-blended draw order, else its static one), keeping tree order on ties, and draws them in place.
/// Hidden meshes are left out.
pub fn render_order(rig: &Rig, pose: &Pose) -> Vec<u32> {
    let mut out = Vec::with_capacity(rig.meshes.len());
    walk_render(rig, pose, &mut |c| if c >= 0 { out.push(c as u32) });
    out
}

/// The command [render_commands] writes to close an isolated group.
pub const RENDER_END_GROUP: i32 = -1;

/// The part of the isolated group a render command opens, or `None` for a mesh or a group's end.
pub fn render_group_part(command: i32) -> Option<usize> {
    (command <= -2).then(|| (-2 - command) as usize)
}

/// [render_order] with isolated groups kept: a mesh index, `-2 - part` opening the group of an isolated part
/// (a group with a part and a composite) and [RENDER_END_GROUP] closing it. Groups that draw nothing are left out.
pub fn render_commands(rig: &Rig, pose: &Pose) -> Vec<i32> {
    let mut out: Vec<i32> = Vec::with_capacity(rig.meshes.len());
    walk_render(rig, pose, &mut |c| {
        if c == RENDER_END_GROUP && out.last().is_some_and(|l| render_group_part(*l).is_some()) {
            out.pop();
        } else {
            out.push(c)
        }
    });
    out
}

/// Visits the render tree back to front: visible meshes by index, isolated groups as their open and close commands.
fn walk_render(rig: &Rig, pose: &Pose, visit: &mut impl FnMut(i32)) {
    fn group(rig: &Rig, pose: &Pose, g: &RenderGroup, visit: &mut impl FnMut(i32)) {
        let mut children: Vec<(i64, &RenderNode)> = g
            .children
            .iter()
            .map(|c| match c {
                RenderNode::Mesh(m) => (order_key(pose.draw_order[*m]), c),
                RenderNode::Group(child) => {
                    let order = child.part.and_then(|p| pose.part_draw_order.get(p).copied()).unwrap_or(child.draw_order as f32);
                    (order_key(order), c)
                }
            })
            .collect();
        children.sort_by_key(|c| c.0);
        for (_, c) in children {
            match c {
                RenderNode::Mesh(m) => {
                    if rig.meshes[*m].visible {
                        visit(*m as i32)
                    }
                }
                RenderNode::Group(child) => match (child.part, &child.composite) {
                    (Some(p), Some(_)) => {
                        visit(-2 - p as i32);
                        group(rig, pose, child, visit);
                        visit(RENDER_END_GROUP);
                    }
                    _ => group(rig, pose, child, visit),
                },
            }
        }
    }
    group(rig, pose, &rig.render, visit);
}

/// The first hit area (in file order) one of whose visible meshes covers canvas point ([x], [y]) in [pose].
pub fn hit_area_at(rig: &Rig, pose: &Pose, x: f32, y: f32) -> Option<usize> {
    let covers = |m: usize| -> bool {
        let (Some(g), Some(v)) = (&rig.meshes[m].geometry, pose.vertices.get(m)) else { return false };
        if !rig.meshes[m].visible || pose.opacity.get(m).map_or(true, |o| *o <= 0.0) {
            return false;
        }
        g.indices.chunks_exact(3).any(|t| {
            let p = |i: u32| (v[i as usize * 2], v[i as usize * 2 + 1]);
            let (a, b, c) = (p(t[0]), p(t[1]), p(t[2]));
            let side = |p: (f32, f32), q: (f32, f32)| (q.0 - p.0) * (y - p.1) - (q.1 - p.1) * (x - p.0);
            let (d1, d2, d3) = (side(a, b), side(b, c), side(c, a));
            !((d1 < 0.0 || d2 < 0.0 || d3 < 0.0) && (d1 > 0.0 || d2 > 0.0 || d3 > 0.0))
        })
    };
    rig.hit_areas.iter().position(|(_, _, meshes)| meshes.iter().any(|m| covers(*m)))
}

/// Where a deformer places its children: a lattice of canvas points or a rotation frame.
#[derive(Clone, Debug)]
pub enum Transform {
    /// [scale] is the scale of the nearest rotation above, which rotations inside the lattice inherit.
    Warp { points: Vec<f32>, columns: usize, rows: usize, bilinear: bool, scale: f32 },
    Rotation { origin: [f32; 2], angle: f32, scale: f32, flip_x: bool, flip_y: bool },
}

impl Transform {
    pub fn apply(&self, x: f32, y: f32) -> [f32; 2] {
        match self {
            Transform::Warp { points, columns, rows, bilinear, .. } => Lattice { points, columns: *columns, rows: *rows, bilinear: *bilinear }.map(x, y),
            Transform::Rotation { origin, angle, scale, flip_x, flip_y } => {
                let x = if *flip_x { -x } else { x } * scale;
                let y = if *flip_y { -y } else { y } * scale;
                let (sin, cos) = angle.to_radians().sin_cos();
                [origin[0] + x * cos - y * sin, origin[1] + x * sin + y * cos]
            }
        }
    }
}

/// The evaluated pose: per mesh in rig order.
#[derive(Default, Clone, Debug)]
pub struct Pose {
    /// Canvas pixels, y down, two floats per vertex.
    pub vertices: Vec<Vec<f32>>,
    pub opacity: Vec<f32>,
    pub draw_order: Vec<f32>,
    pub multiply: Vec<Rgb>,
    pub screen: Vec<Rgb>,
    /// Deformer frames, for hosts that attach objects to them.
    pub deformers: Vec<Transform>,
    /// Accumulated opacity of each deformer.
    pub deformer_opacity: Vec<f32>,
    /// Draw order of each part, blended from its channels and blend shapes.
    pub part_draw_order: Vec<f32>,
    /// What each part's isolated group composites with: its group's channels over its composite's statics,
    /// opacity clamped to 0..1. A part without a group keeps its statics.
    pub part_opacity: Vec<f32>,
    pub part_multiply: Vec<Rgb>,
    pub part_screen: Vec<Rgb>,
}

#[derive(Default)]
pub struct Evaluator {
    values: Vec<f32>,
    defaults: Vec<f32>,
    cells: Vec<(usize, f32)>,
    pub pose: Pose,
}

impl Evaluator {
    pub fn new() -> Evaluator {
        Evaluator::default()
    }

    /// Evaluates [rig] at [parameters], one value per rig parameter; missing values take defaults.
    ///
    /// Blend shapes add their difference from the object's form at the default pose, weighted by the
    /// blend parameter and its limits; mesh shapes hold target offsets.
    pub fn evaluate(&mut self, rig: &Rig, parameters: &[f32]) -> &Pose {
        self.evaluate_ext(rig, parameters, None)
    }

    /// [evaluate] with the advanced features [advanced] has on; `None`, or none on, is exactly [evaluate].
    pub fn evaluate_ext(&mut self, rig: &Rig, parameters: &[f32], mut advanced: Option<&mut Advanced>) -> &Pose {
        self.values.clear();
        self.defaults.clear();
        for (i, p) in rig.parameters.iter().enumerate() {
            self.values.push(p.normalize(parameters.get(i).copied().unwrap_or(p.default)));
            self.defaults.push(p.normalize(p.default));
        }
        if let Some(a) = advanced.as_deref_mut() {
            a.override_parameters(rig, &mut self.values, &self.defaults);
        }
        let (values, defaults, cells) = (&self.values, &self.defaults, &mut self.cells);
        let pose = &mut self.pose;
        pose.deformers.clear();
        pose.deformer_opacity.clear();
        for (i, d) in rig.deformers.iter().enumerate() {
            let parent = d.parent.map(|p| &pose.deformers[p]);
            let arc = advanced.as_deref().and_then(|a| a.arc(rig, i));
            let (transform, state) = deformer_frame(d, parent, values, defaults, cells, arc);
            let inherited = d.parent.map_or(1.0, |p| pose.deformer_opacity[p]);
            // Each stage clamps, so blend shapes cannot carry an object below transparent.
            pose.deformer_opacity.push(state.opacity.clamp(0.0, 1.0) * inherited);
            pose.deformers.push(transform);
        }

        pose.vertices.resize(rig.meshes.len(), Vec::new());
        pose.opacity.clear();
        pose.draw_order.clear();
        pose.multiply.clear();
        pose.screen.clear();
        for (m, mesh) in rig.meshes.iter().enumerate() {
            let out = &mut pose.vertices[m];
            let state = mesh_local(mesh, values, defaults, cells, out);
            if let Some(a) = advanced.as_deref_mut() {
                a.correct_mesh(rig, m, values, defaults, out);
            }
            if let Some(parent) = mesh.parent {
                let transform = &pose.deformers[parent];
                for p in out.chunks_exact_mut(2) {
                    let q = transform.apply(p[0], p[1]);
                    p[0] = q[0];
                    p[1] = q[1];
                }
            }
            let inherited = mesh.parent.map_or(1.0, |p| pose.deformer_opacity[p]);
            pose.opacity.push(state.opacity.clamp(0.0, 1.0) * inherited);
            pose.draw_order.push(state.draw_order);
            pose.multiply.push(state.multiply);
            pose.screen.push(state.screen);
        }

        pose.part_draw_order.clear();
        let mut shapes_part: Vec<(&PartShape, f32)> = Vec::new();
        for part in &rig.parts {
            let mut state = part_state(part, values, cells);
            if !part.shapes.is_empty() {
                let base = part_state(part, defaults, cells);
                for binding in &part.shapes {
                    blend(binding, values, &mut shapes_part);
                    for (s, w) in shapes_part.drain(..) {
                        toward(&mut state, &base, s.opacity, Some(s.draw_order), s.multiply, s.screen, w);
                    }
                }
            }
            pose.part_draw_order.push(state.draw_order);
        }
        pose.part_opacity.clear();
        pose.part_multiply.clear();
        pose.part_screen.clear();
        for part in &rig.parts {
            pose.part_opacity.push(part.composite.opacity.clamp(0.0, 1.0));
            pose.part_multiply.push(part.composite.multiply);
            pose.part_screen.push(part.composite.screen);
        }
        composite_states(&rig.render, values, cells, pose);

        for glue in &rig.glues {
            let mut state = ChannelState {
                draw_order: 0.0, opacity: 1.0, multiply: WHITE, screen: BLACK, flip_x: false, flip_y: false, glue_intensity: glue.intensity,
            };
            apply_channels(&glue.channels, values, &mut state, cells);
            let intensity = state.glue_intensity;
            if glue.mesh_a == glue.mesh_b {
                let vertices = &mut pose.vertices[glue.mesh_a];
                for pair in &glue.pairs {
                    for k in 0..2 {
                        let a = pair.a * 2 + k;
                        let b = pair.b * 2 + k;
                        let (pa, pb) = (vertices[a], vertices[b]);
                        vertices[a] = pa + (pb - pa) * pair.weight_a * intensity;
                        vertices[b] = pb + (pa - pb) * pair.weight_b * intensity;
                    }
                }
                continue;
            }
            let (a, b) = if glue.mesh_a < glue.mesh_b {
                let (left, right) = pose.vertices.split_at_mut(glue.mesh_b);
                (&mut left[glue.mesh_a], &mut right[0])
            } else {
                let (left, right) = pose.vertices.split_at_mut(glue.mesh_a);
                (&mut right[0], &mut left[glue.mesh_b])
            };
            for pair in &glue.pairs {
                let (pa, pb) = ([a[pair.a * 2], a[pair.a * 2 + 1]], [b[pair.b * 2], b[pair.b * 2 + 1]]);
                for k in 0..2 {
                    a[pair.a * 2 + k] = pa[k] + (pb[k] - pa[k]) * pair.weight_a * intensity;
                    b[pair.b * 2 + k] = pb[k] + (pa[k] - pb[k]) * pair.weight_b * intensity;
                }
            }
        }
        pose
    }
}

/// A deformer's frame and channel state at [values], under its parent's frame. With an [arc], its keyed pivot
/// positions are interpolated along that circle.
pub(crate) fn deformer_frame(
    d: &Deformer, parent: Option<&Transform>, values: &[f32], defaults: &[f32], cells: &mut Vec<(usize, f32)>, arc: Option<&Arc>,
) -> (Transform, ChannelState) {
    let mut state = deformer_state(d, values, cells);
    let transform = match &d.kind {
        DeformerKind::Warp { columns, rows, bilinear, lattice, shapes } => {
            let len = (columns + 1) * (rows + 1) * 2;
            let mut points = lattice_points(lattice.as_ref(), len, values, cells);
            if !shapes.is_empty() {
                let base = deformer_state(d, defaults, cells);
                let base_points = lattice_points(lattice.as_ref(), len, defaults, cells);
                let mut blended: Vec<(&LatticeShape, f32)> = Vec::new();
                for binding in shapes {
                    blend(binding, values, &mut blended);
                    for (s, w) in blended.drain(..) {
                        for ((o, v), b) in points.iter_mut().zip(&s.points).zip(&base_points) {
                            *o += (v - b) * w;
                        }
                        toward(&mut state, &base, s.opacity, None, s.multiply, s.screen, w);
                    }
                }
            }
            if let Some(parent) = parent {
                for p in points.chunks_exact_mut(2) {
                    let q = parent.apply(p[0], p[1]);
                    p[0] = q[0];
                    p[1] = q[1];
                }
            }
            let scale = match parent {
                Some(Transform::Warp { scale, .. }) | Some(Transform::Rotation { scale, .. }) => *scale,
                None => 1.0,
            };
            Transform::Warp { points, columns: *columns, rows: *rows, bilinear: *bilinear, scale }
        }
        DeformerKind::Rotation { base_angle, pivot, shapes, .. } => {
            let mut p = pivot_at(pivot.as_ref(), values, cells);
            if !shapes.is_empty() {
                let base = deformer_state(d, defaults, cells);
                let b = pivot_at(pivot.as_ref(), defaults, cells);
                let mut blended: Vec<(&PivotShape, f32)> = Vec::new();
                for binding in shapes {
                    blend(binding, values, &mut blended);
                    for (s, w) in blended.drain(..) {
                        p.x += (s.pivot.x - b.x) * w;
                        p.y += (s.pivot.y - b.y) * w;
                        p.angle += (s.pivot.angle - b.angle) * w;
                        p.scale += (s.pivot.scale - b.scale) * w;
                        toward(&mut state, &base, s.opacity, None, s.multiply, s.screen, w);
                    }
                }
            }
            if let (Some(arc), Some(grid)) = (arc, pivot) {
                along_arc(grid, arc, values, &mut p);
            }
            rotation_frame(parent, p, *base_angle, state.flip_x, state.flip_y)
        }
    };
    (transform, state)
}

/// Moves a pivot from the chord between its two bracketing keys onto the circle about [arc]'s center,
/// turning and scaling the radius in step with the key weight.
fn along_arc(grid: &Grid<Pivot>, arc: &Arc, values: &[f32], p: &mut Pivot) {
    let s = span(&grid.axes[0].keys, values[arc.parameter]);
    let (lo, hi) = (grid.dense[s.lo], grid.dense[s.hi]);
    if s.lo == s.hi || lo == u32::MAX || hi == u32::MAX {
        return;
    }
    let (a, b) = (grid.cells[lo as usize].1, grid.cells[hi as usize].1);
    let [cx, cy] = arc.center;
    let (ra, rb) = ((a.x - cx).hypot(a.y - cy), (b.x - cx).hypot(b.y - cy));
    let start = (a.y - cy).atan2(a.x - cx);
    let mut turn = (b.y - cy).atan2(b.x - cx) - start;
    if turn > std::f32::consts::PI {
        turn -= std::f32::consts::TAU;
    } else if turn < -std::f32::consts::PI {
        turn += std::f32::consts::TAU;
    }
    let (angle, radius) = (start + turn * s.t, ra + (rb - ra) * s.t);
    let chord = (a.x + (b.x - a.x) * s.t, a.y + (b.y - a.y) * s.t);
    p.x += cx + radius * angle.cos() - chord.0;
    p.y += cy + radius * angle.sin() - chord.1;
}

/// A mesh's vertices in its parent's space at [values] - rest positions, keyform offsets and blend shapes - and
/// its channel state.
pub(crate) fn mesh_local(mesh: &Mesh, values: &[f32], defaults: &[f32], cells: &mut Vec<(usize, f32)>, out: &mut Vec<f32>) -> ChannelState {
    let mut state = mesh_state(mesh, values, cells);
    out.clear();
    if let Some(geometry) = &mesh.geometry {
        out.extend_from_slice(&geometry.positions);
        if let Some(grid) = &mesh.offsets {
            weights(grid, values, cells);
            for &(cell, w) in cells.iter() {
                for (o, d) in out.iter_mut().zip(&grid.cells[cell].1) {
                    *o += d * w;
                }
            }
        }
    }
    if !mesh.shapes.is_empty() {
        let base = mesh_state(mesh, defaults, cells);
        // Shape deltas are target offsets: they replace the offsets the default pose has.
        let mut base_offsets = vec![0.0f32; out.len()];
        if let Some(grid) = &mesh.offsets {
            weights(grid, defaults, cells);
            for &(cell, w) in cells.iter() {
                for (o, d) in base_offsets.iter_mut().zip(&grid.cells[cell].1) {
                    *o += d * w;
                }
            }
        }
        let mut blended: Vec<(&MeshShape, f32)> = Vec::new();
        for binding in &mesh.shapes {
            blend(binding, values, &mut blended);
            for (s, w) in blended.drain(..) {
                for ((o, d), b) in out.iter_mut().zip(&s.deltas).zip(&base_offsets) {
                    *o += (d - b) * w;
                }
                toward(&mut state, &base, s.opacity, Some(s.draw_order), s.multiply, s.screen, w);
            }
        }
    }
    state
}

fn deformer_state(d: &Deformer, values: &[f32], cells: &mut Vec<(usize, f32)>) -> ChannelState {
    let mut state = ChannelState { draw_order: 0.0, opacity: d.opacity, multiply: d.multiply, screen: d.screen, flip_x: false, flip_y: false, glue_intensity: 1.0 };
    if let DeformerKind::Rotation { flip_x, flip_y, .. } = d.kind {
        state.flip_x = flip_x;
        state.flip_y = flip_y;
    }
    apply_channels(&d.channels, values, &mut state, cells);
    state
}

/// Each isolated group's composite state into [pose]: the group's channels over its composite's statics.
fn composite_states(g: &RenderGroup, values: &[f32], cells: &mut Vec<(usize, f32)>, pose: &mut Pose) {
    if let (Some(p), Some(c)) = (g.part, &g.composite) {
        if p < pose.part_opacity.len() {
            let mut state = ChannelState {
                draw_order: 0.0, opacity: c.opacity, multiply: c.multiply, screen: c.screen, flip_x: false, flip_y: false, glue_intensity: 1.0,
            };
            apply_channels(&g.channels, values, &mut state, cells);
            pose.part_opacity[p] = state.opacity.clamp(0.0, 1.0);
            pose.part_multiply[p] = state.multiply;
            pose.part_screen[p] = state.screen;
        }
    }
    for child in &g.children {
        if let RenderNode::Group(child) = child {
            composite_states(child, values, cells, pose);
        }
    }
}

fn part_state(part: &Part, values: &[f32], cells: &mut Vec<(usize, f32)>) -> ChannelState {
    let mut state = ChannelState {
        draw_order: part.draw_order as f32, opacity: 1.0, multiply: WHITE, screen: BLACK, flip_x: false, flip_y: false, glue_intensity: 1.0,
    };
    apply_channels(&part.channels, values, &mut state, cells);
    state
}

fn mesh_state(mesh: &Mesh, values: &[f32], cells: &mut Vec<(usize, f32)>) -> ChannelState {
    let mut state = ChannelState {
        draw_order: mesh.draw_order, opacity: mesh.opacity, multiply: mesh.multiply, screen: mesh.screen, flip_x: false, flip_y: false, glue_intensity: 1.0,
    };
    apply_channels(&mesh.channels, values, &mut state, cells);
    state
}

/// A lattice's control points at [values]; without keyforms, all at the origin.
fn lattice_points(grid: Option<&Grid<Vec<f32>>>, len: usize, values: &[f32], cells: &mut Vec<(usize, f32)>) -> Vec<f32> {
    let mut points = vec![0.0; len];
    if let Some(grid) = grid {
        weights(grid, values, cells);
        for &(cell, w) in cells.iter() {
            for (o, v) in points.iter_mut().zip(&grid.cells[cell].1) {
                *o += v * w;
            }
        }
    }
    points
}

/// A rotation's pivot at [values]; without keyforms, the identity at the origin.
fn pivot_at(grid: Option<&Grid<Pivot>>, values: &[f32], cells: &mut Vec<(usize, f32)>) -> Pivot {
    let Some(grid) = grid else { return Pivot { x: 0.0, y: 0.0, angle: 0.0, scale: 1.0 } };
    weights(grid, values, cells);
    let mut p = Pivot { x: 0.0, y: 0.0, angle: 0.0, scale: 0.0 };
    for &(cell, w) in cells.iter() {
        let c = grid.cells[cell].1;
        p.x += c.x * w;
        p.y += c.y * w;
        p.angle += c.angle * w;
        p.scale += c.scale * w;
    }
    p
}

/// The frame a rotation deformer gives its children, from its pivot in its parent's space.
fn rotation_frame(parent: Option<&Transform>, mut p: Pivot, base_angle: f32, flip_x: bool, flip_y: bool) -> Transform {
    // The base angle turns the frame on top of the keyed angle.
    p.angle += base_angle;
    match parent {
        None => Transform::Rotation { origin: [p.x, p.y], angle: p.angle, scale: p.scale, flip_x, flip_y },
        Some(Transform::Rotation { angle, scale, flip_y: parent_flip_y, .. }) => {
            // The parent's flips place the pivot but do not mirror this frame: a vertical flip turns it
            // half way round, a horizontal one leaves it.
            let origin = parent.unwrap().apply(p.x, p.y);
            // A negative parent scale turns the measured direction half way round as well, which keeps
            // the child upright overall.
            let half = if *parent_flip_y { 180.0 } else { 0.0 } + if *scale < 0.0 { 180.0 } else { 0.0 };
            Transform::Rotation { origin, angle: angle + p.angle + half, scale: scale * p.scale, flip_x, flip_y }
        }
        Some(warp @ Transform::Warp { scale, .. }) => {
            // The frame stays rigid: it turns with the lattice's direction along v, measured from a point
            // a tenth of the lattice above the pivot, and keeps the scale of the rotation above the lattice.
            let origin = warp.apply(p.x, p.y);
            let above = warp.apply(p.x, p.y - 0.1);
            // Measured toward the point above, so a collapsed lattice turns by a quarter.
            let turn = (above[1] - origin[1]).atan2(above[0] - origin[0]).to_degrees() + 90.0;
            Transform::Rotation { origin, angle: p.angle + turn, scale: p.scale * scale, flip_x, flip_y }
        }
    }
}
