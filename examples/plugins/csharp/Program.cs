using System.Text.Json.Nodes;

var objects = new JsonObject();
string? line;
while ((line = Console.ReadLine()) != null)
{
    var request = JsonNode.Parse(line)!.AsObject();
    var response = new JsonObject { ["protocol"] = 1, ["id"] = request["id"]!.DeepClone() };
    try
    {
        if (request["protocol"]!.GetValue<int>() != 1) throw new Exception("Unsupported protocol");
        if (request["reset"]!.GetValue<bool>()) objects.Clear();
        foreach (var key in request["removed"]!.AsArray()) objects.Remove(key!.GetValue<string>());
        foreach (var pair in request["changed"]!.AsObject()) objects[pair.Key] = pair.Value!.DeepClone();
        var commands = new JsonArray();
        var factor = request["config"]!["factor"]!.GetValue<double>();
        foreach (var mesh in request["meshes"]!.AsArray())
            commands.Add(new JsonObject {
                ["op"] = "mesh_opacity", ["id"] = mesh!["id"]!.DeepClone(),
                ["value"] = mesh["opacity"]!.GetValue<double>() * factor
            });
        response["commands"] = commands;
    }
    catch (Exception error) { response["error"] = error.Message; }
    Console.WriteLine(response.ToJsonString());
    Console.Out.Flush();
}
