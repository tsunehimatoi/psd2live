package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspaceModifierPlan
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import kotlinx.serialization.json.*

internal fun modifierName(type: String): String = when {
    type == RigSourceModifiers.SCAFFOLD -> tr("modifier.scaffold")
    '/' in type -> tr("settings.rig.area.${type.substringBefore('/')}") + " · " + tr("settings.rig.base.${type.replace('/', '.')}")
    '.' in type -> tr("settings.rig.module.$type")
    else -> tr("modifier.type.$type")
}

private fun contentName(state: PSD2LiveState, kind: String, id: String): String {
    val puppet = state.previewModel?.rig?.puppet
    return when (kind) {
        "mesh" -> puppet?.drawables?.singleOrNull { it.id.raw == id }?.name
        "warp", "deformer" -> puppet?.deformers?.singleOrNull { it.id.raw == id }?.name
        "part" -> puppet?.parts?.singleOrNull { it.id.raw == id }?.name
        "parameter" -> puppet?.parameters?.singleOrNull { it.id.raw == id }?.name
        else -> null
    } ?: when {
        kind == "hierarchy" && id in setOf("root", "parameters") -> tr("modifier.hierarchy.$id")
        kind == "relations" && id == "parameter-links" -> tr("modifier.relation.parameters")
        else -> id
    }
}

@Composable
internal fun ModifierStackPanel(state: PSD2LiveState, vm: PSD2LiveViewModel) {
    val stack = remember(state.rigEdits.modifierStack) { RigModifierStack.fromJson(requireNotNull(state.rigEdits.modifierStack)) }
    val reports = state.previewModel?.rig?.modifierReport?.get("entries")?.jsonArray.orEmpty().associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
    val colors = LocalToolColors.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragState by remember { mutableStateOf<String?>(null) }
    val enabled = !state.isBusy && !state.workspaceEditBusy && !state.editorDraftBusy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        val labels = listOf(tr("modifier.add"), tr("modifier.addGroup"))
        PanelToolbar(labels = labels, iconCount = 4) { labelsShown ->
            PanelToolButton(labels[0], labelsShown > 0, { vm.openModifierAddition() }, enabled, labels[0]) { IconAdd() }
            PanelToolButton(labels[1], labelsShown > 1, { vm.openModifierAddition(group = true) }, enabled, labels[1]) { IconFolder() }
            Spacer(Modifier.weight(1f))
            PanelExpandCollapseButtons({ stack.instances.forEach { expanded[it.id] = true } }, { expanded.clear() })
        }
        if (stack.instances.isEmpty()) Text(tr("modifier.empty"), color = colors.textMuted, style = LocalToolTypography.current.caption,
            modifier = Modifier.padding(Spacing.Margin))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            stack.instances.forEachIndexed { index, instance -> key(instance.id) {
                if (dragging != null && destination == index) Divider(color = colors.accent, thickness = 2.dp)
                val dragHandle = Modifier.pointerInput(instance.id, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(onDragStart = { offset ->
                        dragging = instance.id; destination = index; dragState = vm.modifierEditState()
                        dragY = (bounds[instance.id]?.top ?: 0f) + offset.y
                    }, onDragCancel = { dragging = null; destination = null }, onDragEnd = {
                        val to = destination
                        if (to != null && to != index) vm.editModifier("modifier_move", buildJsonObject { put("id", instance.id); put("index", to) }, dragState)
                        dragging = null; destination = null
                    }, onDrag = { change, amount ->
                        change.consume(); dragY += amount.y
                        destination = stack.instances.indices.minByOrNull { row -> bounds[stack.instances[row].id]?.let { kotlin.math.abs(it.center.y - dragY) } ?: Float.MAX_VALUE }
                    })
                }
                Column(Modifier.fillMaxWidth().onGloballyPositioned { bounds[instance.id] = it.boundsInParent() }
                    .border(1.dp, if (dragging == instance.id) colors.accent else colors.border, RoundedCornerShape(Corners.Control))
                    .background(colors.panelElevated, RoundedCornerShape(Corners.Control)).padding(Spacing.Gap)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                        CompactIconButton({}, modifier = dragHandle, enabled = enabled, tooltip = tr("modifier.reorder"), size = 20.dp) { IconDragHandle(tint = colors.textMuted) }
                        CompactIconButton({ expanded[instance.id] = expanded[instance.id] != true }, tooltip = tr("modifier.contents"), size = 20.dp) {
                            IconChevron(expanded[instance.id] == true)
                        }
                        CompactCheckbox(instance.enabled, { value -> vm.editModifier("modifier_enable", buildJsonObject { put("id", instance.id); put("enabled", value) }) }, enabled = enabled)
                        Text(instance.config["name"]?.jsonPrimitive?.contentOrNull ?: modifierName(instance.type), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = colors.textPrimary, style = LocalToolTypography.current.body)
                        val status = reports[instance.id]?.get("status")?.jsonPrimitive?.content ?: "loading"
                        Text(tr("modifier.status.$status"), color = if (status == "missing_dependency") colors.warning else colors.textMuted,
                            style = LocalToolTypography.current.caption)
                        CompactIconButton({ vm.editModifier("modifier_remove", buildJsonObject { put("id", instance.id) }) }, tooltip = tr("modifier.remove"), enabled = enabled, size = 20.dp) { IconClose() }
                    }
                    Text(tr("modifier.targets", instance.targets.joinToString { contentName(state, it.kind, it.id) }),
                        style = LocalToolTypography.current.caption, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AnimatedVisibility(expanded[instance.id] == true, enter = expandVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)), exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing))) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                            instance.presetGroup?.let { Text(tr("modifier.origin", if (it in setOf("fitted", "custom")) tr("modifier.group.$it") else it), style = LocalToolTypography.current.caption, color = colors.textMuted) }
                            val report = reports[instance.id]
                            val missing = report?.get("missing_dependencies")?.jsonArray.orEmpty()
                            if (missing.isNotEmpty()) Text(tr("modifier.dependencies", missing.joinToString { it.jsonPrimitive.content.let { dependency ->
                                if (dependency.startsWith("parameter:")) dependency.removePrefix("parameter:") else modifierName(dependency)
                            } }), color = colors.warning, style = LocalToolTypography.current.caption)
                            instance.targets.forEach { target -> CompactButton(contentName(state, target.kind, target.id),
                                { vm.locateModifierContent(target.kind, target.id) }, height = 20.dp) }
                            ModifierDependencies(instance, stack, enabled, vm)
                            ModifierSourceValues(instance, enabled, vm)
                            ModifierModulePhysicsValues(instance, report, state, enabled, vm)
                            ModifierNativeValues(instance.type, instance.config, state, enabled, vm) { config, expected, done ->
                                vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", config) }, expected, done)
                            }
                            report?.let { ModifierContributions(it, state, vm) }
                            CompactButton(tr("modifier.apply"), { vm.editModifier("modifier_apply", buildJsonObject { put("id", instance.id) }) }, enabled = enabled, height = 22.dp)
                        }
                    }
                }
            } }
        }
    }
}

@Composable
private fun ModifierDependencies(instance: RigModifierInstance, stack: RigModifierStack, enabled: Boolean, vm: PSD2LiveViewModel) {
    val spec = RigLegacyBindings.find(instance.type) ?: return
    val capabilities = stack.appliedCapabilities + stack.instances.take(stack.index(instance.id)).map {
        ModifierCapability(it.type, it.version, it.targets, it.id, it.config)
    }
    val providers = RigLegacyBindings.providers(instance)
    for ((type, selected) in providers) {
        val candidates = capabilities.filter { cap -> cap.type == type && cap.id != null && instance.targets.all { it in cap.targets } &&
            (type == RigSourceModifiers.SCAFFOLD || cap.config["scaffold"] == instance.config["scaffold"]) }
        val ids = (listOf(selected) + candidates.mapNotNull { it.id }).distinct()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            Text(modifierName(type), style = LocalToolTypography.current.caption, color = LocalToolColors.current.textMuted, modifier = Modifier.weight(1f))
            CompactDropdown(ids, selected, { next ->
                if (next != selected) {
                    val field = when (type) { RigSourceModifiers.SCAFFOLD -> "scaffold"; spec.module?.base -> "base"; else -> "dependencies" }
                    val value = if (field == "dependencies") JsonObject(instance.config[field]?.jsonObject.orEmpty() + (type to JsonPrimitive(next))) else JsonPrimitive(next)
                    vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", JsonObject(instance.config + (field to value))) })
                }
            }, itemLabel = { id ->
                if (candidates.none { it.id == id }) tr("modifier.provider.missing", id) else "${modifierName(type)} · $id"
            }, itemEnabled = { id -> candidates.any { it.id == id } }, enabled = enabled, height = 20.dp, modifier = Modifier.weight(1.5f))
        }
    }
}

@Composable
private fun ModifierModulePhysicsValues(instance: RigModifierInstance, report: JsonObject?, state: PSD2LiveState, enabled: Boolean, vm: PSD2LiveViewModel) {
    if (RigLegacyBindings.find(instance.type)?.hasPhysics != true || report?.get("status")?.jsonPrimitive?.content != "active") return
    val rig = state.previewModel?.rig?.modifierOutput ?: return
    val groups = rig.physics.groups.associateBy { it.id }
    val candidates = report.getValue("contributions").jsonArray.mapNotNull { raw ->
        val item = raw.jsonObject
        val slot = item["physics_slot"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val group = groups[item.getValue("id").jsonPrimitive.content] ?: return@mapNotNull null
        slot to group
    }
    var open by remember(instance.id) { mutableStateOf(false) }
    PresetFolderRow(tr("swing.physics"), tr("modifier.count", candidates.size), open, true) { open = !open }
    if (open) candidates.forEach { (slot, actual) ->
        Text(actual.name, style = LocalToolTypography.current.caption, color = LocalToolColors.current.textMuted)
        ModifierNativeValues("physics", io.github.psd2live.core.Physics3Json.setting(actual).toJson(), state, enabled, vm,
            editName = actual.id == instance.outputId("physics:$slot")) { next, expected, done ->
            val overrides = JsonObject(instance.config["physics"]?.jsonObject.orEmpty() + (slot to next))
            vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type)
                put("config", JsonObject(instance.config + ("physics" to overrides))) }, expected, done)
        }
    }
}

@Composable
private fun ModifierSourceValues(instance: RigModifierInstance, enabled: Boolean, vm: PSD2LiveViewModel) {
    if (instance.type == RigSourceModifiers.SCAFFOLD) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactCheckbox(instance.config["featureDisplacement"]?.jsonPrimitive?.booleanOrNull == true, { value ->
                vm.editModifier("modifier_update", buildJsonObject {
                    put("id", instance.id); put("type", instance.type)
                    put("config", JsonObject(instance.config + ("featureDisplacement" to JsonPrimitive(value))))
                })
            }, enabled = enabled)
            Text(tr("model.deformer.featureDisplacement"), style = LocalToolTypography.current.caption)
        }
        return
    }
    val spec = RigLegacyBindings.find(instance.type) ?: return
    if (spec.mouthOutline) {
        val color = RigLegacyBindings.outlineColor(instance)
        var hex by remember(instance.id, color) { mutableStateOf(color?.let { "%06X".format(it) }.orEmpty()) }
        fun setColor(rgb: Int?) {
            vm.editModifier("modifier_update", buildJsonObject {
                put("id", instance.id); put("type", instance.type)
                put("config", JsonObject(if (rgb == null) instance.config - "color" else instance.config + ("color" to JsonPrimitive(rgb))))
            }) { failure -> if (failure != null) hex = color?.let { "%06X".format(it) }.orEmpty() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactTextField(hex, { value ->
                hex = value
                val digits = value.trim().removePrefix("#")
                if (digits.length == 6) digits.toIntOrNull(16)?.takeIf { it >= 0 && it != color }?.let(::setColor)
            }, enabled = enabled, placeholder = "#RRGGBB", isMono = true, modifier = Modifier.width(80.dp), height = 20.dp)
            CompactButton(tr("mouth.autoColor"), { setColor(null) }, enabled = enabled && color != null, height = 20.dp)
        }
    }
    var advanced by remember(instance.id) { mutableStateOf(false) }
    fun config(field: String, key: String?, value: Float): JsonObject = if (key == null) JsonObject(instance.config + (field to JsonPrimitive(value)))
        else JsonObject(instance.config + (field to JsonObject(instance.config[field]?.jsonObject.orEmpty() + (key to JsonPrimitive(value)))))
    @Composable fun value(label: String, field: String, key: String?, default: Float, range: ClosedFloatingPointRange<Float>, unit: String) {
        val current = if (key == null) instance.config[field]?.jsonPrimitive?.float else instance.config[field]?.jsonObject?.get(key)?.jsonPrimitive?.float
        ModifierNumber(label, current ?: default, range, unit, enabled, vm) { number, expected, done ->
            vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", config(field, key, number)) }, expected, done)
        }
    }
    if (spec.strength) value(tr("modifier.strength"), "strength", null, 1f, 0f..2f, "")
    var valuesOpen by remember(instance.id) { mutableStateOf(false) }
    if (spec.knobs.isNotEmpty() || spec.fields.isNotEmpty()) {
        PresetFolderRow(tr("settings.rig.values"), tr("modifier.count", spec.knobs.size + spec.fields.size), valuesOpen, true) { valuesOpen = !valuesOpen }
        if (valuesOpen) {
            val hasTurnStyle = spec.type == "face.depth-turn"
            if (hasTurnStyle) {
                val style = instance.config["values"]?.jsonObject?.get("style")?.jsonPrimitive?.float ?: 0f
                TurnStyleRow(style, enabled) { index ->
                    val values = buildJsonObject {
                        spec.knobs.forEach { knob -> put(knob.id, if (knob.id == "style") index.toFloat() else knob.default) }
                    }
                    vm.editModifier("modifier_update", buildJsonObject {
                        put("id", instance.id)
                        put("type", instance.type)
                        put("config", JsonObject(instance.config + ("values" to values)))
                    })
                }
            }
            spec.knobs.filter { (advanced || !it.advanced) && !(hasTurnStyle && it.id == "style") }.forEach { knob ->
                val label = when {
                    spec.seeThrough -> tr("seeThrough.opacity")
                    spec.mouthOutline -> tr("mouth.thickness")
                    else -> tr("settings.rig.knob.${instance.type.replace('/', '.')}.${knob.id}")
                }
                value(label, "values", knob.id, knob.default, knob.range, unit(knob.unit))
            }
            spec.fields.filter { advanced || !it.advanced }.forEach { field ->
                value(tr("settings.rigTuning.${field.id}"), "tuning", field.id, field.default, field.range, unit(field.unit))
            }
            if (spec.fields.any { it.advanced } || spec.knobs.any { it.advanced }) CompactButton(tr("settings.rigTuning.advanced"), { advanced = !advanced }, height = 20.dp)
        }
    }
}

private fun unit(unit: RigTuning.Unit): String = when (unit) {
    RigTuning.Unit.DEGREES -> "°"
    RigTuning.Unit.PERCENT -> "%"
    RigTuning.Unit.TORSO_LENGTHS -> tr("settings.unit.x")
    RigTuning.Unit.PIXELS -> tr("settings.unit.px")
}

/** Keep each continuous field local; submit its final value once against the state captured on press/focus. */
@Composable
internal fun ModifierNumber(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, enabled: Boolean,
                           vm: PSD2LiveViewModel, commit: (Float, String?, (String?) -> Unit) -> Unit) {
    var draft by remember { mutableFloatStateOf(value) }
    var editing by remember { mutableStateOf(false) }
    val actualValue by rememberUpdatedState(value)
    var expected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(value) { if (!editing) draft = value }
    val start = { if (!editing) { editing = true; expected = vm.modifierEditState() } }
    val finish = { if (editing) { editing = false; if (draft != value) commit(draft, expected) { failure -> if (failure != null) draft = actualValue } } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        Text(label, modifier = Modifier.weight(1f), style = LocalToolTypography.current.caption, color = LocalToolColors.current.textPrimary, maxLines = 2)
        CompactSlider(draft, { draft = it }, start, finish, valueRange = range, enabled = enabled, modifier = Modifier.weight(1f))
        CompactNumberSpinner(draft.toDouble(), { draft = it.toFloat() }, min = range.start.toDouble(), max = range.endInclusive.toDouble(),
            decimals = if (unit == "%") 0 else 2, unit = unit, enabled = enabled, height = 20.dp, modifier = Modifier.width(68.dp), onEditStart = start, onEditEnd = finish)
    }
}

@Composable
internal fun ModifierContributions(report: JsonObject, state: PSD2LiveState, vm: PSD2LiveViewModel) {
    val colors = LocalToolColors.current
    val folded = remember(report.getValue("id")) { mutableStateMapOf<String, Boolean>() }
    val contributions = report.getValue("contributions").jsonArray.map { it.jsonObject }
    for ((kind, items) in contributions.groupBy { it.getValue("kind").jsonPrimitive.content }) {
        val open = folded[kind] ?: (kind == "relations" || kind == "parameter" && items.any { it["change"]?.jsonPrimitive?.content != "reused" })
        PresetFolderRow(tr("modifier.kind.$kind"), tr("modifier.count", items.size), open, true) { folded[kind] = !open }
        if (open) items.forEach { item ->
            val id = item.getValue("id").jsonPrimitive.content
            val mask = item["mask"]?.jsonObject
            val target = mask?.getValue("target")?.jsonPrimitive?.content
            val label = if (mask != null) tr("modifier.relation.mask", item["name"]?.jsonPrimitive?.content ?: contentName(state, "mesh", target!!))
                else item["name"]?.jsonPrimitive?.content ?: contentName(state, kind, id)
            val present = if (mask != null) state.previewModel?.rig?.puppet?.drawables?.any { it.id.raw == target } == true
                else if (kind == "mesh") state.previewModel?.rig?.puppet?.drawables?.any { it.id.raw == id } == true
                else when (kind) {
                    "deformer" -> state.previewModel?.rig?.puppet?.deformers?.any { it.id.raw == id } == true
                    "part" -> state.previewModel?.rig?.puppet?.parts?.any { it.id.raw == id } == true
                    "parameter" -> state.previewModel?.rig?.puppet?.parameters?.any { it.id.raw == id } == true
                    else -> false
                }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                Text(tr("modifier.change.${item.getValue("change").jsonPrimitive.content}"), color = colors.textMuted, style = LocalToolTypography.current.caption)
                if (present) CompactButton(label, { vm.locateModifierContent(if (mask != null) "mesh" else kind, target ?: id) }, height = 20.dp, modifier = Modifier.weight(1f))
                else Text(label, color = colors.textPrimary, style = LocalToolTypography.current.caption, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (mask != null) Column(Modifier.fillMaxWidth().padding(start = Spacing.Margin), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                for (stage in listOf("before", "after")) {
                    val sources = mask.getValue(stage).jsonArray.map { it.jsonPrimitive.content }
                    Text(tr("modifier.relation.$stage") + if (mask.getValue(stage + "_inverted").jsonPrimitive.boolean) " · " + tr("inspector.invertMask") else "",
                        color = colors.textMuted, style = LocalToolTypography.current.caption)
                    if (sources.isEmpty()) Text(tr("modifier.relation.none"), color = colors.textMuted, style = LocalToolTypography.current.caption)
                    sources.forEach { source ->
                        if (state.previewModel?.rig?.puppet?.drawables?.any { it.id.raw == source } == true)
                            CompactButton(contentName(state, "mesh", source), { vm.locateModifierContent("mesh", source) }, height = 20.dp)
                        else Text(source, color = colors.textPrimary, style = LocalToolTypography.current.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    val forms = report.getValue("owned_keyforms").jsonArray
    if (forms.isNotEmpty()) {
        val open = folded["keyforms"] == true
        PresetFolderRow(tr("modifier.keyforms"), tr("modifier.count", forms.size), open, true) { folded["keyforms"] = !open }
        if (open) forms.forEach { element -> val form = element.jsonObject
            val kind = form.getValue("kind").jsonPrimitive.content; val id = form.getValue("id").jsonPrimitive.content
            CompactButton("${form["object_name"]?.jsonPrimitive?.content ?: contentName(state, kind, id)} · ${form["parameter_name"]?.jsonPrimitive?.content ?: contentName(state, "parameter", form.getValue("parameter").jsonPrimitive.content)}" +
                (form["key"]?.jsonObject?.let { key -> " · " + key.entries.joinToString { (parameter, value) -> contentName(state, "parameter", parameter) + ": " + value.jsonPrimitive.content } } ?: "") +
                (form["channel"]?.let { channel ->
                    val name = when (val raw = channel.jsonPrimitive.content) { "draw_order" -> "drawOrder"; "multiply_color" -> "multiplyColor"; "screen_color" -> "screenColor"; else -> raw }
                    " · ${tr("keyform.channel.$name")}"
                } ?: ""), { vm.locateModifierContent(kind, id) }, height = 20.dp)
        }
    }
    report.getValue("override_issues").jsonArray.forEach { raw -> val issue = raw.jsonObject
        Text(tr("modifier.override.${issue.getValue("kind").jsonPrimitive.content}", issue.getValue("target").jsonPrimitive.content), color = colors.warning, style = LocalToolTypography.current.caption)
    }
}

@Composable
internal fun ModifierAdditionDialog(state: PSD2LiveState, vm: PSD2LiveViewModel) {
    val dialog = vm.modifierAddition ?: return
    val colors = LocalToolColors.current
    val plan = dialog.plan
    val ready = !dialog.loading && dialog.error == null && dialog.preview != null
    ModalDialogFrame(title = tr(if (dialog.group == null) "modifier.add" else "modifier.addGroup"), onDismiss = vm::dismissModifierAddition,
        width = 560.dp, dismissible = !dialog.committing, onConfirm = if (ready) vm::confirmModifierAddition else null,
        footer = { CompactButton(tr("action.cancel"), vm::dismissModifierAddition, enabled = !dialog.committing)
            CompactButton(tr("modifier.add"), vm::confirmModifierAddition, enabled = ready, isPrimary = true) }) {
        Text(tr("modifier.capturedTargets", dialog.targets.size), color = colors.textMuted, style = LocalToolTypography.current.caption)
        if (dialog.group == null) CompactDropdown(if (dialog.targets.all { it.kind == "mesh" }) WorkspaceModifierPlan.types else listOf("swing"), dialog.type ?: "body/legacy",
            { vm.previewModifierAddition(it, null) }, itemLabel = ::modifierName, enabled = !dialog.committing, modifier = Modifier.fillMaxWidth())
        else Text(tr("modifier.group.fitted"), style = LocalToolTypography.current.body, color = colors.textPrimary)
        Text(dialog.targets.joinToString { contentName(state, it.kind, it.id) }, style = LocalToolTypography.current.caption, color = colors.textMuted)
        if (dialog.loading) Text(tr(if (dialog.committing) "modifier.adding" else "modifier.previewing"), color = colors.textMuted, style = LocalToolTypography.current.caption)
        dialog.error?.let { Text(it, color = colors.warning, style = LocalToolTypography.current.caption) }
        val configuration = dialog.configuration ?: plan?.get("entries")?.jsonArray?.lastOrNull()?.jsonObject?.get("config")?.jsonObject
        if (dialog.type == "motion") {
            val available = remember(state.previewModel) { state.previewModel?.let(WorkspaceModifierPlan::selectableMotionPresets).orEmpty() }
            val selected = configuration?.get("id")?.jsonPrimitive?.content?.takeIf { it in available }
                ?: configuration?.get("name")?.jsonPrimitive?.content
            if (available.isNotEmpty()) CompactDropdown(available, selected ?: available.first(),
                { vm.previewModifierAddition("motion", null, preset = it) }, itemLabel = { preset ->
                    val name = preset.removePrefix(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX)
                    if (name == preset) builtinMotionTitle(name)
                    else "${tr("settings.rig.base.motion.natural")} · ${builtinMotionTitle(name)}"
                }, enabled = !dialog.loading && !dialog.committing, modifier = Modifier.fillMaxWidth())
        }
        if (dialog.type == "physics") {
            val presets = WorkspaceModifierPlan.physicsPresets().associateBy { it.id }
            if (presets.isNotEmpty()) {
                val selected = configuration?.get("id")?.jsonPrimitive?.content?.takeIf { it in presets } ?: "custom"
                CompactDropdown(listOf("custom") + presets.keys, selected,
                    { vm.previewModifierAddition("physics", null, preset = it.takeUnless { id -> id == "custom" }) },
                    itemLabel = { presets[it]?.name ?: tr("modifier.group.custom") },
                    enabled = !dialog.loading && !dialog.committing, modifier = Modifier.fillMaxWidth())
            }
        }
        if (configuration != null && dialog.type in WorkspaceModifierPlan.nativeTypes) {
            ModifierNativeValues(dialog.type!!, configuration, state, !dialog.loading && !dialog.committing, vm) { next, _, done ->
                vm.previewModifierAddition(dialog.type, null, next)
                done(null)
            }
        }
        plan?.get("plan")?.jsonArray?.forEach { raw -> val item = raw.jsonObject
            Text("${tr("modifier.plan.${item.getValue("disposition").jsonPrimitive.content}")} · ${modifierName(item.getValue("type").jsonPrimitive.content)}",
                color = colors.textPrimary, style = LocalToolTypography.current.body)
        }
        if (plan?.get("entries")?.jsonArray?.isEmpty() == true) Text(tr("modifier.noAdditions"), color = colors.textMuted, style = LocalToolTypography.current.caption)
        dialog.preview?.get("modifiers")?.jsonArray?.filter { raw -> plan?.get("entries")?.jsonArray.orEmpty().any {
            it.jsonObject.getValue("id") == raw.jsonObject.getValue("id")
        } }?.forEach { report ->
            Divider(color = colors.border)
            Text(modifierName(report.jsonObject.getValue("type").jsonPrimitive.content), style = LocalToolTypography.current.body, color = colors.textPrimary)
            ModifierContributions(report.jsonObject, state, vm)
        }
    }
}
