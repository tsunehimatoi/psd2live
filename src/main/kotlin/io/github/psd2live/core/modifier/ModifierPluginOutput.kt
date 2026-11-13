package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Candidate-only model commands. Transport and process lifetime are owned by ModifierPluginWorkers. */
internal object ModifierPluginOutput {
    val operations = setOf("physics", "physics_group_add", "physics_group_inputs_add", "motion", "motion_clip_add",
        "parameter_add", "mesh_opacity", "mesh_masked_by", "mesh_clone", "mesh_channel_grid_set", "structure", "set", "parameter_keys", "delete", "copy", "warp",
        "canvas_geometry", "canvas_topology", "canvas_create_warp", "canvas_create_rotation", "canvas_create_glue", "canvas_glue_edit",
        "path_put", "path_delete", "vertex_group_put", "vertex_group_delete")
    val components = setOf("mesh", "deformer", "part", "parameter", "physics", "motion", "glue", "textures", "hierarchy", "relations",
        "canvas", "authoring", "advanced", "hit_areas", "rest_pose", "*")
    fun sidecars(before: RigIR, after: RigIR): List<ModifierContribution> = buildList {
        fun changed(kind: String, id: String, a: Any?, b: Any?) {
            if (a != b) add(ModifierContribution(kind, id, ModifierChange.MODIFIED))
        }
        changed("canvas", "canvas", before.canvas, after.canvas)
        changed("textures", "textures", before.textures, after.textures)
        changed("authoring", "authoring", before.authoring, after.authoring)
        changed("advanced", "advanced", before.advanced, after.advanced)
        changed("hit_areas", "hit-areas", before.hitAreas, after.hitAreas)
        changed("rest_pose", "rest-pose", before.restPose, after.restPose)
        changed("hierarchy", "render-root", before.renderRoot, after.renderRoot)
        changed("hierarchy", "root-part", before.rootPart, after.rootPart)
        changed("physics", "fps", before.physics.fps, after.physics.fps)
    }
    fun apply(rig: RigIR, instance: RigModifierInstance, algorithm: ModifierPluginAlgorithm, commands: JsonArray, checkpoint: () -> Unit): RigIR =
        apply(rig, instance, algorithm, commands, checkpoint, emptyList())

    fun apply(rig: RigIR, instance: RigModifierInstance, algorithm: ModifierPluginAlgorithm, commands: JsonArray, checkpoint: () -> Unit,
              declaredParameters: List<io.github.psd2live.format.model.Parameter>): RigIR {
        require(commands.size <= 10000) { "Too many plugin commands" }
        var output = rig
        for (raw in commands) {
            checkpoint()
            val command = raw.jsonObject
            when (command["op"]?.jsonPrimitive?.content) {
                "physics_group_add" -> {
                    require("physics" in algorithm.writes || "*" in algorithm.writes) { "Plugin write is outside declared targets" }
                    val edit = io.github.psd2live.core.RigPhysicsEdit.fromJson(command.getValue("group").jsonObject)
                    val group = io.github.psd2live.algorithms.physics.PhysicsGroups.fromSetting(edit)
                    output = io.github.psd2live.algorithms.physics.PhysicsGroups.append(output, listOf(group))
                }
                "physics_group_inputs_add" -> {
                    require("physics" in algorithm.writes || "*" in algorithm.writes) { "Plugin write is outside declared targets" }
                    val groupId = command.getValue("groupId").jsonPrimitive.content
                    val additions = command.getValue("inputs").jsonArray.map { rawPin ->
                        val pin = rawPin.jsonObject
                        io.github.psd2live.format.model.PhysicsInput(
                            parameter = pin.getValue("parameter").jsonPrimitive.content,
                            weight = pin.getValue("weight").jsonPrimitive.float,
                            source = io.github.psd2live.format.model.PhysicsSource.valueOf(pin.getValue("type").jsonPrimitive.content),
                            reflect = pin["reflect"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                    }
                    val groups = output.physics.groups.map { group ->
                        if (group.id != groupId) group
                        else {
                            val existing = group.inputs.groupBy { it.parameter }
                            for (pin in additions) require(existing[pin.parameter].orEmpty().all { it == pin }) {
                                "Incompatible expression input ${pin.parameter} in physics group ${group.id}"
                            }
                            group.copy(inputs = group.inputs + additions.filter { it.parameter !in existing })
                        }
                    }
                    output = output.copy(physics = output.physics.copy(groups = groups))
                }
                "parameter_add" -> {
                    require("parameter" in algorithm.writes || "*" in algorithm.writes) { "Plugin write is outside declared targets" }
                    val id = command.getValue("id").jsonPrimitive.content
                    if (output.parameters.none { it.id == id }) {
                        val keys = command["keys"]?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray()
                        val param = io.github.psd2live.format.model.Parameter(
                            id = id,
                            name = command["name"]?.jsonPrimitive?.content ?: id,
                            min = command.getValue("min").jsonPrimitive.float,
                            max = command.getValue("max").jsonPrimitive.float,
                            default = command.getValue("default").jsonPrimitive.float,
                            blend = command["blend"]?.jsonPrimitive?.booleanOrNull ?: false,
                            repeat = command["repeat"]?.jsonPrimitive?.booleanOrNull ?: false,
                            keys = keys?.let(io.github.psd2live.format.model.Floats::of),
                        )
                        output = output.copy(
                            parameters = output.parameters + param,
                            parameterTree = output.parameterTree + io.github.psd2live.format.model.ParameterNode.Param(id),
                        )
                    }
                }
                "motion_clip_add" -> {
                    require("motion" in algorithm.writes || "*" in algorithm.writes) { "Plugin write is outside declared targets" }
                    val source = io.github.psd2live.core.MotionData.fromJson(command.getValue("clip").jsonObject)
                    val id = command.getValue("id").jsonPrimitive.content
                    val file = command.getValue("file").jsonPrimitive.content
                    val clip = io.github.psd2live.format.model.Clip(
                        id = id,
                        name = source.name,
                        group = source.exportGroup ?: if (source.loop) "Idle" else source.name,
                        file = file,
                        duration = source.duration,
                        fps = source.fps,
                        loop = source.loop,
                        fadeIn = source.fadeIn.takeUnless { source.runtimeDefaultFades },
                        fadeOut = source.fadeOut.takeUnless { source.runtimeDefaultFades },
                        curves = source.curves.map(io.github.psd2live.core.MotionData::curve),
                        events = source.events.map { io.github.psd2live.format.model.ClipEvent(it.time, it.value) },
                    )
                    require(output.clips.none { it.id == clip.id || it.file.equals(clip.file, ignoreCase = true) }) {
                        "Motion ID or file collision: ${clip.id} / ${clip.file}"
                    }
                    val existingRoles = output.parameterRoles
                    val roles = source.parameterRoles.map { (role, parameters) ->
                        require(existingRoles.none { it.role == role && it.parameters != parameters }) { "Conflicting motion parameter role $role" }
                        io.github.psd2live.format.model.ParameterRole(role, parameters)
                    }
                    val combinedRoles = (roles + existingRoles).distinct()
                    output = output.copy(clips = output.clips + clip, parameterRoles = combinedRoles)
                }
                "mesh_masked_by" -> {
                    val id = command.getValue("id").jsonPrimitive.content
                    require(("*" in algorithm.writes || instance.targets.any { it.kind == "mesh" && it.id == id }) &&
                        ("mesh" in algorithm.writes || "*" in algorithm.writes)) { "Plugin write is outside declared targets" }
                    val maskedBy = command.getValue("maskedBy").jsonArray.map { it.jsonPrimitive.content }
                    val invertMask = command["invertMask"]?.jsonPrimitive?.booleanOrNull
                    output = output.copy(meshes = output.meshes.map {
                        if (it.id == id) it.copy(maskedBy = maskedBy, invertMask = invertMask ?: it.invertMask) else it
                    })
                }
                "mesh_clone" -> {
                    require("mesh" in algorithm.writes || "*" in algorithm.writes) { "Plugin write is outside declared targets" }
                    val sourceId = command.getValue("sourceId").jsonPrimitive.content
                    val newId = command.getValue("id").jsonPrimitive.content
                    val newName = command.getValue("name").jsonPrimitive.content
                    val drawOrder = command.getValue("drawOrder").jsonPrimitive.float
                    val opacityScale = command["opacityScale"]?.jsonPrimitive?.floatOrNull ?: 1f
                    val maskedBy = command["maskedBy"]?.jsonArray.orEmpty().map { DrawableId(it.jsonPrimitive.content) }
                    val invertMask = command["invertMask"]?.jsonPrimitive?.booleanOrNull ?: false
                    val selectable = command["selectable"]?.jsonPrimitive?.booleanOrNull ?: true
                    val insertBefore = command["insertBeforeOriginal"]?.jsonPrimitive?.booleanOrNull ?: true
                    val model = PuppetIr.toPuppet(output)
                    val original = requireNotNull(model.drawables.firstOrNull { it.id.raw == sourceId }) { "Missing source mesh $sourceId" }
                    require(model.drawables.none { it.id.raw == newId }) { "Duplicate cloned mesh id $newId" }
                    val tracks = LinkedHashMap(original.channelGrids.gridsByChannel)
                    tracks.remove(FormChannel.DRAW_ORDER)
                    tracks[FormChannel.OPACITY]?.let { track ->
                        tracks[FormChannel.OPACITY] = KeyformGrid(
                            track.axes,
                            track.cells.map { cell ->
                                KeyformCell(cell.coordinate, (cell.form as? ChannelValue.Scalar)?.let { ChannelValue.Scalar(it.value * opacityScale) } ?: cell.form)
                            },
                        )
                    }
                    val cloned = original.copy(
                        id = DrawableId(newId),
                        name = newName,
                        maskedBy = maskedBy,
                        invertMask = invertMask,
                        channelGrids = if (tracks.isEmpty()) ChannelGrids.Empty else ChannelGrids(tracks),
                        drawOrder = drawOrder,
                        opacity = original.opacity * opacityScale,
                        isSelectable = selectable,
                        textureSourceId = original.textureSourceId ?: original.id,
                        blendShapes = original.blendShapes.map { binding ->
                            binding.copy(forms = binding.forms.map { form ->
                                form?.let { MeshForm(it.positionDeltas, drawOrder, it.opacity * opacityScale, it.multiplyColor, it.screenColor) }
                            })
                        },
                        userData = "",
                    )
                    fun place(children: List<OrgChild>): List<OrgChild> = if (!insertBefore) children else children.flatMap { child ->
                        if (child is OrgChild.Drawable && child.id == original.id) listOf(OrgChild.Drawable(cloned.id), child) else listOf(child)
                    }
                    val updated = model.copy(
                        drawables = model.drawables + cloned,
                        parts = model.parts.map { part ->
                            if (part.children.none { it is OrgChild.Drawable && it.id == original.id }) part
                            else part.copy(children = place(part.children))
                        },
                        rootChildren = place(model.rootChildren),
                    ).withDerivedRenderRoot()
                    output = output.withPuppet(updated)
                }
                "physics", "motion" -> {
                    val definition = if (command.getValue("op").jsonPrimitive.content == "physics")
                        PhysicsAlgorithmPlugin.definition else MotionAlgorithmPlugin.definition
                    output = definition.plugin.evaluate(output, instance.copy(config = command.getValue("config").jsonObject), checkpoint)
                }
                "mesh_opacity" -> {
                    val id = command.getValue("id").jsonPrimitive.content
                    require(instance.targets.any { it.kind == "mesh" && it.id == id } && ("mesh" in algorithm.writes || "*" in algorithm.writes)) { "Plugin write is outside declared targets" }
                    val opacity = command.getValue("value").jsonPrimitive.float
                    require(opacity.isFinite() && opacity in 0f..1f) { "Invalid plugin opacity" }
                    output = output.copy(meshes = output.meshes.map { if (it.id == id) it.copy(opacity = opacity) else it })
                }
                "mesh_channel_grid_set" -> {
                    val id = command.getValue("id").jsonPrimitive.content
                    require(("*" in algorithm.writes || instance.targets.any { it.kind == "mesh" && it.id == id }) &&
                        ("mesh" in algorithm.writes || "*" in algorithm.writes)) { "Plugin write is outside declared targets" }
                    val channel = FormChannel.valueOf(command["channel"]?.jsonPrimitive?.content ?: "OPACITY")
                    val axes = command.getValue("axes").jsonArray.map { rawAxis ->
                        val axis = rawAxis.jsonObject
                        KeyformAxis(
                            ParameterId(axis.getValue("parameter").jsonPrimitive.content),
                            axis.getValue("keys").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                        )
                    }
                    val cells = command.getValue("cells").jsonArray.map { rawCell ->
                        val cell = rawCell.jsonObject
                        val coord = cell.getValue("coord").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
                        val value: ChannelValue = ChannelValue.Scalar(cell.getValue("value").jsonPrimitive.float)
                        KeyformCell(coord, value)
                    }
                    val grid = KeyformGrid<ChannelValue>(axes, cells)
                    val model = PuppetIr.toPuppet(output)
                    val updated = model.copy(drawables = model.drawables.map { drawable ->
                        if (drawable.id.raw != id) drawable
                        else drawable.copy(channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel + (channel to grid)))
                    })
                    output = output.withPuppet(updated)
                }
                else -> {
                    require(command["op"]?.jsonPrimitive?.content in operations) { "Unsupported plugin command" }
                    output = output.withPuppet(RigAuthoringJournal.apply(PuppetIr.toPuppet(output), command))
                }
            }
        }
        return validate(rig, output, instance, algorithm, declaredParameters)
    }

    fun validate(rig: RigIR, output: RigIR, instance: RigModifierInstance, algorithm: ModifierPluginAlgorithm,
                 declaredParameters: List<io.github.psd2live.format.model.Parameter> = emptyList()): RigIR {
        fun field(kind: String, before: Any?, after: Any?) {
            require(before == after || kind in algorithm.writes || "*" in algorithm.writes) { "Plugin wrote undeclared $kind data" }
        }
        field("canvas", rig.canvas, output.canvas)
        field("textures", rig.textures, output.textures)
        field("authoring", rig.authoring, output.authoring)
        field("advanced", rig.advanced, output.advanced)
        field("hit_areas", rig.hitAreas, output.hitAreas)
        field("rest_pose", rig.restPose, output.restPose)
        field("hierarchy", rig.renderRoot, output.renderRoot)
        field("hierarchy", rig.rootPart, output.rootPart)
        field("physics", rig.physics.fps, output.physics.fps)
        require(declaredParameters.map { it.id }.distinct().size == declaredParameters.size) { "Duplicate captured shared parameter" }
        val approved = declaredParameters.associateBy { it.id }
        val existing = rig.parameters.mapTo(HashSet()) { it.id }
        output.parameters.filter { it.id !in existing && it.id in approved }.forEach {
            require(it == approved.getValue(it.id)) { "Plugin changed captured shared parameter ${it.id}" }
        }
        val changes = RigModifierEvaluator.contributions(rig, output)
        require(changes.all { it.kind in algorithm.writes || "*" in algorithm.writes }) { "Plugin wrote undeclared object kinds" }
        require(changes.filter { it.change == ModifierChange.ADDED && it.kind !in setOf("relations", "hierarchy") }.all {
            instance.localOutputName(it.id) != null || it.kind == "parameter" && (algorithm.parameters.any { p -> p.id == it.id } || it.id in approved) ||
                algorithm.derivedOutputs.any { rule -> rule.matches(instance, it.kind, it.id) }
        }) { "Plugin outputs must use instance-owned deterministic IDs or declared shared parameters" }
        return output
    }
}
