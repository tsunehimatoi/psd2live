use std::io::{self, BufRead, Write};
use serde_json::{json, Value};

fn main() {
    let mut objects = serde_json::Map::new();
    let stdout = io::stdout();
    let mut output = stdout.lock();
    for line in io::stdin().lock().lines() {
        let request: Value = serde_json::from_str(&line.expect("input")).expect("JSON");
        assert_eq!(request["protocol"], 1);
        if request["reset"] == true { objects.clear(); }
        for key in request["removed"].as_array().expect("removed") { objects.remove(key.as_str().expect("key")); }
        for (key, value) in request["changed"].as_object().expect("changed") { objects.insert(key.clone(), value.clone()); }
        let factor = request["config"]["factor"].as_f64().expect("factor");
        let commands: Vec<Value> = request["meshes"].as_array().expect("meshes").iter().map(|mesh| {
            json!({"op": "mesh_opacity", "id": mesh["id"], "value": mesh["opacity"].as_f64().expect("opacity") * factor})
        }).collect();
        writeln!(output, "{}", json!({"protocol": 1, "id": request["id"], "commands": commands})).expect("output");
        output.flush().expect("flush");
    }
}
