use crate::rig_ir::{
    BlendBinding, KeyAxis, KeyCell, KeyGrid, Mesh, MeshShape, ObjectCache, ParameterNode,
    PhysicsGroup, PhysicsInput, PhysicsNormalization, PhysicsOutput, PhysicsSegment,
    ascii_stem, decode_object_frame, decode_object_mesh, encode_object_frame, encode_object_mesh,
    eval_color_channel, eval_floats_grid, eval_scalar_channel, objects_response,
    sync_parameters_from_tree, with_parameter_created,
};
use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};

const MODE_RANGE: f32 = 30.0;
const AUTO_BLEND_CELLS: usize = 64;
const MAX_CELLS: usize = 200_000;
const CHANNEL_DRAW_ORDER: u32 = 0;
const CHANNEL_OPACITY: u32 = 1;
const CHANNEL_MULTIPLY_COLOR: u32 = 2;
const CHANNEL_SCREEN_COLOR: u32 = 3;

#[derive(Clone)]
struct BakedAxis {
    parameter: String,
    keys: Vec<f32>,
    offsets: HashMap<String, Vec<Vec<f32>>>,
}

impl BakedAxis {
    fn from_json(obj: &Json) -> Result<Self, String> {
        let parameter = obj
            .get("parameter")
            .and_then(Json::as_str)
            .ok_or("Baked axis missing parameter")?
            .to_string();
        let keys: Vec<f32> = obj
            .get("keys")
            .and_then(Json::as_arr)
            .ok_or("Baked axis missing keys")?
            .iter()
            .filter_map(|v| v.as_f64().map(|x| x as f32))
            .collect();
        if keys.len() < 2 {
            return Err("Baked axis needs at least 2 keys".into());
        }
        let mut offsets = HashMap::new();
        if let Some(map) = obj.get("offsets").and_then(Json::as_obj) {
            for (mesh, per) in map {
                let Some(arr) = per.as_arr() else { continue };
                let mut forms = Vec::with_capacity(arr.len());
                for f in arr {
                    let Some(fa) = f.as_arr() else { continue };
                    forms.push(fa.iter().filter_map(|v| v.as_f64().map(|x| x as f32)).collect());
                }
                offsets.insert(mesh.clone(), forms);
            }
        }
        Ok(Self { parameter, keys, offsets })
    }

    fn at(&self, mesh: &str, value: f32) -> Option<Vec<f32>> {
        let per = self.offsets.get(mesh)?;
        if per.is_empty() {
            return None;
        }
        if value <= self.keys[0] {
            return Some(per[0].clone());
        }
        let last = self.keys.len() - 1;
        if value >= self.keys[last] {
            return Some(per[last].clone());
        }
        let upper = self.keys.iter().position(|&k| k >= value)?;
        let lower = upper - 1;
        let t = (value - self.keys[lower]) / (self.keys[upper] - self.keys[lower]);
        let a = &per[lower];
        let b = &per[upper];
        Some((0..a.len()).map(|i| a[i] + (b[i] - a[i]) * t).collect())
    }
}

struct SimOutput {
    id: String,
    name: Option<String>,
    range: f32,
    gain: f32,
}

fn physics_source_ordinal(s: &str) -> u32 {
    match s.to_ascii_lowercase().as_str() {
        "x" => 0,
        "angle" => 1,
        "y" => 2,
        _ => 0,
    }
}

pub(crate) fn parse_physics_group(obj: &Json) -> Option<PhysicsGroup> {
    let id = obj.get("id")?.as_str()?.to_string();
    let name = obj.get("name").and_then(Json::as_str).unwrap_or(&id).to_string();
    let inputs = obj
        .get("inputs")?
        .as_arr()?
        .iter()
        .filter_map(|i| {
            let src = i.get("type").or_else(|| i.get("source")).and_then(Json::as_str).unwrap_or("angle");
            Some(PhysicsInput {
                parameter: i.get("parameter")?.as_str()?.to_string(),
                weight: i.get("weight").and_then(Json::as_f64).unwrap_or(100.0) as f32,
                source: physics_source_ordinal(src),
                reflect: i.get("reflect").and_then(Json::as_bool).unwrap_or(false),
            })
        })
        .collect();
    let outputs = obj
        .get("outputs")?
        .as_arr()?
        .iter()
        .filter_map(|o| {
            let src = o.get("type").or_else(|| o.get("source")).and_then(Json::as_str).unwrap_or("angle");
            Some(PhysicsOutput {
                parameter: o.get("parameter")?.as_str()?.to_string(),
                vertex: o.get("vertex").and_then(Json::as_i64).unwrap_or(1) as i32,
                scale: o.get("scale").and_then(Json::as_f64).unwrap_or(1.0) as f32,
                weight: o.get("weight").and_then(Json::as_f64).unwrap_or(100.0) as f32,
                source: physics_source_ordinal(src),
                reflect: o.get("reflect").and_then(Json::as_bool).unwrap_or(false),
            })
        })
        .collect();
    let segments = obj
        .get("segments")?
        .as_arr()?
        .iter()
        .filter_map(|s| {
            Some(PhysicsSegment {
                length: s.get("length").and_then(Json::as_f64).unwrap_or(10.0) as f32,
                mobility: s.get("mobility").and_then(Json::as_f64).unwrap_or(0.9) as f32,
                delay: s.get("delay").and_then(Json::as_f64).unwrap_or(0.9) as f32,
                acceleration: s.get("acceleration").and_then(Json::as_f64).unwrap_or(1.2) as f32,
            })
        })
        .collect();
    let norm = obj.get("normalization");
    let pos = norm.and_then(|n| n.get("position"));
    let ang = norm.and_then(|n| n.get("angle"));
    let normalization = PhysicsNormalization {
        position_min: pos.and_then(|p| p.get("min")).or_else(|| norm.and_then(|n| n.get("position_min"))).and_then(Json::as_f64).unwrap_or(-10.0) as f32,
        position_default: pos.and_then(|p| p.get("default")).or_else(|| norm.and_then(|n| n.get("position_default"))).and_then(Json::as_f64).unwrap_or(0.0) as f32,
        position_max: pos.and_then(|p| p.get("max")).or_else(|| norm.and_then(|n| n.get("position_max"))).and_then(Json::as_f64).unwrap_or(10.0) as f32,
        angle_min: ang.and_then(|a| a.get("min")).or_else(|| norm.and_then(|n| n.get("angle_min"))).and_then(Json::as_f64).unwrap_or(-10.0) as f32,
        angle_default: ang.and_then(|a| a.get("default")).or_else(|| norm.and_then(|n| n.get("angle_default"))).and_then(Json::as_f64).unwrap_or(0.0) as f32,
        angle_max: ang.and_then(|a| a.get("max")).or_else(|| norm.and_then(|n| n.get("angle_max"))).and_then(Json::as_f64).unwrap_or(10.0) as f32,
    };
    Some(PhysicsGroup { id, name, inputs, outputs, segments, normalization })
}

fn with_physics_group_tree(nodes: Vec<ParameterNode>, ids: &[String]) -> (Vec<ParameterNode>, bool) {
    let moved: HashSet<&str> = ids.iter().map(String::as_str).collect();
    let mut placed = false;
    fn walk(
        list: Vec<ParameterNode>,
        moved: &HashSet<&str>,
        ids: &[String],
        placed: &mut bool,
    ) -> Vec<ParameterNode> {
        let mut out = Vec::with_capacity(list.len());
        for node in list {
            match node {
                ParameterNode::Param(id) => {
                    if !moved.contains(id.as_str()) {
                        out.push(ParameterNode::Param(id));
                    }
                }
                ParameterNode::Group { id, name, label, open, children } => {
                    let mut next_children = walk(children, moved, ids, placed);
                    if id == "ParamGroupPhysics" && !*placed {
                        *placed = true;
                        let mut prefixed: Vec<ParameterNode> = ids.iter().cloned().map(ParameterNode::Param).collect();
                        prefixed.append(&mut next_children);
                        next_children = prefixed;
                    }
                    out.push(ParameterNode::Group { id, name, label, open, children: next_children });
                }
            }
        }
        out
    }
    let next = walk(nodes, &moved, ids, &mut placed);
    (next, placed)
}

fn with_key_inserted(grid: KeyGrid<Vec<f32>>, axis_idx: usize, key: f32) -> KeyGrid<Vec<f32>> {
    let existing = &grid.axes[axis_idx].keys;
    if existing.iter().any(|&k| k == key) {
        return grid;
    }
    let insert_at = existing.iter().position(|&k| k > key).unwrap_or(existing.len());
    let mut new_keys = existing.clone();
    new_keys.insert(insert_at, key);

    let mut axes = grid.axes.clone();
    axes[axis_idx].keys = new_keys;

    let mut cell_map: HashMap<Vec<i32>, &Vec<f32>> = HashMap::with_capacity(grid.cells.len());
    for c in &grid.cells {
        cell_map.insert(c.coordinate.clone(), &c.form);
    }

    let mut new_cells = Vec::with_capacity(grid.cells.len() + grid.cells.len() / existing.len().max(1));
    for c in &grid.cells {
        let mut coord = c.coordinate.clone();
        if coord[axis_idx] >= insert_at as i32 {
            coord[axis_idx] += 1;
        }
        new_cells.push(KeyCell { coordinate: coord, form: c.form.clone() });
    }

    let len = grid.cells.first().map(|c| c.form.len()).unwrap_or(0);
    for c in &grid.cells {
        if c.coordinate[axis_idx] != 0 {
            continue;
        }
        let mut coord = c.coordinate.clone();
        coord[axis_idx] = insert_at as i32;
        let form = if insert_at == 0 {
            let mut ref_coord = c.coordinate.clone();
            ref_coord[axis_idx] = 0;
            cell_map[&ref_coord].clone()
        } else if insert_at == existing.len() {
            let mut ref_coord = c.coordinate.clone();
            ref_coord[axis_idx] = (existing.len() - 1) as i32;
            cell_map[&ref_coord].clone()
        } else {
            let mut lo_coord = c.coordinate.clone();
            lo_coord[axis_idx] = (insert_at - 1) as i32;
            let mut hi_coord = c.coordinate.clone();
            hi_coord[axis_idx] = insert_at as i32;
            let a = cell_map[&lo_coord];
            let b = cell_map[&hi_coord];
            let k0 = existing[insert_at - 1];
            let k1 = existing[insert_at];
            let t = (key - k0) / (k1 - k0);
            (0..len).map(|i| a[i] + (b[i] - a[i]) * t).collect()
        };
        new_cells.push(KeyCell { coordinate: coord, form });
    }
    KeyGrid { axes, cells: new_cells }
}

fn with_offsets(mut grid: KeyGrid<Vec<f32>>, param_id: &str, axis: &BakedAxis, mesh: &str) -> Option<KeyGrid<Vec<f32>>> {
    let expected: usize = grid.axes.iter().map(|a| a.keys.len()).product();
    if grid.cells.len() != expected || grid.cells.is_empty() {
        return None;
    }
    let count = grid.cells[0].form.len();
    let mut index = grid.axes.iter().position(|a| a.parameter == param_id);
    if index.is_none() {
        let mut cells = Vec::with_capacity(grid.cells.len() * axis.keys.len());
        for k in 0..axis.keys.len() {
            for cell in &grid.cells {
                let mut coord = cell.coordinate.clone();
                coord.push(k as i32);
                cells.push(KeyCell { coordinate: coord, form: cell.form.clone() });
            }
        }
        grid.axes.push(KeyAxis { parameter: param_id.to_string(), keys: axis.keys.clone() });
        grid.cells = cells;
        index = Some(grid.axes.len() - 1);
    } else {
        let ai = index.unwrap();
        for &k in &axis.keys {
            grid = with_key_inserted(grid, ai, k);
        }
    }
    let ai = index.unwrap();
    let keys = &grid.axes[ai].keys;
    let mut out_cells = Vec::with_capacity(grid.cells.len());
    for cell in &grid.cells {
        let add = axis.at(mesh, keys[cell.coordinate[ai] as usize])?;
        if add.len() != count {
            return None;
        }
        let form = (0..count).map(|i| cell.form[i] + add[i]).collect();
        out_cells.push(KeyCell { coordinate: cell.coordinate.clone(), form });
    }
    Some(KeyGrid { axes: grid.axes, cells: out_cells })
}

pub fn handle_simulation(
    req: &Request,
    cache: &ObjectCache,
    output_id: fn(&Request, &str) -> String,
) -> Response {
    let targets = req.targets();
    if !targets.iter().all(|t| t.get("kind").and_then(Json::as_str) == Some("mesh")) {
        return Response::Error("A simulation modifier targets meshes".into());
    }
    let target_ids: Vec<String> = targets
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(str::to_string))
        .collect();
    let config = req.config();
    let sim_id = config.get("id").and_then(Json::as_str).unwrap_or("").to_string();
    let sim_name = config.get("name").and_then(Json::as_str).unwrap_or(&sim_id).to_string();
    let enabled = config.get("enabled").and_then(Json::as_bool).unwrap_or(true);
    let Some(bake) = config.get("bake") else {
        return Response::Error("A simulation modifier needs an enabled, valid bake".into());
    };
    if !enabled {
        return Response::Error("A simulation modifier needs an enabled, valid bake".into());
    }
    let cfg_targets: Vec<String> = config
        .get("targets")
        .and_then(Json::as_arr)
        .map(|a| a.iter().filter_map(Json::as_str).map(str::to_string).collect())
        .unwrap_or_default();
    if cfg_targets != target_ids {
        return Response::Error("Simulation targets changed; bake again before adding the modifier".into());
    }

    let mut frame = match cache.get("frame").and_then(|b| decode_object_frame(b).map_err(|e| e.to_string())) {
        Ok(f) => f,
        Err(e) => return Response::Error(e),
    };
    let mesh_order: Vec<String> = req
        .mesh_order()
        .iter()
        .filter_map(|v| v.as_str().map(str::to_string))
        .collect();
    let deformer_order: Vec<String> = req
        .header
        .get("deformer_order")
        .and_then(Json::as_arr)
        .map(|a| a.iter().filter_map(Json::as_str).map(str::to_string).collect())
        .unwrap_or_default();

    let mut meshes: HashMap<String, Mesh> = HashMap::new();
    for id in &mesh_order {
        if let Ok(bytes) = cache.get(&format!("mesh:{id}")) {
            if let Ok(m) = decode_object_mesh(bytes) {
                meshes.insert(id.clone(), m);
            }
        }
    }

    let statics: Vec<BakedAxis> = match bake
        .get("statics")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .map(BakedAxis::from_json)
        .collect::<Result<Vec<_>, _>>()
    {
        Ok(v) => v,
        Err(e) => return Response::Error(e),
    };
    let raw_modes = bake.get("modes").and_then(Json::as_arr).unwrap_or(&[]);
    let mut mode_axes = Vec::with_capacity(raw_modes.len());
    for m in raw_modes {
        let Some(ax) = m.get("axis") else {
            return Response::Error("Simulation mode missing axis".into());
        };
        match BakedAxis::from_json(ax) {
            Ok(parsed) => mode_axes.push(parsed),
            Err(e) => return Response::Error(e),
        }
    }

    let bake_params: Vec<String> = mode_axes.iter().map(|a| a.parameter.clone()).collect();
    let exaggeration = config.get("exaggeration").and_then(Json::as_f64).unwrap_or(1.3);
    let prev_outputs = config.get("outputs").and_then(Json::as_obj);
    let output_names = config.get("output_names").and_then(Json::as_obj);
    let mut outputs: HashMap<String, SimOutput> = HashMap::new();
    for p in &bake_params {
        let prev = prev_outputs.and_then(|m| m.iter().find(|(k, _)| k == p).map(|(_, v)| v));
        outputs.insert(
            p.clone(),
            SimOutput {
                id: output_id(req, &format!("simulation:{p}")),
                name: prev.and_then(|v| v.get("name")).and_then(Json::as_str).map(str::to_string),
                range: prev.and_then(|v| v.get("range")).and_then(Json::as_f64).unwrap_or(MODE_RANGE as f64) as f32,
                gain: prev.and_then(|v| v.get("gain")).and_then(Json::as_f64).unwrap_or(exaggeration) as f32,
            },
        );
    }

    let own_ids: HashSet<String> = bake_params
        .iter()
        .map(|p| outputs.get(p).map(|o| o.id.clone()).unwrap_or_else(|| p.clone()))
        .collect();
    let blend = if let Some(b) = config.get("blend_shapes").and_then(Json::as_bool) {
        b
    } else {
        let added: usize = mode_axes.iter().map(|a| a.keys.len()).product();
        target_ids.iter().any(|t| {
            let cells: usize = meshes
                .get(t)
                .and_then(|m| m.offsets.as_ref())
                .map(|g| {
                    g.axes
                        .iter()
                        .filter(|a| !own_ids.contains(&a.parameter))
                        .map(|a| a.keys.len())
                        .product()
                })
                .unwrap_or(1);
            cells * added > AUTO_BLEND_CELLS
        })
    };

    let vertical_id = format!("ParamSim{}_Y", ascii_stem(&sim_id));
    let sideways = mode_axes.iter().filter(|a| a.parameter != vertical_id).count();

    let mut written_modes = Vec::with_capacity(mode_axes.len());
    let mut created_ids = Vec::new();
    for (k, axis) in mode_axes.iter().enumerate() {
        let baked = &axis.parameter;
        let out = outputs.get(baked);
        let out_id = out.map(|o| o.id.clone()).unwrap_or_else(|| baked.clone());
        let range = out.map(|o| o.range).unwrap_or(MODE_RANGE);
        let gain = out.map(|o| o.gain).unwrap_or(1.0);
        let stretch = range / MODE_RANGE;
        let keys: Vec<f32> = axis.keys.iter().map(|&v| v * stretch).collect();
        let offsets = if gain == 1.0 {
            axis.offsets.clone()
        } else {
            axis.offsets
                .iter()
                .map(|(m, per)| {
                    (
                        m.clone(),
                        per.iter()
                            .map(|f| f.iter().map(|&x| x * gain).collect())
                            .collect(),
                    )
                })
                .collect()
        };
        if !frame.parameters.iter().any(|p| p.id == out_id) {
            let name = out
                .and_then(|o| o.name.clone())
                .or_else(|| {
                    output_names
                        .and_then(|m| m.iter().find(|(k, _)| k == baked).map(|(_, v)| v))
                        .and_then(Json::as_str)
                        .map(str::to_string)
                })
                .unwrap_or_else(|| {
                    if baked == &vertical_id {
                        format!("{sim_name} Y")
                    } else if sideways == 1 {
                        sim_name.clone()
                    } else {
                        format!("{sim_name} {}", k + 1)
                    }
                });
            with_parameter_created(&mut frame, &out_id, &name, blend);
            if let Some(p) = frame.parameters.iter_mut().find(|p| p.id == out_id) {
                p.min = keys[0];
                p.max = keys[keys.len() - 1];
                p.default = 0.0;
                if blend {
                    p.keys = Some(keys.clone());
                }
            }
            created_ids.push(out_id.clone());
        }
        written_modes.push(BakedAxis {
            parameter: out_id,
            keys,
            offsets,
        });
    }

    if !created_ids.is_empty() {
        let (next_tree, placed) = with_physics_group_tree(frame.parameter_tree.clone(), &created_ids);
        if placed {
            frame.parameter_tree = next_tree;
        }
    }

    let vertex_counts: HashMap<String, usize> = bake
        .get("vertex_counts")
        .and_then(Json::as_obj)
        .map(|m| {
            m.iter()
                .filter_map(|(k, v)| v.as_i64().map(|n| (k.clone(), n as usize)))
                .collect()
        })
        .unwrap_or_default();

    let mut changed_meshes: HashSet<String> = HashSet::new();
    let defaults: HashMap<String, f32> = frame.parameters.iter().map(|p| (p.id.clone(), p.default)).collect();

    for (idx, axis) in statics.iter().chain(written_modes.iter()).enumerate() {
        let is_mode = idx >= statics.len();
        let as_blend = blend && is_mode;
        let Some(param) = frame.parameters.iter().find(|p| p.id == axis.parameter).cloned() else {
            return Response::Error(format!("{sim_id}: parameter {} no longer exists", axis.parameter));
        };
        if param.blend != as_blend {
            return Response::Error(format!(
                "{sim_id}: {} is not a {} parameter",
                axis.parameter,
                if as_blend { "blend shape" } else { "normal" }
            ));
        }
        for (mesh_id, &count) in &vertex_counts {
            let Some(offsets) = axis.offsets.get(mesh_id) else { continue };
            let Some(mesh) = meshes.get_mut(mesh_id) else {
                return Response::Error(format!("{sim_id}: mesh {mesh_id} not found"));
            };
            let vcount = mesh.geometry.as_ref().map(|g| g.positions.len() / 2).unwrap_or(0);
            if vcount != count || offsets.iter().any(|f| f.len() != count * 2) {
                return Response::Error(format!("{sim_id}: {mesh_id} was remeshed since the bake"));
            }
            if as_blend {
                let reference = mesh
                    .offsets
                    .as_ref()
                    .and_then(|g| eval_floats_grid(g, &defaults, count * 2))
                    .unwrap_or_else(|| vec![0.0; count * 2]);
                let draw_order = eval_scalar_channel(&mesh.channels, CHANNEL_DRAW_ORDER, mesh.draw_order, &defaults);
                let opacity = eval_scalar_channel(&mesh.channels, CHANNEL_OPACITY, mesh.opacity, &defaults);
                let multiply = eval_color_channel(&mesh.channels, CHANNEL_MULTIPLY_COLOR, &mesh.multiply, &defaults);
                let screen = eval_color_channel(&mesh.channels, CHANNEL_SCREEN_COLOR, &mesh.screen, &defaults);
                let neutral = axis.keys.iter().position(|&v| v == 0.0).map(|i| i as i32).unwrap_or(-1);
                let shapes = axis
                    .keys
                    .iter()
                    .enumerate()
                    .map(|(k, _)| {
                        if k as i32 == neutral {
                            None
                        } else {
                            let add = &offsets[k];
                            let deltas = (0..count * 2).map(|i| reference[i] + add[i]).collect();
                            Some(MeshShape {
                                deltas,
                                draw_order,
                                opacity,
                                multiply: multiply.clone(),
                                screen: screen.clone(),
                            })
                        }
                    })
                    .collect();
                mesh.shapes.retain(|b| b.parameter != param.id);
                mesh.shapes.push(BlendBinding {
                    parameter: param.id.clone(),
                    keys: axis.keys.clone(),
                    neutral_index: neutral,
                    shapes,
                    limits: Vec::new(),
                });
            } else {
                let base_grid = mesh.offsets.clone().unwrap_or_else(|| KeyGrid {
                    axes: vec![],
                    cells: vec![KeyCell { coordinate: vec![], form: vec![0.0; count * 2] }],
                });
                let Some(next_grid) = with_offsets(base_grid, &param.id, axis, mesh_id) else {
                    return Response::Error(format!("{sim_id}: {mesh_id} keyforms cannot take {}", axis.parameter));
                };
                if next_grid.cells.len() > MAX_CELLS {
                    return Response::Error(format!("{sim_id}: {mesh_id} keyforms cannot take {}", axis.parameter));
                }
                mesh.offsets = Some(next_grid);
            }
            changed_meshes.insert(mesh_id.clone());
        }
        if let Some(p) = frame.parameters.iter_mut().find(|p| p.id == param.id) {
            if let Some(ref authored) = p.keys {
                let mut merged = authored.clone();
                merged.extend_from_slice(&axis.keys);
                merged.sort_by(f32::total_cmp);
                merged.dedup();
                p.keys = Some(merged);
            }
        }
    }

    // Physics groups
    let available: HashSet<&str> = frame.parameters.iter().map(|p| p.id.as_str()).collect();
    let mut raw_pendulums = Vec::new();
    if let Some(p) = bake.get("physics") {
        if let Some(g) = parse_physics_group(p) {
            raw_pendulums.push(g);
        }
    }
    if let Some(arr) = bake.get("extra_physics").and_then(Json::as_arr) {
        for p in arr {
            if let Some(g) = parse_physics_group(p) {
                raw_pendulums.push(g);
            }
        }
    }
    for (index, mut rule) in raw_pendulums.into_iter().enumerate() {
        if let Some(name) = output_names
            .and_then(|m| m.iter().find(|(k, _)| k == &rule.id).map(|(_, v)| v))
            .and_then(Json::as_str)
        {
            rule.name = name.to_string();
        }
        for o in &mut rule.outputs {
            let range = outputs.get(&o.parameter).map(|x| x.range).unwrap_or(MODE_RANGE);
            let out_id = outputs.get(&o.parameter).map(|x| x.id.clone()).unwrap_or_else(|| o.parameter.clone());
            o.parameter = out_id;
            o.scale = o.scale * range / MODE_RANGE;
        }
        rule.inputs.retain(|i| available.contains(i.parameter.as_str()));
        rule.outputs.retain(|o| available.contains(o.parameter.as_str()));
        if rule.inputs.is_empty() || rule.outputs.is_empty() {
            continue;
        }
        rule.id = output_id(req, &format!("physics:{index}"));
        if frame.physics.groups.iter().any(|g| g.id == rule.id) {
            return Response::Error(format!("Duplicate physics group {}", rule.id));
        }
        frame.physics.groups.push(rule);
    }

    sync_parameters_from_tree(&mut frame);

    let mut changed_blobs = HashMap::with_capacity(changed_meshes.len() + 1);
    for id in changed_meshes {
        if let Some(mesh) = meshes.get(&id) {
            changed_blobs.insert(format!("mesh:{id}"), encode_object_mesh(mesh));
        }
    }
    changed_blobs.insert("frame".into(), encode_object_frame(&frame));
    objects_response(changed_blobs, mesh_order, deformer_order)
}
