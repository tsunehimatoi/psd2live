use crate::rig_ir::{
    decode_object_frame, decode_object_mesh, decode_rig_binary, encode_object_mesh,
    objects_response, KeyAxis, KeyCell, KeyGrid, MeshGeometry, ObjectCache,
};
use crate::space::{collect_deformer_branch, geometry_residual};
use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};

fn parse_floats4(v: &Json, label: &str) -> Result<[f32; 4], String> {
    let arr = v
        .as_arr()
        .ok_or_else(|| format!("Invalid {label}: expected array"))?;
    if arr.len() != 4 {
        return Err(format!("Invalid {label}: expected 4 numbers"));
    }
    let mut out = [0.0f32; 4];
    for (i, item) in arr.iter().enumerate() {
        let f = item
            .as_f64()
            .ok_or_else(|| format!("Invalid {label}: non-number"))? as f32;
        if !f.is_finite() {
            return Err(format!("Invalid {label}: non-finite number"));
        }
        out[i] = f;
    }
    Ok(out)
}

fn iris_point(x: f32, y: f32, iris: [f32; 4], amount: f32) -> (f32, f32) {
    let scale_x = 1.0 - amount * iris[2] / 100.0;
    let scale_y = 1.0 + amount * iris[3] / 100.0;
    (
        iris[0] + (x - iris[0]) * scale_x,
        iris[1] + (y - iris[1]) * scale_y,
    )
}

fn build_squash_grid(
    positions: &[f32],
    frame: [f32; 4],
    iris: [f32; 4],
    crease: f32,
    jelly: &str,
    opening: &str,
    opens: Option<&[f32]>,
) -> KeyGrid<Vec<f32>> {
    let jellies = [-1.0f32, 0.0, 1.0];
    let mut axes = vec![KeyAxis {
        parameter: jelly.to_string(),
        keys: jellies.to_vec(),
    }];
    if let Some(op) = opens {
        axes.push(KeyAxis {
            parameter: opening.to_string(),
            keys: op.to_vec(),
        });
    }
    let bottom = positions
        .iter()
        .enumerate()
        .filter(|(i, _)| i % 2 == 1)
        .map(|(_, &v)| v)
        .fold(f32::NEG_INFINITY, f32::max);
    let width = (frame[2] - frame[0]).max(1e-4);
    let height = (frame[3] - frame[1]).max(1e-4);
    let open_vals: &[f32] = opens.unwrap_or(&[1.0]);
    let mut cells = Vec::with_capacity(open_vals.len() * jellies.len());
    for (o, &open) in open_vals.iter().enumerate() {
        for (j, &amount) in jellies.iter().enumerate() {
            let scale = if open < 1.0 {
                0.82 + 0.18 * (open / crease).clamp(0.0, 1.0)
            } else {
                1.0
            };
            let mut delta = vec![0.0f32; positions.len()];
            for i in (0..positions.len()).step_by(2) {
                let (x, y) = iris_point(positions[i], positions[i + 1], iris, amount);
                let posed_y = if opens.is_some() {
                    bottom + (y - bottom) * scale
                } else {
                    y
                };
                delta[i] = (x - positions[i]) / width;
                delta[i + 1] = (posed_y - positions[i + 1]) / height;
            }
            let coord = if opens.is_some() {
                vec![j as i32, o as i32]
            } else {
                vec![j as i32]
            };
            cells.push(KeyCell {
                coordinate: coord,
                form: delta,
            });
        }
    }
    KeyGrid { axes, cells }
}

pub fn handle_eye_squash(req: &Request, cache: &ObjectCache) -> Response {
    match handle_eye_squash_inner(req, cache) {
        Ok(resp) => resp,
        Err(msg) => Response::Error(msg),
    }
}

fn handle_eye_squash_inner(req: &Request, cache: &ObjectCache) -> Result<Response, String> {
    let values = req.context_values().ok_or("Missing eye squash context")?;
    if values.get("schema").and_then(Json::as_str) != Some("eye-squash/1") {
        return Err("Missing eye squash source inputs".into());
    }
    let targets_obj = values
        .get("targets")
        .and_then(Json::as_obj)
        .ok_or("Missing eye squash targets")?;
    let bytes = req
        .context_resource("source_meshes")
        .ok_or("Missing source_meshes resource")?;
    let raw_rig = decode_rig_binary(bytes).map_err(|e| format!("Invalid source_meshes: {e}"))?;
    let raw_meshes: HashMap<String, MeshGeometry> = raw_rig
        .meshes
        .into_iter()
        .filter_map(|m| m.geometry.map(|g| (m.id, g)))
        .collect();

    let config = req.config();
    let scaffold = config.get("scaffold").ok_or("Missing scaffold")?;
    let scaffold_id = scaffold.as_str().ok_or("Invalid scaffold")?;
    let base = config.get("base").ok_or("Missing base")?;
    let base_id = base.as_str().ok_or("Invalid base")?;

    let target_ids: Vec<String> = req
        .targets()
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(String::from))
        .collect();
    let target_set: HashSet<&str> = target_ids.iter().map(String::as_str).collect();
    for (k, _) in targets_obj {
        if !target_set.contains(k.as_str()) {
            return Err(format!("Unexpected eye squash target: {k}"));
        }
    }

    let upstream_json = req.context_upstream();
    let upstream = upstream_json
        .as_ref()
        .and_then(Json::as_arr)
        .unwrap_or(&[]);

    let has_cap = |cap_name: &str, inst_id: Option<&str>, req_targets: &[&str]| {
        upstream.iter().any(|u| {
            if u.get("capability").and_then(Json::as_str) != Some(cap_name) {
                return false;
            }
            if let Some(id) = inst_id {
                if u.get("instanceId").and_then(Json::as_str) != Some(id) {
                    return false;
                }
            }
            let u_targets: HashSet<&str> = u
                .get("targets")
                .and_then(Json::as_arr)
                .map(|arr| {
                    arr.iter()
                        .filter_map(|t| t.get("id").and_then(Json::as_str))
                        .collect()
                })
                .unwrap_or_default();
            req_targets.iter().all(|t| u_targets.contains(t))
        })
    };

    let target_refs: Vec<&str> = target_ids.iter().map(String::as_str).collect();
    if !has_cap("rig.scaffold", Some(scaffold_id), &target_refs) {
        return Err("Missing eye squash scaffold provider".into());
    }

    let frame = cache
        .get("frame")
        .and_then(|b| decode_object_frame(b).map_err(|e| e.to_string()))?;
    let live_defaults: HashMap<String, f32> = frame
        .parameters
        .iter()
        .map(|p| (p.id.clone(), p.default))
        .collect();

    let mut changed_blobs = HashMap::new();
    for tid in &target_ids {
        if !has_cap("eye/fitted", Some(base_id), &[tid.as_str()]) {
            return Err("Missing fitted eye provider".into());
        }
        let Some((_, entry)) = targets_obj.iter().find(|(k, _)| k == tid) else {
            continue;
        };

        let applies = |cap_name: &str| {
            upstream.iter().any(|u| {
                u.get("capability").and_then(Json::as_str) == Some(cap_name)
                    && u.get("config").and_then(|c| c.get("scaffold")) == Some(scaffold)
                    && u.get("config").and_then(|c| c.get("base")) == Some(base)
                    && u.get("targets")
                        .and_then(Json::as_arr)
                        .is_some_and(|arr| arr.iter().any(|t| t.get("id").and_then(Json::as_str) == Some(tid.as_str())))
            })
        };

        if applies("eye.squash") || applies("psd2live.native.core/eye-squash") {
            continue;
        }
        let wide = applies("eye.wide");

        let mut mesh = cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))?;

        let head_turn = upstream.iter().any(|u| {
            let cap = u.get("capability").and_then(Json::as_str);
            (cap == Some("face/fitted") || cap == Some("face.depth-turn"))
                && u.get("config").and_then(|c| c.get("scaffold")) == Some(scaffold)
                && u.get("targets")
                    .and_then(Json::as_arr)
                    .is_some_and(|arr| arr.iter().any(|t| t.get("id").and_then(Json::as_str) == Some(tid.as_str())))
        });
        let head_axes = ["ParamAngleX", "ParamAngleY", "ParamAngleZ"];
        if head_turn
            && mesh
                .offsets
                .as_ref()
                .is_some_and(|g| g.axes.iter().any(|a| head_axes.contains(&a.parameter.as_str())))
        {
            return Err("Eye squash cannot lower fitted head-turn carriers".into());
        }

        let side_str = entry
            .get("side")
            .and_then(Json::as_str)
            .ok_or("Missing eye squash side")?;
        let opening = match side_str {
            "LEFT" => "ParamEyeLOpen",
            "RIGHT" => "ParamEyeROpen",
            _ => return Err(format!("Invalid eye squash side: {side_str}")),
        };
        let crease = entry
            .get("crease")
            .and_then(Json::as_f64)
            .ok_or("Missing eye squash crease")? as f32;
        let parent = entry.get("parent").and_then(Json::as_str).map(String::from);
        let bounds = parse_floats4(entry.get("frame").ok_or("Missing frame")?, "frame")?;
        let iris = parse_floats4(entry.get("iris").ok_or("Missing iris")?, "iris")?;
        let raw = raw_meshes
            .get(tid)
            .ok_or_else(|| format!("Missing source mesh {tid}"))?;

        let geom = mesh.geometry.as_ref().ok_or("Missing mesh geometry")?;
        if geom.uvs != raw.uvs || geom.indices != raw.indices {
            return Err("Eye squash requires the captured iris topology".into());
        }
        let jelly = "ParamEyeBallForm";
        if !live_defaults.contains_key(opening) || !live_defaults.contains_key(jelly) {
            return Err("Missing eye squash parameters".into());
        }

        let before = build_squash_grid(&raw.positions, bounds, iris, crease, jelly, opening, None);
        let opens: &[f32] = if wide {
            &[0.0, crease, 1.0, 2.0]
        } else {
            &[0.0, crease, 1.0]
        };
        let after = build_squash_grid(
            &raw.positions,
            bounds,
            iris,
            crease,
            jelly,
            opening,
            Some(opens),
        );

        let width = (bounds[2] - bounds[0]).max(1e-4);
        let height = (bounds[3] - bounds[1]).max(1e-4);
        let norm_positions: Vec<f32> = raw
            .positions
            .iter()
            .enumerate()
            .map(|(i, &v)| {
                if parent.is_none() {
                    v
                } else if i % 2 == 0 {
                    (v - bounds[0]) / width
                } else {
                    (v - bounds[1]) / height
                }
            })
            .collect();

        let next_offsets = if mesh.parent == parent
            && geom.positions == norm_positions
            && mesh.offsets.as_ref() == Some(&before)
            && mesh.shapes.is_empty()
        {
            Some(after)
        } else {
            let live_branch = collect_deformer_branch(mesh.parent.as_deref(), cache)?;
            let canonical_branch = collect_deformer_branch(parent.as_deref(), cache)?;
            let mut canonical_mesh = mesh.clone();
            canonical_mesh.parent = parent;
            if let Some(ref mut g) = canonical_mesh.geometry {
                g.positions = norm_positions;
            }
            canonical_mesh.offsets = None;
            canonical_mesh.shapes.clear();
            canonical_mesh.channels.clear();
            geometry_residual(
                &mesh,
                &live_branch,
                &live_defaults,
                &canonical_mesh,
                &canonical_branch,
                &before,
                &after,
            )?
        };

        if mesh.offsets != next_offsets {
            mesh.offsets = next_offsets;
            changed_blobs.insert(format!("mesh:{}", mesh.id), encode_object_mesh(&mesh));
        }
    }

    Ok(objects_response(changed_blobs, Vec::new(), Vec::new()))
}
