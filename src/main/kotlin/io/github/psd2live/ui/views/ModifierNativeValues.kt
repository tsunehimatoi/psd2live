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

/** The same native configuration controls are used before insertion and inside a persisted instance. */
@Composable
internal fun ModifierNativeValues(type: String, config: JsonObject, state: PSD2LiveState, enabled: Boolean,
                                  vm: PSD2LiveViewModel, editName: Boolean = true, commit: (JsonObject, String?, (String?) -> Unit) -> Unit) {
    fun change(next: JsonObject) = commit(next, vm.modifierEditState()) {}
    @Composable fun number(label: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> JsonObject) {
        ModifierNumber(tr(label), value, range, "", enabled, vm) { next, expected, done -> commit(update(next), expected, done) }
    }
    @Composable fun <T> choice(label: String, values: List<T>, selected: T, display: (T) -> String, update: (T) -> JsonObject) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            Text(tr(label), color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.caption, modifier = Modifier.weight(1f))
            CompactDropdown(values, selected, { change(update(it)) }, itemLabel = display, enabled = enabled, height = 20.dp, modifier = Modifier.weight(1.5f))
        }
    }
    @Composable fun toggle(label: String, value: Boolean, update: (Boolean) -> JsonObject) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            CompactCheckbox(value, { change(update(it)) }, enabled = enabled)
            Text(tr(label), color = LocalToolColors.current.textPrimary, style = LocalToolTypography.current.caption)
        }
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
            edit.inputs.forEachIndexed { index, input ->
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
            edit.outputs.forEachIndexed { index, output ->
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
            edit.segments.forEachIndexed { index, segment ->
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
            number("animation.fadeInTime", clip.fadeIn, 0f..10f) { MotionClips.toJson(clip.copy(fadeIn = it)) }
            number("animation.fadeOutTime", clip.fadeOut, 0f..10f) { MotionClips.toJson(clip.copy(fadeOut = it)) }
            toggle("animation.loop", clip.loop) { MotionClips.toJson(clip.copy(loop = it)) }
            val expanded = remember(clip.id) { mutableStateMapOf<String, Boolean>() }
            clip.curves.forEachIndexed { index, curve ->
                val open = expanded[curve.parameterId] == true
                PresetFolderRow(parameterName(curve.parameterId), tr("modifier.count", curve.keys.size), open, true) { expanded[curve.parameterId] = !open }
                if (!open) return@forEachIndexed
                fun updated(next: MotionCurve) = MotionClips.toJson(clip.copy(curves = clip.curves.mapIndexed { i, old -> if (i == index) next else old }))
                choice("animation.editor.searchParameter", parameterIds.filter { id -> id == curve.parameterId || clip.curves.none { it.parameterId == id } }, curve.parameterId, ::parameterName) { parameter ->
                    updated(curve.copy(parameterId = parameter))
                }
                curve.keys.forEachIndexed { keyIndex, key ->
                    fun keyed(next: MotionKey) = updated(curve.copy(keys = curve.keys.mapIndexed { i, old -> if (i == keyIndex) next else old }))
                    Text("${keyIndex + 1}", color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.caption)
                    val lower = curve.keys.getOrNull(keyIndex - 1)?.time ?: 0f
                    val upper = curve.keys.getOrNull(keyIndex + 1)?.time ?: clip.duration
                    if (lower < upper) number("animation.editor.time", key.time, lower..upper) { keyed(key.copy(time = it)) }
                    val parameter = parameters.firstOrNull { it.id.raw == curve.parameterId }
                    val limits = parameter?.let(MotionLoops::keyLimits) ?: (-100f..100f)
                    number("animation.editor.value", key.value, limits) { keyed(key.copy(value = it)) }
                    choice("animation.editor.interpolation", MotionInterpolation.entries, key.interpolation, { tr("animation.interp.${it.name.lowercase()}") }) { keyed(key.copy(interpolation = it)) }
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
            parameterIds.firstOrNull { id -> clip.curves.none { it.parameterId == id } }?.let { id ->
                CompactButton(tr("animation.editor.addTrack"), {
                    val value = parameters.first { it.id.raw == id }.default
                    change(MotionClips.toJson(clip.copy(curves = clip.curves + MotionCurve(id, listOf(MotionKey(0f, value))))))
                }, enabled = enabled, height = 20.dp)
            }
        }
    }
}
