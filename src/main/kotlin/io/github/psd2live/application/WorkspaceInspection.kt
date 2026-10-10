package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceKeyformTargetRef
import kotlinx.serialization.json.*

/** Compose discovery and detail from an already detached query capture. */
internal fun WorkspaceQueries.inspect(a: JsonObject): JsonObject {
    val queries = this
    val snapshot = queries.snapshot()
    val state = snapshot.state
    val target = a["target"]?.jsonPrimitive?.content
    val scope = if (target != null) "object" else a["scope"]?.jsonPrimitive?.content ?: "project"
    return buildJsonObject {
        put("scope", scope)
        put("state", state); put("project_id", snapshot.projectId?.let(::JsonPrimitive) ?: JsonNull)
        snapshot.historyHeadNodeId?.let { put("history_node_id", it) }
        if (target != null) {
            val ref = inspectionTarget(target)
            val obj = queries.getObject(ref)
            put("target", target); put("name", obj.name)
            obj.parentId?.let { put("parent", it) }
            put("visible", obj.visible)
            obj.geometry?.let { geometry ->
                put("forms", geometry.keyformCount)
                putJsonObject("axes") { geometry.axes.forEach { axis -> put(axis.parameterId, JsonArray(axis.keys.map(::JsonPrimitive))) } }
            }
            putJsonArray("channels") { obj.channels.forEach { channel -> add(buildJsonObject {
                put("channel", channel.channel); put("value", channel.staticValue)
                if (channel.axes.isNotEmpty()) putJsonObject("axes") { channel.axes.forEach { axis -> put(axis.parameterId, JsonArray(axis.keys.map(::JsonPrimitive))) } }
            }) } }
            if (ref.kind == "mesh") {
                val paths = queries.currentPuppet()?.deformPaths?.filter { it.drawableId.raw == ref.id }.orEmpty()
                if (paths.isNotEmpty()) {
                    putJsonArray("paths") {
                        paths.forEach { p ->
                            add(buildJsonObject {
                                put("id", p.id); put("level", p.editLevel); put("width", p.width)
                                put("hardness", p.hardness); put("closed", p.closed); put("pointCount", p.points.size)
                            })
                        }
                    }
                }
            }
            // A short ownership chain explains inherited motion without returning ancestor geometry.
            val objects = queries.listRigObjectSummaries().associateBy { it.getValue("id").jsonPrimitive.content }
            var parent = obj.parentId
            val seen = mutableSetOf<String>()
            putJsonArray("inherited") {
                while (parent != null) {
                    val parentId = parent ?: break
                    if (!seen.add(parentId)) break
                    val summary = objects[parentId] ?: break
                    val kind = summary.getValue("kind").jsonPrimitive.content
                    val ancestor = queries.getObject(WorkspaceKeyformTargetRef(kind, parentId))
                    add(buildJsonObject {
                        put("target", "$kind:$parent"); put("name", ancestor.name)
                        putJsonArray("parameters") { ancestor.geometry?.axes.orEmpty().forEach { add(JsonPrimitive(it.parameterId)) } }
                    })
                    parent = ancestor.parentId
                }
            }
        } else when (scope) {
            "project" -> {
                put("loaded", snapshot.loaded); put("busy", snapshot.busy)
                putJsonArray("canvas") { snapshot.canvasWidth?.let { add(JsonPrimitive(it)) }; snapshot.canvasHeight?.let { add(JsonPrimitive(it)) } }
                snapshot.selectedLayerId?.let { put("selection", it) }
                put("layers", snapshot.layers.count { !it.deleted }); put("parameters", snapshot.parameters.size)
                snapshot.persistenceError?.let { put("persistenceError", it) }
                if (snapshot.loaded) putJsonObject("quality") {
                    put("overrides", io.github.psd2live.core.quality.GeneratedOverrideQuality.report(queries.generatedOverrideIssues(), queries.supersededEntryNotes()))
                    put("regeneration", io.github.psd2live.core.quality.RegenerationQuality.report(queries.regenerationIssues(), queries.staleRegenerations()))
                    put("skeleton", io.github.psd2live.core.quality.SkeletonBindingQuality.report(queries.skeletonBindingIssues()))
                }
            }
            "physics" -> { put("fps", queries.physicsFps()); put("groups", JsonArray(queries.listPhysics().map { it.toJson() })) }
            "swings" -> put("swings", JsonArray(queries.listSwings().map { it.toJson() }))
            "simulations" -> {
                val puppet = queries.currentPuppet()
                // The bake's arrays stay out; its summary and whether it is stale are what a caller acts on.
                put("simulations", JsonArray(queries.listSimulations().map { sim -> JsonObject(sim.toJson() - "bake" + buildJsonObject {
                    sim.bake?.let { bake ->
                        put("bake", bake.summary())
                        if (puppet != null) put("bake_stale", io.github.psd2live.core.sim.SimBake.stale(puppet, sim))
                    }
                }) }))
                // Glue keys are what glue_roles take.
                put("glues", JsonArray(queries.currentPuppet()?.glues.orEmpty().map { glue -> buildJsonObject {
                    put("key", io.github.psd2live.core.sim.glueKey(glue)); put("pairs", glue.pairs.size)
                } }))
            }
            "settings" -> put("settings", queries.projectSettings())
            "preview" -> put("preview", queries.previewSession())
            else -> {
                val items = when (scope) {
                    "objects" -> queries.listRigObjectSummaries().map { row -> JsonObject(row - "id" - "kind" + ("target" to JsonPrimitive("${row.getValue("kind").jsonPrimitive.content}:${row.getValue("id").jsonPrimitive.content}"))) }
                    "layers" -> snapshot.layers.filterNot { it.deleted }.map { layer -> buildJsonObject {
                        put("id", layer.id); put("name", layer.sourceName); put("visible", layer.visible)
                        put("role", layer.semanticTag); put("side", layer.side)
                        put("type", layer.classificationType); put("parameter", layer.parameterBinding)
                        put("switch_id", layer.switchId)
                        put("mesh", queries.layerMeshSettings(layer.id))
                        putJsonArray("bounds") { listOf(layer.opaqueBounds.left, layer.opaqueBounds.top, layer.opaqueBounds.right, layer.opaqueBounds.bottom).forEach { add(JsonPrimitive(it)) } }
                    } }
                    "parameters" -> snapshot.parameters.map { p -> buildJsonObject { put("id", p.id); put("name", p.name); put("min", p.min); put("max", p.max); put("default", p.default) } }
                    "paths" -> queries.currentPuppet()?.deformPaths.orEmpty().map { p -> buildJsonObject {
                        put("id", p.id); put("target", "mesh:${p.drawableId.raw}"); put("level", p.editLevel)
                        put("width", p.width); put("hardness", p.hardness); put("closed", p.closed); put("pointCount", p.points.size)
                    } }
                    "vertex_groups" -> queries.currentPuppet()?.vertexGroups.orEmpty().map { g -> buildJsonObject {
                        put("target", "mesh:${g.drawableId.raw}"); put("name", g.name); put("kind", g.kind.jsonName)
                        put("vertices", g.weights.size); put("weighted", g.weights.count { it > 0f })
                        put("max", g.weights.maxOrNull() ?: 0f)
                    } }
                    else -> error("Unknown inspect scope")
                }.filter { a["query"]?.jsonPrimitive?.content?.let { query -> it.toString().contains(query, ignoreCase = true) } ?: true }
                val offset = a["offset"]?.jsonPrimitive?.int ?: 0; val limit = a["limit"]?.jsonPrimitive?.int ?: 24
                require(offset >= 0 && limit in 1..64)
                put("items", JsonArray(items.drop(offset).take(limit)))
                put("total", items.size)
                if (offset.toLong() + limit < items.size) put("next", offset + limit)
            }
        }
    }
}

private fun inspectionTarget(value: String): WorkspaceKeyformTargetRef {
    val pair = value.split(':', limit = 2)
    require(pair.size == 2 && pair[0] in setOf("mesh", "warp", "rotation", "part", "glue") && pair[1].isNotBlank()) {
        "Use kind:id from inspect"
    }
    return WorkspaceKeyformTargetRef(pair[0], pair[1])
}
