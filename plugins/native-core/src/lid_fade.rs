use crate::rig_ir::{
    decode_object_frame, decode_object_mesh, encode_object_mesh, eval_scalar_channel, grid_ref,
    objects_response, residual_channel_grid, set_channel, ChannelValue, KeyAxis, KeyCell, KeyGrid,
    ObjectCache, CHANNEL_OPACITY,
};
use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};

struct UpstreamCap<'a> {
    capability: &'a str,
    instance_id: &'a str,
    scaffold: Option<&'a Json>,
    targets: HashSet<&'a str>,
}

fn parse_upstream(json: Option<&Json>) -> Vec<UpstreamCap<'_>> {
    json.and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(|u| {
            let capability = u.get("capability").and_then(Json::as_str)?;
            let instance_id = u.get("instanceId").and_then(Json::as_str)?;
            let scaffold = u.get("config").and_then(|c| c.get("scaffold"));
            let targets = u
                .get("targets")
                .and_then(Json::as_arr)
                .unwrap_or(&[])
                .iter()
                .filter_map(|t| t.get("id").and_then(Json::as_str))
                .collect();
            Some(UpstreamCap { capability, instance_id, scaffold, targets })
        })
        .collect()
}

pub fn handle_lid_fade(req: &Request, cache: &ObjectCache) -> Response {
    let Some(ctx) = req.context_values() else {
        return Response::Error("Missing lid-fade context".into());
    };
    if ctx.get("schema").and_then(Json::as_str) != Some("lid-fade/1") {
        return Response::Error("Missing lid-fade/1 schema".into());
    }
    let Some(ctx_targets) = ctx.get("targets").and_then(Json::as_obj) else {
        return Response::Error("Missing lid-fade targets".into());
    };
    let config = req.config();
    let Some(scaffold_val) = config.get("scaffold") else {
        return Response::Error("Missing lid-fade scaffold config".into());
    };
    let Some(scaffold_id) = scaffold_val.as_str() else {
        return Response::Error("Invalid lid-fade scaffold config".into());
    };
    let Some(base_id) = config.get("base").and_then(Json::as_str) else {
        return Response::Error("Missing lid-fade base config".into());
    };

    let targets: Vec<String> = req
        .header
        .get("targets")
        .and_then(Json::as_arr)
        .unwrap_or(&[])
        .iter()
        .filter_map(|t| t.get("id").and_then(Json::as_str).map(str::to_string))
        .collect();
    for (k, _) in ctx_targets {
        if !targets.iter().any(|t| t == k) {
            return Response::Error(format!("Unexpected lid-fade measurement: {k}"));
        }
    }

    let upstream_json = req.context_upstream();
    let upstream = parse_upstream(upstream_json.as_ref());
    if !upstream.iter().any(|u| {
        u.capability == "rig.scaffold"
            && u.instance_id == scaffold_id
            && targets.iter().all(|t| u.targets.contains(t.as_str()))
    }) {
        return Response::Error("Missing lid fade scaffold provider".into());
    }

    let frame = match cache.get("frame").and_then(|b| decode_object_frame(b).map_err(|e| e.to_string())) {
        Ok(f) => f,
        Err(e) => return Response::Error(e),
    };
    let defaults: HashMap<String, f32> = frame.parameters.iter().map(|p| (p.id.clone(), p.default)).collect();

    let mut changed_blobs = HashMap::new();
    for tid in &targets {
        if !upstream.iter().any(|u| {
            u.capability == "eye/fitted" && u.instance_id == base_id && u.targets.contains(tid.as_str())
        }) {
            return Response::Error("Missing fitted eye provider".into());
        }
        let Some(measured) = ctx_targets.iter().find(|(k, _)| k == tid).map(|(_, v)| v) else {
            continue;
        };
        if upstream.iter().any(|u| {
            (u.capability == "eye.lid-fade" || u.capability == "builtin.6579652e6c69642d66616465.v2/fade")
                && u.targets.contains(tid.as_str())
                && u.scaffold == Some(scaffold_val)
        }) {
            continue;
        }
        let wide = upstream.iter().any(|u| {
            u.capability == "eye.wide" && u.targets.contains(tid.as_str()) && u.scaffold == Some(scaffold_val)
        });
        let Some(param) = measured.get("parameter").and_then(Json::as_str) else {
            return Response::Error(format!("Missing lid-fade parameter for {tid}"));
        };
        if !defaults.contains_key(param) {
            return Response::Error("Missing fitted eye opening parameter".into());
        }
        let crease = measured.get("crease").and_then(Json::as_f64).unwrap_or(0.2) as f32;
        let neutral_opacity = measured.get("opacity").and_then(Json::as_f64).unwrap_or(1.0) as f32;

        let mut mesh = match cache
            .get(&format!("mesh:{tid}"))
            .and_then(|b| decode_object_mesh(b).map_err(|e| e.to_string()))
        {
            Ok(m) => m,
            Err(e) => return Response::Error(e),
        };
        let orig_mesh = mesh.clone();

        let keys = if wide {
            vec![0.0, crease + 0.15, 0.75, 1.0, 2.0]
        } else {
            vec![0.0, crease + 0.15, 0.75, 1.0]
        };
        let cells = (0..keys.len())
            .map(|i| KeyCell {
                coordinate: vec![i as i32],
                form: ChannelValue::Scalar(if i < 2 { 0.0 } else { 1.0 }),
            })
            .collect();
        let desired = KeyGrid {
            axes: vec![KeyAxis { parameter: param.to_string(), keys }],
            cells,
        };

        let actual_grid = grid_ref(&orig_mesh.channels, CHANNEL_OPACITY);
        let opacity_grid = if actual_grid.is_none() && orig_mesh.opacity == neutral_opacity {
            desired
        } else {
            let neutral = ChannelValue::Scalar(neutral_opacity);
            residual_channel_grid(
                &defaults,
                actual_grid,
                &ChannelValue::Scalar(orig_mesh.opacity),
                None,
                &neutral,
                Some(&desired),
                &neutral,
            )
        };
        set_channel(&mut mesh.channels, CHANNEL_OPACITY, Some(opacity_grid));

        if !mesh.shapes.is_empty() {
            let previous = eval_scalar_channel(&orig_mesh.channels, CHANNEL_OPACITY, orig_mesh.opacity, &defaults);
            let current = eval_scalar_channel(&mesh.channels, CHANNEL_OPACITY, mesh.opacity, &defaults);
            if current != previous {
                for binding in &mut mesh.shapes {
                    for shape in binding.shapes.iter_mut().flatten() {
                        shape.opacity = current + if shape.opacity.is_finite() { shape.opacity - previous } else { 0.0 };
                    }
                }
            }
        }

        if mesh != orig_mesh {
            changed_blobs.insert(format!("mesh:{tid}"), encode_object_mesh(&mesh));
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
    objects_response(changed_blobs, mesh_order, deformer_order)
}
