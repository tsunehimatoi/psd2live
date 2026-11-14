use crate::rig_ir::{
    eval_color_channel, eval_floats_grid, eval_grid_color, eval_grid_scalar, eval_scalar_channel,
    grid_ref, set_channel, BlendBinding, ChannelValue, Channels, Deformer, KeyAxis, KeyCell,
    KeyGrid, LatticeShape, Mesh, MeshGeometry, MeshShape, Parameter, Rgb, RigIR,
    CHANNEL_DRAW_ORDER, CHANNEL_MULTIPLY_COLOR, CHANNEL_OPACITY, CHANNEL_SCREEN_COLOR,
};
use std::collections::{HashMap, HashSet};

const MESH_CELLS: usize = 256;
const MESH_VALUES: usize = 120_000;
const WARP_CELLS: usize = 64;
const LATTICE: usize = 8;
const SAMPLED_CELLS: usize = 32;
const EPS_KEY: f32 = 1e-3;

fn is_head_param(id: &str) -> bool {
    matches!(id, "ParamAngleX" | "ParamAngleY" | "ParamAngleZ")
}

pub fn lawful<F>(generated: &RigIR, mut carrier_name: F) -> RigIR
where
    F: FnMut(bool, &str) -> String,
{
    let defaults: HashMap<String, f32> = generated
        .parameters
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();

    let mut meshes = generated.meshes.clone();
    let mut draw_order_changed = false;
    for d in &mut meshes {
        if d.shapes.is_empty() {
            continue;
        }
        let order = eval_scalar_channel(&d.channels, CHANNEL_DRAW_ORDER, d.draw_order, &defaults);
        let all_match = d.shapes.iter().all(|b| {
            b.shapes
                .iter()
                .all(|f| f.as_ref().map_or(true, |s| s.draw_order == order))
        });
        if !all_match {
            draw_order_changed = true;
            for b in &mut d.shapes {
                for f in &mut b.shapes {
                    if let Some(s) = f {
                        s.draw_order = order;
                    }
                }
            }
        }
    }

    let mut puppet = generated.clone();
    if draw_order_changed {
        puppet.meshes = meshes;
    }

    let axes = grid_parameters(&puppet);
    let kinds: HashMap<&str, bool> = puppet
        .parameters
        .iter()
        .map(|p| (p.id.as_str(), p.blend))
        .collect();

    // Collect (parameter, keys, neutral_index) for all ordinary (non-blend) bindings.
    let mut ordinary_params: Vec<String> = Vec::new();
    let mut ordinary_by_param: HashMap<String, Vec<(Vec<f32>, i32)>> = HashMap::new();
    let mut all_binding_keys: HashMap<String, Vec<f32>> = HashMap::new();

    let mut record_binding = |param: &str, keys: &[f32], neutral_index: i32| {
        all_binding_keys
            .entry(param.to_string())
            .or_default()
            .extend_from_slice(keys);
        if kinds.get(param).copied() == Some(false) {
            if !ordinary_by_param.contains_key(param) {
                ordinary_params.push(param.to_string());
            }
            ordinary_by_param
                .entry(param.to_string())
                .or_default()
                .push((keys.to_vec(), neutral_index));
        }
    };

    for d in &puppet.meshes {
        for b in &d.shapes {
            record_binding(&b.parameter, &b.keys, b.neutral_index);
        }
    }
    for d in &puppet.deformers {
        match d {
            Deformer::Warp { shapes, .. } => {
                for b in shapes {
                    record_binding(&b.parameter, &b.keys, b.neutral_index);
                }
            }
            Deformer::Rotation { shapes, .. } => {
                for b in shapes {
                    record_binding(&b.parameter, &b.keys, b.neutral_index);
                }
            }
        }
    }
    for p in &puppet.parts {
        for b in &p.shapes {
            record_binding(&b.parameter, &b.keys, b.neutral_index);
        }
    }

    if ordinary_by_param.is_empty() {
        return puppet;
    }

    let mut retyped: HashSet<String> = HashSet::new();
    for (id, ridden) in &ordinary_by_param {
        let def = defaults.get(id).copied().unwrap_or(0.0);
        if !axes.contains(id.as_str())
            && def == 0.0
            && ridden.iter().all(|(keys, neutral_idx)| {
                let idx = *neutral_idx as usize;
                idx < keys.len() && keys[idx].abs() < EPS_KEY
            })
        {
            retyped.insert(id.clone());
        }
    }

    let folded: HashSet<String> = ordinary_params
        .into_iter()
        .filter(|id| !retyped.contains(id))
        .collect();

    let ranges: HashMap<String, (f32, f32)> = puppet
        .parameters
        .iter()
        .map(|p| (p.id.clone(), (p.min, p.max)))
        .collect();

    let part_of: HashMap<String, Option<String>> = puppet
        .deformers
        .iter()
        .map(|d| match d {
            Deformer::Warp { id, part, .. } | Deformer::Rotation { id, part, .. } => {
                (id.clone(), part.clone())
            }
        })
        .collect();

    let existing_deformer_ids: HashSet<String> = puppet
        .deformers
        .iter()
        .map(|d| match d {
            Deformer::Warp { id, .. } | Deformer::Rotation { id, .. } => id.clone(),
        })
        .collect();

    let mut carriers: Vec<Deformer> = Vec::new();
    let mut carrier_ids: HashSet<String> = HashSet::new();

    let next_drawables: Vec<Mesh> = puppet
        .meshes
        .iter()
        .map(|d| {
            if !d.shapes.iter().any(|b| folded.contains(&b.parameter)) {
                return d.clone();
            }
            let part = d
                .parent
                .as_ref()
                .and_then(|pid| part_of.get(pid))
                .and_then(|p| p.clone());
            let (folded_mesh, new_warps) = fold_drawable(
                d,
                &folded,
                &defaults,
                &ranges,
                part.as_deref(),
                &mut carrier_name,
                |base| {
                    let mut n = 1usize;
                    loop {
                        let cand = if n == 1 {
                            base.to_string()
                        } else {
                            format!("{base}_{n}")
                        };
                        if !existing_deformer_ids.contains(&cand) && !carrier_ids.contains(&cand) {
                            carrier_ids.insert(cand.clone());
                            return cand;
                        }
                        n += 1;
                    }
                },
            );
            carriers.extend(new_warps);
            folded_mesh
        })
        .collect();

    let next_parameters: Vec<Parameter> = puppet
        .parameters
        .into_iter()
        .map(|mut p| {
            if retyped.contains(&p.id) {
                p.blend = true;
                let mut keys = p.keys.unwrap_or_default();
                if let Some(bk) = all_binding_keys.get(&p.id) {
                    keys.extend_from_slice(bk);
                }
                let mut dedup: Vec<f32> = Vec::new();
                for k in keys {
                    if !dedup.iter().any(|&x| x == k) {
                        dedup.push(k);
                    }
                }
                dedup.sort_by(|a, b| a.total_cmp(b));
                p.keys = Some(dedup);
            }
            p
        })
        .collect();

    let mut next_deformers: Vec<Deformer> = puppet
        .deformers
        .into_iter()
        .map(|d| match &d {
            Deformer::Warp { shapes, .. } if shapes.iter().any(|b| folded.contains(&b.parameter)) => {
                fold_warp(&d, &folded, &defaults, &ranges)
            }
            _ => d,
        })
        .collect();
    next_deformers.extend(carriers);

    RigIR {
        parameters: next_parameters,
        meshes: next_drawables,
        deformers: next_deformers,
        ..puppet
    }
}

fn grid_parameters(puppet: &RigIR) -> HashSet<String> {
    let mut set = HashSet::new();
    fn add_channels(set: &mut HashSet<String>, channels: &Channels) {
        for (_, grid) in channels {
            for a in &grid.axes {
                set.insert(a.parameter.clone());
            }
        }
    }
    for d in &puppet.meshes {
        if let Some(g) = &d.offsets {
            for a in &g.axes {
                set.insert(a.parameter.clone());
            }
        }
        add_channels(&mut set, &d.channels);
    }
    for d in &puppet.deformers {
        match d {
            Deformer::Warp {
                lattice, channels, ..
            } => {
                if let Some(g) = lattice {
                    for a in &g.axes {
                        set.insert(a.parameter.clone());
                    }
                }
                add_channels(&mut set, channels);
            }
            Deformer::Rotation {
                pivot, channels, ..
            } => {
                if let Some(g) = pivot {
                    for a in &g.axes {
                        set.insert(a.parameter.clone());
                    }
                }
                add_channels(&mut set, channels);
            }
        }
    }
    for p in &puppet.parts {
        add_channels(&mut set, &p.channels);
    }
    for g in &puppet.glues {
        add_channels(&mut set, &g.channels);
    }
    set
}

fn weights<S>(binding: &BlendBinding<S>, pose: &HashMap<String, f32>) -> Vec<(usize, f32)> {
    let keys = &binding.keys;
    if keys.len() < 2 {
        return Vec::new();
    }
    let mut limit = 1.0f32;
    if !binding.limits.is_empty() {
        limit = f32::INFINITY;
        for l in &binding.limits {
            let x = pose.get(&l.parameter).copied().unwrap_or(0.0);
            let pts = &l.points;
            let lw = if pts.is_empty() {
                1.0
            } else if x <= pts[0].value {
                pts[0].weight
            } else if x >= pts[pts.len() - 1].value {
                pts[pts.len() - 1].weight
            } else {
                let mut found = pts[pts.len() - 1].weight;
                for w in pts.windows(2) {
                    let (a, b) = (&w[0], &w[1]);
                    if x <= b.value {
                        let span = b.value - a.value;
                        found = if span <= 0.0 {
                            b.weight
                        } else {
                            a.weight + (b.weight - a.weight) * (x - a.value) / span
                        };
                        break;
                    }
                }
                found
            };
            if lw < limit {
                limit = lw;
            }
        }
    }
    if limit <= 0.0 {
        return Vec::new();
    }
    let x = pose.get(&binding.parameter).copied().unwrap_or(0.0);
    let mut lower = 0usize;
    while lower + 1 < keys.len() && keys[lower + 1] <= x {
        lower += 1;
    }
    let fraction = if x <= keys[0] || lower + 1 >= keys.len() {
        0.0
    } else {
        (x - keys[lower]) / (keys[lower + 1] - keys[lower]).max(1e-9)
    };
    let mut out = Vec::with_capacity(2);
    let candidates = [(lower, (1.0 - fraction) * limit), (lower + 1, fraction * limit)];
    for (k, w) in candidates {
        if k < keys.len() && (k as i32) != binding.neutral_index && w > 0.0 {
            out.push((k, w));
        }
    }
    out
}

fn merged_axes<S>(
    axes: &[KeyAxis],
    bindings: &[&BlendBinding<S>],
    ranges: &HashMap<String, (f32, f32)>,
) -> Vec<KeyAxis> {
    let mut order: Vec<String> = Vec::new();
    let mut map: HashMap<String, Vec<f32>> = HashMap::new();
    for a in axes {
        if !map.contains_key(&a.parameter) {
            order.push(a.parameter.clone());
        }
        map.entry(a.parameter.clone())
            .or_default()
            .extend_from_slice(&a.keys);
    }
    for b in bindings {
        if !map.contains_key(&b.parameter) {
            order.push(b.parameter.clone());
        }
        map.entry(b.parameter.clone())
            .or_default()
            .extend_from_slice(&b.keys);
        for l in &b.limits {
            if !map.contains_key(&l.parameter) {
                order.push(l.parameter.clone());
            }
            let entry = map.entry(l.parameter.clone()).or_default();
            for pt in &l.points {
                entry.push(pt.value);
            }
        }
    }

    order
        .into_iter()
        .map(|id| {
            let mut values = map.remove(&id).unwrap_or_default();
            let own = axes.iter().find(|a| a.parameter == id);
            let mut merged = own.map(|a| a.keys.clone()).unwrap_or_default();
            if own.is_none() {
                if let Some(&(min, max)) = ranges.get(&id) {
                    values.push(min);
                    values.push(max);
                }
            }
            values.sort_by(|a, b| a.total_cmp(b));
            for v in values {
                if merged.iter().all(|&m| (m - v).abs() >= EPS_KEY * 2.0) {
                    merged.push(v);
                }
            }
            merged.sort_by(|a, b| a.total_cmp(b));
            KeyAxis {
                parameter: id,
                keys: merged,
            }
        })
        .collect()
}

fn cell_count(axes: &[KeyAxis]) -> usize {
    axes.iter().fold(1usize, |n, a| n.saturating_mul(a.keys.len()))
}

fn build_cells<T, F>(
    axes: &[KeyAxis],
    defaults: &HashMap<String, f32>,
    mut form: F,
) -> Vec<KeyCell<T>>
where
    F: FnMut(&HashMap<String, f32>) -> T,
{
    let count = cell_count(axes);
    let mut out = Vec::with_capacity(count);
    let mut pose = defaults.clone();
    for linear in 0..count {
        let mut rest = linear;
        let mut coordinate = Vec::with_capacity(axes.len());
        for a in axes {
            let k = a.keys.len();
            let c = rest % k;
            rest /= k;
            coordinate.push(c as i32);
            pose.insert(a.parameter.clone(), a.keys[c]);
        }
        out.push(KeyCell {
            coordinate,
            form: form(&pose),
        });
    }
    out
}

fn added_mesh(
    bindings: &[&BlendBinding<MeshShape>],
    pose: &HashMap<String, f32>,
    rest: &[f32],
) -> Vec<f32> {
    let mut out = vec![0.0f32; rest.len()];
    for b in bindings {
        for (k, w) in weights(b, pose) {
            let Some(f) = b.shapes.get(k).and_then(|s| s.as_ref()) else {
                continue;
            };
            for i in 0..out.len() {
                let v = f.deltas.get(i).copied().unwrap_or(0.0);
                out[i] += w * (v - rest[i]);
            }
        }
    }
    out
}

fn added_scalar<F>(
    bindings: &[&BlendBinding<MeshShape>],
    pose: &HashMap<String, f32>,
    part: F,
    rest: f32,
) -> f32
where
    F: Fn(&MeshShape) -> f32,
{
    let mut out = 0.0f32;
    for b in bindings {
        for (k, w) in weights(b, pose) {
            let Some(f) = b.shapes.get(k).and_then(|s| s.as_ref()) else {
                continue;
            };
            out += w * (part(f) - rest);
        }
    }
    out
}

fn added_color<F>(
    bindings: &[&BlendBinding<MeshShape>],
    pose: &HashMap<String, f32>,
    part: F,
    rest: &Rgb,
) -> [f32; 3]
where
    F: Fn(&MeshShape) -> &Rgb,
{
    let mut out = [0.0f32; 3];
    for b in bindings {
        for (k, w) in weights(b, pose) {
            let Some(f) = b.shapes.get(k).and_then(|s| s.as_ref()) else {
                continue;
            };
            let c = part(f);
            out[0] += w * (c.r - rest.r);
            out[1] += w * (c.g - rest.g);
            out[2] += w * (c.b - rest.b);
        }
    }
    out
}

fn fold_drawable<C, U>(
    d: &Mesh,
    folded: &HashSet<String>,
    defaults: &HashMap<String, f32>,
    ranges: &HashMap<String, (f32, f32)>,
    part: Option<&str>,
    carrier_name: &mut C,
    mut unique: U,
) -> (Mesh, Vec<Deformer>)
where
    C: FnMut(bool, &str) -> String,
    U: FnMut(&str) -> String,
{
    let mut fold: Vec<&BlendBinding<MeshShape>> = Vec::new();
    let mut keep: Vec<BlendBinding<MeshShape>> = Vec::new();
    for b in &d.shapes {
        if folded.contains(&b.parameter) {
            fold.push(b);
        } else {
            keep.push(b.clone());
        }
    }
    let Some(mesh_geom) = &d.geometry else {
        let mut out = d.clone();
        out.shapes = keep;
        return (out, Vec::new());
    };
    let size = mesh_geom.positions.len();
    let rest_deltas = d
        .offsets
        .as_ref()
        .and_then(|g| eval_floats_grid(g, defaults, size))
        .unwrap_or_else(|| vec![0.0f32; size]);

    let mut head: Vec<&BlendBinding<MeshShape>> = Vec::new();
    let mut group_order: Vec<String> = Vec::new();
    let mut group_map: HashMap<String, Vec<&BlendBinding<MeshShape>>> = HashMap::new();
    for &b in &fold {
        if is_head_param(&b.parameter) {
            head.push(b);
        } else {
            if !group_map.contains_key(&b.parameter) {
                group_order.push(b.parameter.clone());
            }
            group_map.entry(b.parameter.clone()).or_default().push(b);
        }
    }
    let mut groups: Vec<Vec<&BlendBinding<MeshShape>>> = group_order
        .into_iter()
        .map(|k| group_map.remove(&k).unwrap())
        .collect();
    if !head.is_empty() {
        groups.push(head);
    }

    let own = d
        .offsets
        .as_ref()
        .map(|g| g.axes.clone())
        .unwrap_or_default();
    let mut channel_axes: Vec<KeyAxis> = Vec::new();
    let mut seen_ch_param: HashSet<String> = own.iter().map(|a| a.parameter.clone()).collect();
    for (_, g) in &d.channels {
        for a in &g.axes {
            if seen_ch_param.insert(a.parameter.clone()) {
                channel_axes.push(a.clone());
            }
        }
    }
    let mut own_plus_ch = own.clone();
    own_plus_ch.extend(channel_axes);

    let mut in_mesh: Vec<&BlendBinding<MeshShape>> = Vec::new();
    let mut to_warps: Vec<Vec<&BlendBinding<MeshShape>>> = Vec::new();

    for g in groups {
        let mut candidate = in_mesh.clone();
        candidate.extend(g.iter().copied());
        let axes = merged_axes(&own_plus_ch, &candidate, ranges);
        let cnt = cell_count(&axes);
        if cnt <= MESH_CELLS && cnt.saturating_mul(size) <= MESH_VALUES {
            in_mesh = candidate;
        } else {
            to_warps.push(g);
        }
    }

    let mut packed: Vec<Vec<&BlendBinding<MeshShape>>> = Vec::new();
    for g in to_warps {
        let g_has_head = g.iter().any(|b| is_head_param(&b.parameter));
        let mut placed = false;
        if !g_has_head {
            for p in &mut packed {
                if p.iter().any(|b| is_head_param(&b.parameter)) {
                    continue;
                }
                let mut cand = p.clone();
                cand.extend(g.iter().copied());
                if cell_count(&merged_axes(&[], &cand, ranges)) <= WARP_CELLS {
                    *p = cand;
                    placed = true;
                    break;
                }
            }
        }
        if !placed {
            packed.push(g);
        }
    }
    packed.sort_by_key(|p| {
        if p.iter().any(|b| is_head_param(&b.parameter)) {
            0
        } else {
            1
        }
    });

    let mut geometry = d.offsets.clone();
    if !in_mesh.is_empty() {
        let axes = merged_axes(&own, &in_mesh, ranges);
        let cells = build_cells(&axes, defaults, |pose| {
            let base = d
                .offsets
                .as_ref()
                .and_then(|g| eval_floats_grid(g, pose, size))
                .unwrap_or_else(|| vec![0.0f32; size]);
            let add = added_mesh(&in_mesh, pose, &rest_deltas);
            (0..size)
                .map(|i| base.get(i).copied().unwrap_or(0.0) + add[i])
                .collect::<Vec<f32>>()
        });
        geometry = Some(KeyGrid { axes, cells });
    }

    let channels = folded_channels(d, &fold, defaults, ranges);
    let mut drawable = d.clone();
    drawable.offsets = geometry;
    drawable.channels = channels;
    drawable.shapes = keep;

    if packed.is_empty() {
        return (drawable, Vec::new());
    }
    carried(
        drawable,
        &packed,
        &rest_deltas,
        defaults,
        ranges,
        part,
        carrier_name,
        &mut unique,
    )
}

fn folded_channels(
    d: &Mesh,
    fold: &[&BlendBinding<MeshShape>],
    defaults: &HashMap<String, f32>,
    ranges: &HashMap<String, (f32, f32)>,
) -> Channels {
    let mut channels = d.channels.clone();

    let mut fold_scalar = |ch: u32, static_val: f32, part: fn(&MeshShape) -> f32| {
        let rest = eval_scalar_channel(&channels, ch, static_val, defaults);
        let changed = fold.iter().any(|b| {
            b.shapes
                .iter()
                .any(|f| f.as_ref().map_or(false, |s| (part(s) - rest).abs() > 1e-3))
        });
        if !changed {
            return;
        }
        let own_axes = grid_ref(&channels, ch)
            .map(|g| g.axes.clone())
            .unwrap_or_default();
        let axes = merged_axes(&own_axes, fold, ranges);
        let before = grid_ref(&channels, ch).cloned();
        let cells = build_cells(&axes, defaults, |pose| {
            let base = eval_grid_scalar(before.as_ref(), static_val, pose);
            ChannelValue::Scalar(base + added_scalar(fold, pose, part, rest))
        });
        set_channel(&mut channels, ch, Some(KeyGrid { axes, cells }));
    };

    fold_scalar(CHANNEL_OPACITY, d.opacity, |s| s.opacity);
    fold_scalar(CHANNEL_DRAW_ORDER, d.draw_order, |s| s.draw_order);

    let mut fold_color = |ch: u32, static_val: &Rgb, part: fn(&MeshShape) -> &Rgb| {
        let rest = eval_color_channel(&channels, ch, static_val, defaults);
        let changed = fold.iter().any(|b| {
            b.shapes.iter().any(|f| {
                f.as_ref().map_or(false, |s| {
                    let c = part(s);
                    (c.r - rest.r).abs() > 1e-3
                        || (c.g - rest.g).abs() > 1e-3
                        || (c.b - rest.b).abs() > 1e-3
                })
            })
        });
        if !changed {
            return;
        }
        let own_axes = grid_ref(&channels, ch)
            .map(|g| g.axes.clone())
            .unwrap_or_default();
        let axes = merged_axes(&own_axes, fold, ranges);
        let before = grid_ref(&channels, ch).cloned();
        let cells = build_cells(&axes, defaults, |pose| {
            let base = eval_grid_color(before.as_ref(), static_val, pose);
            let add = added_color(fold, pose, part, &rest);
            ChannelValue::Color(Rgb {
                r: base.r + add[0],
                g: base.g + add[1],
                b: base.b + add[2],
            })
        });
        set_channel(&mut channels, ch, Some(KeyGrid { axes, cells }));
    };

    fold_color(CHANNEL_MULTIPLY_COLOR, &d.multiply, |s| &s.multiply);
    fold_color(CHANNEL_SCREEN_COLOR, &d.screen, |s| &s.screen);

    channels
}

fn carried<C, U>(
    mut d: Mesh,
    groups: &[Vec<&BlendBinding<MeshShape>>],
    rest_deltas: &[f32],
    defaults: &HashMap<String, f32>,
    ranges: &HashMap<String, (f32, f32)>,
    part: Option<&str>,
    carrier_name: &mut C,
    unique: &mut U,
) -> (Mesh, Vec<Deformer>)
where
    C: FnMut(bool, &str) -> String,
    U: FnMut(&str) -> String,
{
    let geom = d.geometry.as_ref().unwrap();
    let positions = &geom.positions;
    let n = positions.len() / 2;

    let own = d
        .offsets
        .as_ref()
        .map(|g| g.cells.as_slice())
        .unwrap_or(&[]);
    let stride = (own.len() / SAMPLED_CELLS).max(1);
    let mut samples: Vec<&[f32]> = Vec::with_capacity(1 + (own.len() + stride - 1) / stride);
    samples.push(rest_deltas);
    for (i, c) in own.iter().enumerate() {
        if i % stride == 0 {
            samples.push(&c.form);
        }
    }

    let mut min_x = f32::MAX;
    let mut min_y = f32::MAX;
    let mut max_x = -f32::MAX;
    let mut max_y = -f32::MAX;
    for deltas in &samples {
        for i in 0..n {
            let x = positions[i * 2] + deltas[i * 2];
            let y = positions[i * 2 + 1] + deltas[i * 2 + 1];
            min_x = min_x.min(x);
            max_x = max_x.max(x);
            min_y = min_y.min(y);
            max_y = max_y.max(y);
        }
    }
    let pad_x = (max_x - min_x).max(1e-4) * 0.15;
    let pad_y = (max_y - min_y).max(1e-4) * 0.15;
    min_x -= pad_x;
    max_x += pad_x;
    min_y -= pad_y;
    max_y += pad_y;

    let mut ox = 0.0f32;
    let mut oy = 0.0f32;
    let mut sx = 1.0f32;
    let mut sy = 1.0f32;
    let mut parent = d.parent.clone();
    let mut warps: Vec<Deformer> = Vec::with_capacity(groups.len());
    let points = LATTICE + 1;

    let total_samples = n * samples.len();
    let mut sample_u = Vec::with_capacity(total_samples);
    let mut sample_v = Vec::with_capacity(total_samples);
    for deltas in &samples {
        for i in 0..n {
            sample_u.push((positions[i * 2] + deltas[i * 2] - min_x) / (max_x - min_x));
            sample_v.push((positions[i * 2 + 1] + deltas[i * 2 + 1] - min_y) / (max_y - min_y));
        }
    }
    let fit = LatticeFit::new(&sample_u, &sample_v);

    for (index, group) in groups.iter().enumerate() {
        let left = (min_x - ox) / sx;
        let top = (min_y - oy) / sy;
        let width = (max_x - min_x) / sx;
        let height = (max_y - min_y) / sy;
        let axes = merged_axes(&[], group, ranges);
        let cells = build_cells(&axes, defaults, |pose| {
            let mv = added_mesh(group, pose, rest_deltas);
            let mut mx = Vec::with_capacity(total_samples);
            let mut my = Vec::with_capacity(total_samples);
            for _ in 0..samples.len() {
                for i in 0..n {
                    mx.push(mv[i * 2] / sx);
                    my.push(mv[i * 2 + 1] / sy);
                }
            }
            let dx = fit.solve(&mx);
            let dy = fit.solve(&my);
            let mut pts = Vec::with_capacity(points * points * 2);
            for j in 0..(points * points) {
                let col = j % points;
                let row = j / points;
                pts.push(left + (col as f32) / (LATTICE as f32) * width + dx[j]);
                pts.push(top + (row as f32) / (LATTICE as f32) * height + dy[j]);
            }
            pts
        });
        let head = group.iter().any(|b| is_head_param(&b.parameter));
        let base_id = format!("{}_{}", d.id, if head { "Turn".to_string() } else { format!("Shape{index}") });
        let id = unique(&base_id);
        let name = carrier_name(head, &d.name);
        warps.push(Deformer::Warp {
            id: id.clone(),
            name,
            parent: parent.clone(),
            part: part.map(String::from),
            rows: LATTICE as i32,
            columns: LATTICE as i32,
            bilinear: true,
            lattice: Some(KeyGrid { axes, cells }),
            channels: Vec::new(),
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
            selectable: true,
            visible: true,
            enabled: true,
            shapes: Vec::new(),
        });
        parent = Some(id);
        ox += left * sx;
        oy += top * sy;
        sx *= width;
        sy *= height;
    }

    let scaled = |deltas: &[f32]| -> Vec<f32> {
        deltas
            .iter()
            .enumerate()
            .map(|(i, &v)| v / if i % 2 == 0 { sx } else { sy })
            .collect()
    };

    let new_positions: Vec<f32> = positions
        .iter()
        .enumerate()
        .map(|(i, &v)| if i % 2 == 0 { (v - ox) / sx } else { (v - oy) / sy })
        .collect();
    d.parent = parent;
    d.geometry = Some(MeshGeometry {
        positions: new_positions,
        uvs: geom.uvs.clone(),
        indices: geom.indices.clone(),
    });
    d.offsets = d.offsets.as_ref().map(|g| KeyGrid {
        axes: g.axes.clone(),
        cells: g
            .cells
            .iter()
            .map(|c| KeyCell {
                coordinate: c.coordinate.clone(),
                form: scaled(&c.form),
            })
            .collect(),
    });
    d.shapes = d
        .shapes
        .iter()
        .map(|b| BlendBinding {
            parameter: b.parameter.clone(),
            keys: b.keys.clone(),
            neutral_index: b.neutral_index,
            limits: b.limits.clone(),
            shapes: b
                .shapes
                .iter()
                .map(|f| {
                    f.as_ref().map(|s| MeshShape {
                        deltas: scaled(&s.deltas),
                        draw_order: s.draw_order,
                        opacity: s.opacity,
                        multiply: s.multiply.clone(),
                        screen: s.screen.clone(),
                    })
                })
                .collect(),
        })
        .collect();

    (d, warps)
}

struct LatticeFit {
    m: usize,
    weights: Vec<[(usize, f32); 4]>,
    l: Vec<f64>,
}

impl LatticeFit {
    fn new(u: &[f32], v: &[f32]) -> Self {
        let points = LATTICE + 1;
        let m = points * points;
        let mut a = vec![0.0f64; m * m];
        let mut weights = Vec::with_capacity(u.len());

        for i in 0..u.len() {
            let gu = (u[i] * LATTICE as f32).clamp(0.0, LATTICE as f32 - 1e-4);
            let gv = (v[i] * LATTICE as f32).clamp(0.0, LATTICE as f32 - 1e-4);
            let c = gu as usize;
            let r = gv as usize;
            let fu = gu - c as f32;
            let fv = gv - r as f32;
            let p = r * points + c;
            let w = [
                (p, (1.0 - fu) * (1.0 - fv)),
                (p + 1, fu * (1.0 - fv)),
                (p + points, (1.0 - fu) * fv),
                (p + points + 1, fu * fv),
            ];
            for &(pi, wp) in &w {
                for &(qi, wq) in &w {
                    a[pi * m + qi] += (wp * wq) as f64;
                }
            }
            weights.push(w);
        }

        let smooth = 1e-3 * ((u.len() as f64) / (m as f64)).max(1.0);
        for r in 0..points {
            for c in 0..points {
                let p = r * points + c;
                let mut near = [0usize; 4];
                let mut ncount = 0usize;
                if c > 0 {
                    near[ncount] = p - 1;
                    ncount += 1;
                }
                if c < LATTICE {
                    near[ncount] = p + 1;
                    ncount += 1;
                }
                if r > 0 {
                    near[ncount] = p - points;
                    ncount += 1;
                }
                if r < LATTICE {
                    near[ncount] = p + points;
                    ncount += 1;
                }
                let mut row = vec![0.0f64; m];
                row[p] = 1.0;
                for &q in &near[..ncount] {
                    row[q] -= 1.0 / (ncount as f64);
                }
                for i in 0..m {
                    if row[i] != 0.0 {
                        for j in 0..m {
                            if row[j] != 0.0 {
                                a[i * m + j] += smooth * row[i] * row[j];
                            }
                        }
                    }
                }
            }
        }

        let mut l = vec![0.0f64; m * m];
        for i in 0..m {
            for j in 0..=i {
                let mut s = a[i * m + j];
                for k in 0..j {
                    s -= l[i * m + k] * l[j * m + k];
                }
                l[i * m + j] = if i == j {
                    s.max(1e-12).sqrt()
                } else {
                    s / l[j * m + j]
                };
            }
        }

        Self { m, weights, l }
    }

    fn solve(&self, target: &[f32]) -> Vec<f32> {
        let m = self.m;
        let mut b = vec![0.0f64; m];
        for (i, w) in self.weights.iter().enumerate() {
            let t = target[i] as f64;
            for &(p, wp) in w {
                b[p] += (wp as f64) * t;
            }
        }
        let mut y = vec![0.0f64; m];
        for i in 0..m {
            let mut s = b[i];
            for k in 0..i {
                s -= self.l[i * m + k] * y[k];
            }
            y[i] = s / self.l[i * m + i];
        }
        let mut x = vec![0.0f64; m];
        for i in (0..m).rev() {
            let mut s = y[i];
            for k in (i + 1)..m {
                s -= self.l[k * m + i] * x[k];
            }
            x[i] = s / self.l[i * m + i];
        }
        x.into_iter().map(|v| v as f32).collect()
    }
}

fn fold_warp(
    d: &Deformer,
    folded: &HashSet<String>,
    defaults: &HashMap<String, f32>,
    ranges: &HashMap<String, (f32, f32)>,
) -> Deformer {
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
    } = d
    else {
        return d.clone();
    };
    let mut fold: Vec<&BlendBinding<LatticeShape>> = Vec::new();
    let mut keep: Vec<BlendBinding<LatticeShape>> = Vec::new();
    for b in shapes {
        if folded.contains(&b.parameter) {
            fold.push(b);
        } else {
            keep.push(b.clone());
        }
    }
    let pt_len = ((*columns + 1) * (*rows + 1) * 2) as usize;
    let Some(rest) = lattice
        .as_ref()
        .and_then(|g| eval_floats_grid(g, defaults, pt_len))
    else {
        return Deformer::Warp {
            id: id.clone(),
            name: name.clone(),
            parent: parent.clone(),
            part: part.clone(),
            rows: *rows,
            columns: *columns,
            bilinear: *bilinear,
            lattice: lattice.clone(),
            channels: channels.clone(),
            opacity: *opacity,
            multiply: multiply.clone(),
            screen: screen.clone(),
            selectable: *selectable,
            visible: *visible,
            enabled: *enabled,
            shapes: keep,
        };
    };
    let own_axes = lattice.as_ref().map(|g| g.axes.clone()).unwrap_or_default();
    let axes = merged_axes(&own_axes, &fold, ranges);
    let cells = build_cells(&axes, defaults, |pose| {
        let mut out = lattice
            .as_ref()
            .and_then(|g| eval_floats_grid(g, pose, pt_len))
            .unwrap_or_else(|| rest.clone());
        for b in &fold {
            for (k, w) in weights(b, pose) {
                let Some(f) = b.shapes.get(k).and_then(|s| s.as_ref()) else {
                    continue;
                };
                for i in 0..out.len() {
                    out[i] += w * (f.points[i] - rest[i]);
                }
            }
        }
        out
    });

    Deformer::Warp {
        id: id.clone(),
        name: name.clone(),
        parent: parent.clone(),
        part: part.clone(),
        rows: *rows,
        columns: *columns,
        bilinear: *bilinear,
        lattice: Some(KeyGrid { axes, cells }),
        channels: channels.clone(),
        opacity: *opacity,
        multiply: multiply.clone(),
        screen: screen.clone(),
        selectable: *selectable,
        visible: *visible,
        enabled: *enabled,
        shapes: keep,
    }
}
