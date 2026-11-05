package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.VertexGroupKind

/** The same native configuration controls are used before insertion and inside a persisted instance. */
@Composable
internal fun ModifierNativeValues(type: String, config: JsonObject, state: PSD2LiveState, enabled: Boolean,
                                  vm: PSD2LiveViewModel, editName: Boolean = true, instanceId: String? = null, quickOnly: Boolean = false,
                                  commit: (JsonObject, String?, (String?) -> Unit) -> Unit) {
    fun change(next: JsonObject) = commit(next, vm.modifierEditState()) {}
    @Composable fun number(label: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> JsonObject) {
        ModifierNumber(tr(label), value, range, "", enabled, vm) { next, expected, done -> commit(update(next), expected, done) }
    }
    @Composable fun <T> choice(label: String, values: List<T>, selected: T, display: (T) -> String, update: (T) -> JsonObject) {
        InspectorFormRow(tr(label)) {
            CompactDropdown(values, selected, { change(update(it)) }, itemLabel = display, enabled = enabled, height = 20.dp, modifier = Modifier.fillMaxWidth())
        }
    }
    @Composable fun toggle(label: String, value: Boolean, update: (Boolean) -> JsonObject) {
        InspectorFormRow(tr(label)) { CompactCheckbox(value, { change(update(it)) }, enabled = enabled) }
    }
    if (quickOnly) {
        when (type) {
            "swing" -> {
                val edit = RigSwingEdit.fromJson(config)
                choice("swing.preset", SwingPreset.entries, edit.preset, { tr("swing.preset.${it.name.lowercase()}") }) { preset ->
                    edit.copy(preset = preset, motions = edit.motions.map { it.copy(shape = SwingPresets.shape(preset, it.kind)) }).toJson()
                }
                edit.motions.singleOrNull()?.let { motion ->
                    number("modifier.swing.magnitude", motion.shape.magnitude, 0f..0.7f) { edit.copy(motions = listOf(motion.copy(shape = motion.shape.copy(magnitude = it)))).toJson() }
                    number("modifier.swing.softness", motion.shape.softness, 0f..1f) { edit.copy(motions = listOf(motion.copy(shape = motion.shape.copy(softness = it)))).toJson() }
                }
            }
            "simulation" -> {
                val edit = RigSimEdit.fromJson(config)
                choice("sim.material", listOf<SimMaterialPreset?>(null) + SimMaterialPreset.of(edit.kind), SimMaterialPreset.matching(edit.material),
                    { if (it == null) tr("sim.preset.custom") else tr("sim.preset.${it.jsonName}") }) {
                    edit.copy(material = it?.material ?: edit.material).toJson()
                }
                number("sim.exaggeration", edit.exaggeration, RigSimEdit.EXAGGERATIONS) { edit.copy(exaggeration = it).toJson() }
            }
            "motion" -> {
                val clip = MotionClips.fromJson(config)
                number("animation.duration", clip.duration, .01f..120f) { MotionClips.toJson(MotionKeyEdits.withDuration(clip, it)) }
                toggle("animation.loop", clip.loop) { MotionClips.toJson(clip.copy(loop = it)) }
            }
            "physics" -> {
                val edit = RigPhysicsEdit.fromJson(config)
                edit.outputs.singleOrNull()?.let { output ->
                    number("physics.outputScale", output.scale, -100f..100f) { edit.copy(outputs = listOf(output.copy(scale = it))).toJson() }
                }
            }
        }
        return
    }
    if (editName && type in setOf("swing", "physics", "motion", "simulation")) {
        val name = config.getValue("name").jsonPrimitive.content
        var draft by remember { mutableStateOf(name) }
        var editing by remember { mutableStateOf(false) }
        var expected by remember { mutableStateOf<String?>(null) }
        val actual by rememberUpdatedState(name)
        LaunchedEffect(name) { if (!editing) draft = name }
        CompactTextField(draft, { draft = it }, placeholder = tr("inspector.name"), enabled = enabled, modifier = Modifier.fillMaxWidth(),
            onEditStart = { editing = true; expected = vm.modifierEditState() }, onEditEnd = {
                editing = false
                if (draft.isBlank() || draft.any(Char::isISOControl)) draft = actual
                else if (draft != name) commit(JsonObject(config + ("name" to JsonPrimitive(draft))), expected) { failure -> if (failure != null) draft = actual }
            })
    }
    val parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty()
    val parameterIds = parameters.map { it.id.raw }
    fun parameterName(id: String) = parameters.firstOrNull { it.id.raw == id }?.name ?: id
    when (type) {
        "swing" -> {
            val edit = RigSwingEdit.fromJson(config)
            choice("swing.preset", SwingPreset.entries, edit.preset, { tr("swing.preset.${it.name.lowercase()}") }) { preset ->
                edit.copy(preset = preset, motions = edit.motions.map { it.copy(shape = SwingPresets.shape(preset, it.kind)) }).toJson()
            }
            choice("swing.fulcrum", SwingFulcrum.entries, edit.fulcrum, { tr("swing.fulcrum.${it.name.lowercase()}") }) { edit.copy(fulcrum = it).toJson() }
            number("swing.tilt", edit.tilt, -75f..75f) { edit.copy(tilt = it).toJson() }
            number("modifier.swing.along", edit.offsetAlong, -1f..1f) { edit.copy(offsetAlong = it).toJson() }
            number("modifier.swing.across", edit.offsetAcross, -1f..1f) { edit.copy(offsetAcross = it).toJson() }
            edit.motions.forEachIndexed { index, motion ->
                Text(tr("swing.kind.${motion.kind.name.lowercase()}"), color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.caption)
                val kinds = SwingKind.entries.filter { candidate -> candidate == motion.kind || edit.motions.none { it.kind == candidate } }
                fun updated(next: SwingMotion) = edit.copy(motions = edit.motions.mapIndexed { i, old -> if (i == index) next else old }).toJson()
                choice("swing.kind", kinds, motion.kind, { tr("swing.kind.${it.name.lowercase()}") }) { updated(motion.copy(kind = it)) }
                choice("swing.segments", (1..RigSwingEdit.MAX_SEGMENTS).toList(), motion.parameterIds.size, Int::toString) { count ->
                    updated(motion.copy(parameterIds = List(count) { "output:$index:$it" }))
                }
                val shape = motion.shape
                number("modifier.swing.magnitude", shape.magnitude, 0f..0.7f) { updated(motion.copy(shape = shape.copy(magnitude = it))) }
                number("modifier.swing.lift", shape.lift, -.5f.. .5f) { updated(motion.copy(shape = shape.copy(lift = it))) }
                number("modifier.swing.softness", shape.softness, 0f..1f) { updated(motion.copy(shape = shape.copy(softness = it))) }
                number("modifier.swing.zoom", shape.zoom, -.5f.. .5f) { updated(motion.copy(shape = shape.copy(zoom = it))) }
                number("modifier.swing.parallel", shape.parallel, 0f..1f) { updated(motion.copy(shape = shape.copy(parallel = it))) }
                toggle("swing.flip", shape.flip) { updated(motion.copy(shape = shape.copy(flip = it))) }
                toggle("swing.physics.enabled", motion.physics != null) { updated(motion.copy(physics = if (it) SwingPhysics() else null)) }
                motion.physics?.let { physics ->
                    number("physics.length", physics.length, .01f..100f) { updated(motion.copy(physics = physics.copy(length = it))) }
                    number("physics.shakiness", physics.mobility, 0f..1f) { updated(motion.copy(physics = physics.copy(mobility = it))) }
                    number("physics.reactionSpeed", physics.delay, .01f..3f) { updated(motion.copy(physics = physics.copy(delay = it))) }
                    number("physics.convergenceSpeed", physics.acceleration, 0f..5f) { updated(motion.copy(physics = physics.copy(acceleration = it))) }
                    number("physics.outputScale", physics.outputScale, -10f..10f) { updated(motion.copy(physics = physics.copy(outputScale = it))) }
                }
            }
            if (edit.motions.size == 1) CompactButton(tr("modifier.swing.addAxis"), {
                val kind = SwingKind.entries.first { it != edit.motions.single().kind }
                change(edit.copy(motions = edit.motions + SwingMotion(kind, listOf("output:second"), SwingPresets.shape(edit.preset, kind))).toJson())
            }, enabled = enabled, height = 20.dp)
            else CompactButton(tr("modifier.swing.removeAxis"), { change(edit.copy(motions = edit.motions.take(1)).toJson()) }, enabled = enabled, height = 20.dp)
        }
        "physics" -> {
            val edit = RigPhysicsEdit.fromJson(config)
            var selectedInputs by remember(instanceId) { mutableIntStateOf(0) }
            val activeInputs = selectedInputs.coerceIn(0, edit.inputs.lastIndex.coerceAtLeast(0))
            if (edit.inputs.size > 1) CompactDropdown(edit.inputs.indices.toList(), activeInputs, { selectedInputs = it },
                itemLabel = { tr("physics.inputs") + " ${it + 1}" }, modifier = Modifier.fillMaxWidth(), height = 22.dp)
            edit.inputs.forEachIndexed { index, input ->
                if (index != activeInputs) return@forEachIndexed
                fun updated(next: PhysicsInput) = edit.copy(inputs = edit.inputs.mapIndexed { i, old -> if (i == index) next else old }).toJson()
                choice("physics.inputs", parameterIds.filter { id -> id == input.parameter || edit.inputs.none { it.parameter == id } }, input.parameter, ::parameterName) { updated(input.copy(parameter = it)) }
                number("physics.weight", input.weight, 0f..100f) { updated(input.copy(weight = it)) }
                choice("modifier.physics.sourceType", PhysicsSourceType.entries, input.type, { tr("physics.type.${it.name.lowercase()}") }) { updated(input.copy(type = it)) }
                toggle("modifier.physics.reflect", input.reflect) { updated(input.copy(reflect = it)) }
                if (edit.inputs.size > 1) CompactButton(tr("physics.remove"), { change(edit.copy(inputs = edit.inputs.filterIndexed { i, _ -> i != index }).toJson()) }, enabled = enabled, height = 20.dp)
            }
            parameterIds.firstOrNull { id -> edit.inputs.none { it.parameter == id } }?.let { id ->
                if (edit.inputs.size < RigPhysicsEdit.MAX_LINKS) CompactButton(tr("physics.addInput"), { change(edit.copy(inputs = edit.inputs + PhysicsInput(id)).toJson()) }, enabled = enabled, height = 20.dp)
            }
            var selectedOutputs by remember(instanceId) { mutableIntStateOf(0) }
            val activeOutputs = selectedOutputs.coerceIn(0, edit.outputs.lastIndex.coerceAtLeast(0))
            if (edit.outputs.size > 1) CompactDropdown(edit.outputs.indices.toList(), activeOutputs, { selectedOutputs = it },
                itemLabel = { tr("physics.outputs") + " ${it + 1}" }, modifier = Modifier.fillMaxWidth(), height = 22.dp)
            edit.outputs.forEachIndexed { index, output ->
                if (index != activeOutputs) return@forEachIndexed
                fun updated(next: PhysicsOutput) = edit.copy(outputs = edit.outputs.mapIndexed { i, old -> if (i == index) next else old }).toJson()
                choice("physics.outputs", parameterIds.filter { id -> id == output.parameter || edit.outputs.none { it.parameter == id } }, output.parameter, ::parameterName) { updated(output.copy(parameter = it)) }
                choice("physics.vertex", (1..edit.segments.size).toList(), output.vertex, Int::toString) { updated(output.copy(vertex = it)) }
                number("physics.outputScale", output.scale, -100f..100f) { updated(output.copy(scale = it)) }
                number("physics.weight", output.weight, 0f..100f) { updated(output.copy(weight = it)) }
                choice("modifier.physics.sourceType", PhysicsSourceType.entries, output.type, { tr("physics.type.${it.name.lowercase()}") }) { updated(output.copy(type = it)) }
                toggle("modifier.physics.reflect", output.reflect) { updated(output.copy(reflect = it)) }
                if (edit.outputs.size > 1) CompactButton(tr("physics.remove"), { change(edit.copy(outputs = edit.outputs.filterIndexed { i, _ -> i != index }).toJson()) }, enabled = enabled, height = 20.dp)
            }
            parameterIds.firstOrNull { id -> edit.outputs.none { it.parameter == id } }?.let { id ->
                if (edit.outputs.size < RigPhysicsEdit.MAX_LINKS) CompactButton(tr("physics.addOutput"), { change(edit.copy(outputs = edit.outputs + PhysicsOutput(id)).toJson()) }, enabled = enabled, height = 20.dp)
            }
            choice("swing.segments", (1..RigPhysicsEdit.MAX_SEGMENTS).toList(), edit.segments.size, Int::toString) { edit.withSegmentCount(it).toJson() }
            var selectedSegments by remember(instanceId) { mutableIntStateOf(0) }
            val activeSegments = selectedSegments.coerceIn(0, edit.segments.lastIndex.coerceAtLeast(0))
            if (edit.segments.size > 1) CompactDropdown(edit.segments.indices.toList(), activeSegments, { selectedSegments = it },
                itemLabel = { tr("physics.vertex") + " ${it + 1}" }, modifier = Modifier.fillMaxWidth(), height = 22.dp)
            edit.segments.forEachIndexed { index, segment ->
                if (index != activeSegments) return@forEachIndexed
                fun updated(next: PhysicsSegment) = edit.copy(segments = edit.segments.mapIndexed { i, old -> if (i == index) next else old }).toJson()
                Text(tr("physics.vertex") + " ${index + 1}", color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.caption)
                number("physics.length", segment.length, .01f..100f) { updated(segment.copy(length = it)) }
                number("physics.shakiness", segment.mobility, 0f..1f) { updated(segment.copy(mobility = it)) }
                number("physics.reactionSpeed", segment.delay, .01f..3f) { updated(segment.copy(delay = it)) }
                number("physics.convergenceSpeed", segment.acceleration, 0f..5f) { updated(segment.copy(acceleration = it)) }
            }
            val normalization = edit.normalization
            number("modifier.physics.positionMin", normalization.positionMin, minOf(-100f, normalization.positionMin)..minOf(normalization.positionDefault, normalization.positionMax - .001f)) { edit.copy(normalization = normalization.copy(positionMin = it)).toJson() }
            number("modifier.physics.positionDefault", normalization.positionDefault, normalization.positionMin..normalization.positionMax) { edit.copy(normalization = normalization.copy(positionDefault = it)).toJson() }
            number("modifier.physics.positionMax", normalization.positionMax, maxOf(normalization.positionDefault, normalization.positionMin + .001f)..maxOf(100f, normalization.positionMax)) { edit.copy(normalization = normalization.copy(positionMax = it)).toJson() }
            number("modifier.physics.angleMin", normalization.angleMin, minOf(-180f, normalization.angleMin)..minOf(normalization.angleDefault, normalization.angleMax - .001f)) { edit.copy(normalization = normalization.copy(angleMin = it)).toJson() }
            number("modifier.physics.angleDefault", normalization.angleDefault, normalization.angleMin..normalization.angleMax) { edit.copy(normalization = normalization.copy(angleDefault = it)).toJson() }
            number("modifier.physics.angleMax", normalization.angleMax, maxOf(normalization.angleDefault, normalization.angleMin + .001f)..maxOf(180f, normalization.angleMax)) { edit.copy(normalization = normalization.copy(angleMax = it)).toJson() }
        }
        "simulation" -> {
            val edit = RigSimEdit.fromJson(config)
            val stack = state.rigEdits.modifierStack?.let(io.github.psd2live.core.modifier.RigModifierStack::fromJson)
            val preceding = stack?.instances.orEmpty().takeWhile { it.id != instanceId }
            val motionIds = state.rigEdits.motionClips.filter { it.enabled }.map { it.id } +
                preceding.filter { it.enabled && it.type == "motion" }.map { it.outputId("motion") }
            val motions = state.previewModel?.rig?.modifierOutput?.clips.orEmpty()
                .filter { it.id in motionIds }.associate { it.id to it.name }
            for ((motion, weight) in edit.trainingClips) {
                Text(motions[motion] ?: motion, color = if (motion in motions) LocalToolColors.current.textPrimary else LocalToolColors.current.warning,
                    style = LocalToolTypography.current.caption)
                number("sim.trainingClips", weight, RigSimEdit.TRAINING_WEIGHTS) {
                    edit.copy(trainingClips = edit.trainingClips + (motion to it)).toJson()
                }
                CompactButton(tr("physics.remove"), {
                    change(edit.copy(trainingClips = edit.trainingClips - motion).toJson())
                }, enabled = enabled, height = 20.dp)
            }
            val trainable = motions.keys.filter { it !in edit.trainingClips }
            if (trainable.isNotEmpty()) {
                choice("sim.trainingClips", listOf<String?>(null) + trainable, null,
                    { it?.let { id -> motions.getValue(id) } ?: tr("sim.addTrainingClip") }) { motion ->
                    if (motion == null) config else edit.copy(trainingClips = edit.trainingClips + (motion to .5f)).toJson()
                }
            }
            val obstacleMeshes = state.previewModel?.rig?.puppet?.drawables.orEmpty()
                .filter { it.id.raw !in edit.targets && (it.mesh?.vertexCount ?: 0) > 0 }
            val obstacleIds = obstacleMeshes.map { it.id.raw }
            fun obstacleName(id: String) = obstacleMeshes.firstOrNull { it.id.raw == id }?.name ?: id
            edit.colliders.forEachIndexed { index, collider ->
                fun updated(next: SimCollider) = edit.copy(colliders = edit.colliders.mapIndexed { i, old ->
                    if (i == index) next else old
                }).toJson()
                choice("modifier.simulation.collider", (obstacleIds + collider.mesh).distinct(), collider.mesh, ::obstacleName) { mesh ->
                    if (mesh == collider.mesh) config else updated(collider.copy(mesh = mesh, a = 0, b = 0))
                }
                val vertices = obstacleMeshes.firstOrNull { it.id.raw == collider.mesh }?.mesh?.vertexCount ?: 0
                if (vertices > 0) {
                    ModifierNumber(tr("modifier.simulation.vertexA"), collider.a.toFloat(), 0f..(vertices - 1).toFloat(), "", enabled, vm, integer = true) { value, expected, done ->
                        commit(updated(collider.copy(a = value.toInt())), expected, done)
                    }
                    ModifierNumber(tr("modifier.simulation.vertexB"), collider.b.toFloat(), 0f..(vertices - 1).toFloat(), "", enabled, vm, integer = true) { value, expected, done ->
                        commit(updated(collider.copy(b = value.toInt())), expected, done)
                    }
                }
                number("modifier.simulation.radiusA", collider.radius, .01f..maxOf(1000f, collider.radius)) { updated(collider.copy(radius = it)) }
                number("modifier.simulation.radiusB", collider.radiusB, .01f..maxOf(1000f, collider.radiusB)) { updated(collider.copy(radiusB = it)) }
                number("modifier.simulation.friction", collider.friction, 0f..1f) { updated(collider.copy(friction = it)) }
                CompactButton(tr("physics.remove"), {
                    change(edit.copy(colliders = edit.colliders.filterIndexed { i, _ -> i != index }).toJson())
                }, enabled = enabled, height = 20.dp)
            }
            if (obstacleIds.isNotEmpty()) {
                choice("modifier.simulation.addCollider", listOf<String?>(null) + obstacleIds, null,
                    { it?.let(::obstacleName) ?: tr("modifier.simulation.addCollider") }) { mesh ->
                    if (mesh == null) config else edit.copy(colliders = edit.colliders + SimCollider(mesh, 0, radius = 1f)).toJson()
                }
            }
            val vertexGroups = state.previewModel?.rig?.puppet?.vertexGroups.orEmpty()
            for (kind in listOf(VertexGroupKind.PIN, VertexGroupKind.STIFFNESS, VertexGroupKind.GOAL,
                VertexGroupKind.MASS, VertexGroupKind.DAMPING, VertexGroupKind.WIND)) {
                val selected = edit.groups[kind]
                val names = vertexGroups.filter { it.drawableId.raw in edit.targets && it.kind == kind }
                    .map { it.name }.plus(listOfNotNull(selected)).distinct()
                choice("sim.group.${kind.jsonName}", listOf<String?>(null) + names, selected,
                    { it ?: tr("modifier.simulation.firstGroup") }) { name ->
                    edit.copy(groups = if (name == null) edit.groups - kind else edit.groups + (kind to name)).toJson()
                }
            }
            choice("modifier.simulation.kind", SimKind.entries, edit.kind, { tr("sim.kind.${it.jsonName}") }) {
                edit.copy(kind = it, material = SimMaterial.preset(it)).toJson()
            }
            val presets = SimMaterialPreset.of(edit.kind)
            choice("sim.material", listOf<SimMaterialPreset?>(null) + presets, SimMaterialPreset.matching(edit.material),
                { if (it == null) tr("sim.preset.custom") else tr("sim.preset.${it.jsonName}") }) {
                edit.copy(material = it?.material ?: edit.material).toJson()
            }
            val material = edit.material
            number("sim.mass", material.mass, .01f..10f) { edit.copy(material = material.copy(mass = it)).toJson() }
            number("sim.stretch", material.stretch, 0f..1f) { edit.copy(material = material.copy(stretch = it)).toJson() }
            number("sim.bend", material.bend, 0f..1f) { edit.copy(material = material.copy(bend = it)).toJson() }
            number("sim.damping", material.damping, 0f..10f) { edit.copy(material = material.copy(damping = it)).toJson() }
            number("sim.goal", material.goal, 0f..1f) { edit.copy(material = material.copy(goal = it)).toJson() }
            number("sim.slack", material.slack, 0f..1f) { edit.copy(material = material.copy(slack = it)).toJson() }
            number("sim.area", material.area, 0f..1f) { edit.copy(material = material.copy(area = it)).toJson() }
            number("sim.anisotropy", material.anisotropy, 0f..1f) { edit.copy(material = material.copy(anisotropy = it)).toJson() }
            choice("sim.modes", (1..RigSimEdit.MAX_MODES).toList(), edit.modes, Int::toString) { edit.copy(modes = it).toJson() }
            choice("sim.keys", RigSimEdit.KEY_COUNTS.filter { it % 2 == 1 }, edit.keys, Int::toString) { edit.copy(keys = it).toJson() }
            number("sim.exaggeration", edit.exaggeration, RigSimEdit.EXAGGERATIONS) { edit.copy(exaggeration = it).toJson() }
            toggle("sim.autoBake", edit.autoBake) { edit.copy(autoBake = it).toJson() }
            choice("sim.blendShapes", listOf(null, true, false), edit.blendShapes, {
                tr(when (it) {
                    null -> "sim.blendShapesAuto"
                    true -> "sim.blendShapesOn"
                    false -> "sim.blendShapesOff"
                })
            }) { edit.copy(blendShapes = it).toJson() }
            val staticInputs = edit.staticInputs.orEmpty()
            for (parameter in staticInputs) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                    Text(parameterName(parameter), color = LocalToolColors.current.textPrimary,
                        style = LocalToolTypography.current.caption, modifier = Modifier.weight(1f))
                    CompactButton(tr("physics.remove"), {
                        change(edit.copy(staticInputs = (staticInputs - parameter).ifEmpty { null }).toJson())
                    }, enabled = enabled, height = 20.dp)
                }
            }
            val availableStatics = parameterIds.filter { it !in staticInputs && it !in edit.bake?.parameters.orEmpty() }
            if (staticInputs.size < RigSimEdit.MAX_STATIC_INPUTS && availableStatics.isNotEmpty()) {
                choice("sim.staticInputs", listOf<String?>(null) + availableStatics, null,
                    { it?.let(::parameterName) ?: tr("sim.addStaticInput") }) { parameter ->
                    if (parameter == null) config else edit.copy(staticInputs = staticInputs + parameter).toJson()
                }
            }
            edit.inputs.forEachIndexed { index, input ->
                fun updated(next: PhysicsInput): JsonObject {
                    val changed = edit.withInputs(edit.inputs.mapIndexed { i, old -> if (i == index) next else old })
                    return (if (input.parameter in edit.forcedInputs) changed.copy(forcedInputs = (changed.forcedInputs + next.parameter).distinct()) else changed).toJson()
                }
                choice("sim.inputs", parameterIds.filter { id -> id == input.parameter || edit.inputs.none { it.parameter == id } }, input.parameter, ::parameterName) { updated(input.copy(parameter = it)) }
                number("physics.weight", input.weight, 0f..100f) { updated(input.copy(weight = it)) }
                choice("modifier.physics.sourceType", PhysicsSourceType.entries, input.type, { tr("physics.type.${it.name.lowercase()}") }) { updated(input.copy(type = it)) }
                toggle("modifier.physics.reflect", input.reflect) { updated(input.copy(reflect = it)) }
                toggle("sim.inputKeep", input.parameter in edit.forcedInputs) { keep ->
                    edit.copy(forcedInputs = if (keep) (edit.forcedInputs + input.parameter).distinct() else edit.forcedInputs - input.parameter).toJson()
                }
                if (edit.inputs.size > 1) CompactButton(tr("physics.remove"), { change(edit.withInputs(edit.inputs.filterIndexed { i, _ -> i != index }).toJson()) }, enabled = enabled, height = 20.dp)
            }
            parameterIds.firstOrNull { id -> edit.inputs.none { it.parameter == id } }?.let { id ->
                CompactButton(tr("physics.addInput"), { change(edit.copy(inputs = edit.inputs + PhysicsInput(id)).toJson()) }, enabled = enabled, height = 20.dp)
            }
        }
        "motion" -> {
            val clip = MotionClips.fromJson(config)
            number("animation.duration", clip.duration, .01f..120f) { MotionClips.toJson(MotionKeyEdits.withDuration(clip, it)) }
            number("export.setting.clip_fps", clip.fps, 1f..120f) { MotionClips.toJson(clip.copy(fps = it)) }
            number("animation.fadeInTime", clip.fadeIn, 0f..10f) { MotionClips.toJson(clip.copy(fadeIn = it, runtimeDefaultFades = false)) }
            number("animation.fadeOutTime", clip.fadeOut, 0f..10f) { MotionClips.toJson(clip.copy(fadeOut = it, runtimeDefaultFades = false)) }
            toggle("animation.loop", clip.loop) { MotionClips.toJson(clip.copy(loop = it)) }
            var selectedTrack by remember(clip.id) { mutableStateOf(clip.curves.firstOrNull()?.parameterId) }
            val activeTrack = selectedTrack?.takeIf { selected -> clip.curves.any { it.parameterId == selected } } ?: clip.curves.firstOrNull()?.parameterId
            if (activeTrack != null) CompactDropdown(clip.curves.map { it.parameterId }, activeTrack, { selectedTrack = it },
                itemLabel = ::parameterName, modifier = Modifier.fillMaxWidth(), height = 22.dp)
            clip.curves.forEachIndexed { index, curve ->
                if (curve.parameterId != activeTrack) return@forEachIndexed
                fun updated(next: MotionCurve) = MotionClips.toJson(clip.copy(curves = clip.curves.mapIndexed { i, old -> if (i == index) next else old }))
                choice("animation.editor.searchParameter", parameterIds.filter { id -> id == curve.parameterId || clip.curves.none { it.parameterId == id } }, curve.parameterId, ::parameterName) { parameter ->
                    updated(curve.copy(parameterId = parameter))
                }
                var selectedKey by remember(clip.id, curve.parameterId) { mutableIntStateOf(0) }
                val activeKey = selectedKey.coerceIn(0, curve.keys.lastIndex.coerceAtLeast(0))
                if (curve.keys.isNotEmpty()) CompactDropdown(curve.keys.indices.toList(), activeKey, { selectedKey = it },
                    itemLabel = { "${it + 1} · ${curve.keys[it].time}" }, modifier = Modifier.fillMaxWidth(), height = 22.dp)
                curve.keys.forEachIndexed keys@ { keyIndex, key ->
                    if (keyIndex != activeKey) return@keys
                    fun keyed(next: MotionKey) = updated(curve.copy(keys = curve.keys.mapIndexed { i, old -> if (i == keyIndex) next else old }))
                    Text("${keyIndex + 1}", color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.caption)
                    val lower = curve.keys.getOrNull(keyIndex - 1)?.time ?: 0f
                    val upper = curve.keys.getOrNull(keyIndex + 1)?.time ?: clip.duration
                    if (lower < upper) number("animation.editor.time", key.time, lower..upper) { keyed(key.copy(time = it)) }
                    val parameter = parameters.firstOrNull { it.id.raw == curve.parameterId }
                    val limits = parameter?.let(MotionLoops::keyLimits) ?: (-100f..100f)
                    number("animation.editor.value", key.value, limits) { keyed(key.copy(value = it)) }
                    choice("animation.editor.interpolation", MotionInterpolation.entries, key.interpolation, { tr("animation.interp.${it.name.lowercase()}") }) { keyed(key.copy(interpolation = it)) }
                    val handleRange = maxOf(limits.endInclusive - limits.start, kotlin.math.abs(key.inHandle.y), kotlin.math.abs(key.outHandle.y), 1f)
                    if (keyIndex > 0 && curve.keys[keyIndex - 1].interpolation == MotionInterpolation.BEZIER) {
                        number("modifier.motion.inTime", key.inHandle.x, 0f..1f) { keyed(key.copy(inHandle = key.inHandle.copy(x = it))) }
                        number("modifier.motion.inValue", key.inHandle.y, -handleRange..handleRange) { keyed(key.copy(inHandle = key.inHandle.copy(y = it))) }
                    }
                    if (keyIndex < curve.keys.lastIndex && key.interpolation == MotionInterpolation.BEZIER) {
                        number("modifier.motion.outTime", key.outHandle.x, 0f..1f) { keyed(key.copy(outHandle = key.outHandle.copy(x = it))) }
                        number("modifier.motion.outValue", key.outHandle.y, -handleRange..handleRange) { keyed(key.copy(outHandle = key.outHandle.copy(y = it))) }
                    }
                    if (curve.keys.size > 1) CompactButton(tr("animation.editor.deleteKeys"), { change(updated(curve.copy(keys = curve.keys.filterIndexed { i, _ -> i != keyIndex }))) }, enabled = enabled, height = 20.dp)
                }
                val spans = (listOf(0f) + curve.keys.map { it.time } + clip.duration).distinct().sorted().zipWithNext()
                spans.maxByOrNull { it.second - it.first }?.takeIf { it.second - it.first > MotionClips.TIME_EPSILON * 2 }?.let { (start, end) ->
                    CompactButton(tr("modifier.motion.addKey"), {
                        val time = (start + end) / 2f
                        change(MotionClips.toJson(MotionKeyEdits.setKey(clip, curve.parameterId, MotionKey(time, MotionClips.sample(curve, time)))))
                    }, enabled = enabled, height = 20.dp)
                }
                if (clip.curves.size > 1) CompactButton(tr("animation.editor.removeTrack"), { change(MotionClips.toJson(clip.copy(curves = clip.curves.filterIndexed { i, _ -> i != index }))) }, enabled = enabled, height = 20.dp)
            }
            var eventsOpen by remember(clip.id) { mutableStateOf(false) }
            PresetFolderRow(tr("animation.events"), tr("modifier.count", clip.events.size), eventsOpen, true) { eventsOpen = !eventsOpen }
            if (eventsOpen) {
                clip.events.forEachIndexed { index, event ->
                    fun updated(next: MotionEvent) = MotionClips.toJson(clip.copy(events = MotionClips.normalizedEvents(
                        clip.events.mapIndexed { i, old -> if (i == index) next else old })))
                    number("animation.editor.time", event.time, 0f..clip.duration) { updated(event.copy(time = it)) }
                    var value by remember(clip.id, index, event.value) { mutableStateOf(event.value) }
                    var expected by remember { mutableStateOf<String?>(null) }
                    CompactTextField(value, { value = it.take(MotionEvent.MAX_VALUE_LENGTH) }, placeholder = tr("animation.eventValue"),
                        enabled = enabled, modifier = Modifier.fillMaxWidth(), onEditStart = { expected = vm.modifierEditState() },
                        onEditEnd = {
                            if (value != event.value) commit(updated(event.copy(value = value)), expected) { failure ->
                                if (failure != null) value = event.value
                            }
                        })
                    CompactButton(tr("animation.eventDelete"), {
                        change(MotionClips.toJson(clip.copy(events = clip.events.filterIndexed { i, _ -> i != index })))
                    }, enabled = enabled, height = 20.dp)
                }
                if (clip.events.size < MotionEvent.MAX_PER_CLIP) {
                    val free = if (clip.events.none { it.time < MotionClips.TIME_EPSILON }) 0f else
                        (listOf(0f) + clip.events.map { it.time } + clip.duration).sorted().zipWithNext()
                            .maxByOrNull { it.second - it.first }?.takeIf { it.second - it.first > MotionClips.TIME_EPSILON * 2 }
                            ?.let { (it.first + it.second) / 2f }
                    CompactButton(tr("modifier.motion.addEvent"), {
                        free?.let { change(MotionClips.toJson(clip.copy(events = MotionClips.normalizedEvents(clip.events + MotionEvent(it, ""))))) }
                    }, enabled = enabled && free != null, height = 20.dp)
                }
            }
            parameterIds.firstOrNull { id -> clip.curves.none { it.parameterId == id } }?.let { id ->
                CompactButton(tr("animation.editor.addTrack"), {
                    val value = parameters.first { it.id.raw == id }.default
                    change(MotionClips.toJson(clip.copy(curves = clip.curves + MotionCurve(id, listOf(MotionKey(0f, value))))))
                }, enabled = enabled, height = 20.dp)
            }
        }
    }
}
