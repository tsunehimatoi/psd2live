package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideItem
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.*
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
        kind == "relations" && id == "parameter-roles" -> tr("modifier.relation.roles")
        else -> id
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModifierStackPanel(state: PSD2LiveState, vm: PSD2LiveViewModel) {
    val stack = remember(state.rigEdits.modifierStack) { RigModifierStack.fromJson(requireNotNull(state.rigEdits.modifierStack)) }
    val reports = state.previewModel?.rig?.modifierReport?.get("entries")?.jsonArray.orEmpty()
        .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
    val colors = LocalToolColors.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    val selected = stack.instances.firstOrNull { it.id == selectedId } ?: stack.instances.firstOrNull()
    val bounds = remember { mutableStateMapOf<String, ParamItemLayout>() }
    val drag = remember { ParameterDragState() }
    val preview = rememberGraphicsLayer()
    val scroll = rememberScrollState()
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var expected by remember { mutableStateOf<String?>(null) }
    val enabled = !state.isBusy && !state.workspaceEditBusy && !state.editorDraftBusy
    LaunchedEffect(stack, enabled) { drag.clear() }
    Column(Modifier.fillMaxWidth()) {
        val labels = listOf(tr("modifier.add"), tr("modifier.addGroup"))
        PanelToolbar(labels = labels, iconCount = 2) { labelsShown ->
            PanelToolButton(labels[0], labelsShown > 0, { vm.openModifierAddition() }, enabled, labels[0]) { IconAdd() }
            PanelToolButton(labels[1], labelsShown > 1, { vm.openModifierAddition(group = true) }, enabled, labels[1]) { IconFolder() }
        }
        if (stack.instances.isEmpty()) Text(tr("modifier.empty"), color = colors.textMuted, style = LocalToolTypography.current.caption,
            modifier = Modifier.padding(Spacing.Margin))
        Box(Modifier.fillMaxWidth().heightIn(max = 216.dp).onGloballyPositioned { container = it }
            .onPointerEvent(PointerEventType.Move) { event ->
                event.changes.firstOrNull()?.let { drag.onMove(it.position, bounds.values) }
            }
            .onPointerEvent(PointerEventType.Release) { event ->
                if (event.button == PointerButton.Primary && drag.isPressed) drag.onRelease { item, target ->
                    val remaining = stack.instances.filterNot { it.id == item.id }
                    val destination = when (target) {
                        is ParamDropTarget.Before -> remaining.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }
                        is ParamDropTarget.Append -> remaining.size
                        else -> null
                    }
                    if (enabled && destination != null && destination != stack.index(item.id))
                        vm.editModifier("modifier_move", buildJsonObject { put("id", item.id); put("index", destination) }, expected)
                }
            }) {
            MenuGlide(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = Spacing.Margin)) {
                Column(Modifier.fillMaxWidth()) {
                    stack.instances.forEach { instance -> key(instance.id) {
                        val name = instance.config["name"]?.jsonPrimitive?.contentOrNull ?: modifierName(instance.type)
                        val layout = ParamItemLayout(instance.id, instance.id, "modifier", name, null, false, emptySet(), 0f, 0f)
                        DisposableEffect(instance.id) { onDispose { bounds.remove(instance.id) } }
                        var labelCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val status = reports[instance.id]?.get("status")?.jsonPrimitive?.content ?: "loading"
                        val interaction = remember { MutableInteractionSource() }
                        DisposableEffect(instance.id) { onDispose { bounds.remove(instance.id) } }
                        Row(Modifier.fillMaxWidth().height(24.dp)
                            .parameterDragRow(layout, drag, bounds.values, preview)
                            .onGloballyPositioned { row ->
                                val parent = container
                                if (parent != null && parent.isAttached && row.isAttached) {
                                    val top = parent.localPositionOf(row, Offset.Zero).y
                                    bounds[instance.id] = layout.copy(top = top, bottom = top + row.size.height)
                                }
                            }
                            .background(if (selected?.id == instance.id) colors.selection else Color.Transparent)
                            .glideItem(enabled = !drag.isDragging)
                            .clickable(interactionSource = interaction, indication = null) {
                                if (drag.suppressClick) drag.suppressClick = false
                                else { selectedId = instance.id; page = 0 }
                            }.padding(horizontal = Spacing.Gap), verticalAlignment = Alignment.CenterVertically) {
                            CompactCheckbox(instance.enabled, { value ->
                                vm.editModifier("modifier_enable", buildJsonObject { put("id", instance.id); put("enabled", value) })
                            }, enabled = enabled)
                            Text(name, style = LocalToolTypography.current.body,
                                color = if (instance.enabled) colors.textPrimary else colors.textMuted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).handCursor().onGloballyPositioned { labelCoords = it }.onPointerEvent(PointerEventType.Press) { event ->
                                    val row = labelCoords; val parent = container
                                    if (enabled && event.button == PointerButton.Primary && row != null && parent != null && row.isAttached && parent.isAttached) {
                                        bounds[instance.id]?.let { item ->
                                            drag.onPress(item, parent.localPositionOf(row, event.changes.first().position))
                                            selectedId = instance.id; expected = vm.modifierEditState()
                                        }
                                    }
                                })
                            if (status != "active") Text(tr("modifier.status.$status"), style = LocalToolTypography.current.caption,
                                color = if (status == "missing_dependency") colors.warning else colors.textMuted)
                            CompactIconButton({ vm.editModifier("modifier_remove", buildJsonObject { put("id", instance.id) }) },
                                enabled = enabled, tooltip = tr("modifier.remove"), size = 20.dp) { IconClose() }
                        }
                    } }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
            ParameterDragPreview(drag, preview)
        }
        if (selected != null) key(selected.id) {
            Divider(color = colors.divider)
            CompactTabBar(listOf(tr("modifier.adjust"), tr("modifier.targetList"), tr("modifier.details")), page, { page = it })
            Column(Modifier.fillMaxWidth().padding(Spacing.Margin), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val report = reports[selected.id]
                val missing = report?.get("missing_dependencies")?.jsonArray.orEmpty()
                if (missing.isNotEmpty()) InspectorHint(tr("modifier.dependencies", missing.joinToString { it.jsonPrimitive.content }), warning = true)
                when (page) {
                    0 -> {
                        ModifierSourceValues(selected, enabled, vm)
                        ModifierModulePhysicsValues(selected, report, state, enabled, vm)
                        ModifierNativeValues(selected.type, selected.config, state, enabled, vm, instanceId = selected.id) { config, captured, done ->
                            vm.editModifier("modifier_update", buildJsonObject { put("id", selected.id); put("type", selected.type); put("config", config) }, captured, done)
                        }
                    }
                    1 -> selected.targets.forEach { target ->
                        CompactButton(contentName(state, target.kind, target.id), { vm.locateModifierContent(target.kind, target.id) }, height = 20.dp)
                    }
                    2 -> {
                        ModifierDependencies(selected, stack, enabled, vm)
                        report?.let { ModifierContributions(it, state, vm) }
                        CompactButton(tr("modifier.apply"), { vm.editModifier("modifier_apply", buildJsonObject { put("id", selected.id) }) }, enabled = enabled, height = 22.dp)
                    }
                }
            }
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
        val candidates = capabilities.filter { cap -> RigModifierAlgorithms.registry.provides(cap.type, type) && cap.id != null && instance.targets.all { it in cap.targets } &&
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
                if (candidates.none { it.id == id }) tr("modifier.provider.missing", id) else stack.instances.firstOrNull { it.id == id }?.let { provider ->
                    provider.config["name"]?.jsonPrimitive?.contentOrNull ?: "${modifierName(provider.type)} · ${stack.index(id) + 1}"
                } ?: modifierName(type)
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
        for ((key, label) in listOf("frontHairSway" to "modifier.frontHairSway", "backHairSway" to "modifier.backHairSway")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckbox(instance.config[key]?.jsonPrimitive?.booleanOrNull != false, { value ->
                    vm.editModifier("modifier_update", buildJsonObject {
                        put("id", instance.id); put("type", instance.type)
                        put("config", JsonObject(instance.config + (key to JsonPrimitive(value))))
                    })
                }, enabled = enabled)
                Text(tr(label), style = LocalToolTypography.current.caption)
            }
        }
        return
    }
    val spec = RigLegacyBindings.find(instance.type) ?: return
    if (spec.hasPhysics) Row(verticalAlignment = Alignment.CenterVertically) {
        CompactCheckbox(instance.config["physicsEnabled"]?.jsonPrimitive?.booleanOrNull != false, { value ->
            vm.editModifier("modifier_update", buildJsonObject {
                put("id", instance.id); put("type", instance.type)
                put("config", JsonObject(instance.config + ("physicsEnabled" to JsonPrimitive(value))))
            })
        }, enabled = enabled)
        Text(tr("swing.physics"), style = LocalToolTypography.current.caption)
    }
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
    fun config(field: String, key: String?, value: Float): JsonObject = if (key == null) JsonObject(instance.config + (field to JsonPrimitive(value)))
        else JsonObject(instance.config + (field to JsonObject(instance.config[field]?.jsonObject.orEmpty() + (key to JsonPrimitive(value)))))
    @Composable fun value(label: String, field: String, key: String?, default: Float, range: ClosedFloatingPointRange<Float>, unit: String) {
        val current = if (key == null) instance.config[field]?.jsonPrimitive?.float else instance.config[field]?.jsonObject?.get(key)?.jsonPrimitive?.float
        ModifierNumber(label, current ?: default, range, unit, enabled, vm) { number, expected, done ->
            vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", config(field, key, number)) }, expected, done)
        }
    }
    if (spec.strength) value(tr("modifier.strength"), "strength", null, 1f, 0f..4f, "")
    if (spec.knobs.isNotEmpty() || spec.fields.isNotEmpty()) {
        run {
            val hasTurnStyle = spec.type == "face.depth-turn" || spec.type == RigLegacyBindings.FITTED_FACE
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
            spec.knobs.filterNot { hasTurnStyle && it.id == "style" }.forEach { knob ->
                val label = when {
                    spec.seeThrough -> tr("seeThrough.opacity")
                    spec.mouthOutline -> tr("mouth.thickness")
                    else -> tr("settings.rig.knob.${if (instance.type == RigLegacyBindings.FITTED_FACE) "face.depth-turn" else instance.type.replace('/', '.')}.${knob.id}")
                }
                value(label, "values", knob.id, knob.default, knob.range, unit(knob.unit))
            }
            val groups = spec.fields.map { it.group }.distinct()
            var selectedGroup by remember(instance.id) { mutableStateOf(groups.firstOrNull()) }
            if (groups.size > 1) CompactDropdown(groups, selectedGroup ?: groups.first(), { selectedGroup = it },
                itemLabel = { tr("settings.rigTuning.group." + it.name.lowercase()) }, modifier = Modifier.fillMaxWidth(), height = 22.dp)
            spec.fields.filter { it.group == selectedGroup }.forEach { field ->
                value(tr("settings.rigTuning.${field.id}"), "tuning", field.id, field.default, field.range, unit(field.unit))
            }
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
                           vm: PSD2LiveViewModel, integer: Boolean = false, commit: (Float, String?, (String?) -> Unit) -> Unit) {
    var draft by remember { mutableFloatStateOf(value) }
    var editing by remember { mutableStateOf(false) }
    val actualValue by rememberUpdatedState(value)
    var expected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(value) { if (!editing) draft = value }
    val start = { if (!editing) { editing = true; expected = vm.modifierEditState() } }
    val finish = { if (editing) { editing = false; if (draft != value) commit(draft, expected) { failure -> if (failure != null) draft = actualValue } } }
    InspectorFormRow(label) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        CompactSlider(draft, { draft = if (integer) kotlin.math.round(it) else it }, start, finish, valueRange = range, enabled = enabled, modifier = Modifier.weight(1f))
        CompactNumberSpinner(draft.toDouble(), { draft = if (integer) kotlin.math.round(it).toFloat() else it.toFloat() }, min = range.start.toDouble(), max = range.endInclusive.toDouble(),
            decimals = if (integer || unit == "%") 0 else 2, unit = unit, enabled = enabled, height = 20.dp, modifier = Modifier.width(68.dp), onEditStart = start, onEditEnd = finish)
    }
    }
}

@Composable
internal fun ModifierContributions(report: JsonObject, state: PSD2LiveState, vm: PSD2LiveViewModel) {
    val colors = LocalToolColors.current
    val folded = remember(report.getValue("id")) { mutableStateMapOf<String, Boolean>() }
    val contributions = report.getValue("contributions").jsonArray.map { it.jsonObject }
    for ((kind, items) in contributions.groupBy { it.getValue("kind").jsonPrimitive.content }) {
        val open = folded[kind] == true
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
    var advanced by remember(dialog.type, dialog.group) { mutableStateOf(false) }
    var showContributions by remember(dialog.type, dialog.group) { mutableStateOf(false) }
    ModalDialogFrame(title = tr(if (dialog.group == null) "modifier.add" else "modifier.addGroup"), onDismiss = vm::dismissModifierAddition,
        width = if (dialog.group == null) 680.dp else 560.dp, dismissible = !dialog.committing, onConfirm = if (ready) vm::confirmModifierAddition else null,
        footer = { CompactButton(tr("action.cancel"), vm::dismissModifierAddition, enabled = !dialog.committing)
            CompactButton(tr("modifier.add"), vm::confirmModifierAddition, enabled = ready, isPrimary = true) }) {
        Text(tr("modifier.targetCount", dialog.targets.size), color = colors.textMuted, style = LocalToolTypography.current.caption)
        if (dialog.group == null) {
            val available = if (dialog.targets.all { it.kind == "mesh" }) WorkspaceModifierPlan.types else listOf("swing")
            fun category(type: String): String = when {
                type.endsWith("/legacy") && type != "body/legacy" -> "legacy"
                type == RigSourceModifiers.SCAFFOLD -> "structure"
                type in WorkspaceModifierPlan.nativeTypes -> type
                else -> RigLegacyBindings.spec(type).area
            }
            fun categoryName(id: String): String = when (id) {
                "legacy" -> tr("modifier.algorithm.legacy")
                "structure" -> tr("modifier.scaffold")
                in WorkspaceModifierPlan.nativeTypes -> tr("modifier.type.$id")
                else -> tr("settings.rig.area.$id")
            }
            val categories = available.map(::category).distinct().sortedBy { it == "legacy" }
            var selectedCategory by remember { mutableStateOf(category(dialog.type ?: available.first())) }
            Row(Modifier.fillMaxWidth().height(238.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.Margin)) {
                MenuGlide(Modifier.width(124.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Column {
                        categories.forEach { id -> key(id) {
                            CompactMenuItem(categoryName(id), { selectedCategory = id }, active = selectedCategory == id,
                                enabled = !dialog.committing, animateArrival = false)
                        } }
                    }
                }
                Divider(Modifier.fillMaxHeight().width(1.dp), color = colors.divider)
                val listScroll = key(selectedCategory) { rememberScrollState() }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    MenuGlide(Modifier.fillMaxWidth().verticalScroll(listScroll).padding(end = Spacing.Margin)) {
                        Column {
                            available.filter { category(it) == selectedCategory }.forEach { type -> key(type) {
                                CompactMenuItem(modifierName(type), { if (type != dialog.type) vm.previewModifierAddition(type, null) },
                                    active = dialog.type == type, enabled = !dialog.committing, animateArrival = false)
                            } }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
                }
            }
            Divider(color = colors.divider)
            dialog.type?.let { Text(modifierName(it), style = LocalToolTypography.current.body, color = colors.textPrimary) }
        }
        else {
            val selectedGroup = WorkspaceModifierPlan.groupCatalog.firstOrNull { it.id == dialog.group }
                ?: WorkspaceModifierPlan.groupCatalog.first { it.id == WorkspaceModifierPlan.RECOMMENDED_GROUP }
            InspectorFormRow(tr("modifier.algorithm")) {
                CompactDropdown(listOf("legacy", "modern"), selectedGroup.algorithm,
                    { algorithm -> vm.previewModifierAddition(null, "$algorithm-${selectedGroup.richness}") },
                    itemLabel = { tr("modifier.algorithm.$it") }, enabled = !dialog.committing, modifier = Modifier.fillMaxWidth())
            }
            InspectorFormRow(tr("modifier.richness")) {
                CompactDropdown(listOf("minimal", "standard", "full"), selectedGroup.richness,
                    { richness -> vm.previewModifierAddition(null, "${selectedGroup.algorithm}-$richness") },
                    itemLabel = { tr("modifier.richness.$it") }, enabled = !dialog.committing, modifier = Modifier.fillMaxWidth())
            }
        }
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
            ModifierNativeValues(dialog.type!!, configuration, state, !dialog.loading && !dialog.committing, vm, quickOnly = !advanced) { next, _, done ->
                vm.previewModifierAddition(dialog.type, null, next)
                done(null)
            }
        }
        if (configuration != null && dialog.type in WorkspaceModifierPlan.nativeTypes)
            CompactButton(tr(if (advanced) "modifier.less" else "modifier.more"), { advanced = !advanced }, height = 20.dp)
        plan?.get("plan")?.jsonArray?.forEach { raw -> val item = raw.jsonObject
            Text("${tr("modifier.plan.${item.getValue("disposition").jsonPrimitive.content}")} · ${modifierName(item.getValue("type").jsonPrimitive.content)}",
                color = colors.textPrimary, style = LocalToolTypography.current.body)
        }
        if (plan?.get("entries")?.jsonArray?.isEmpty() == true) Text(tr("modifier.noAdditions"), color = colors.textMuted, style = LocalToolTypography.current.caption)
        CompactButton(tr("modifier.details"), { showContributions = !showContributions }, height = 20.dp)
        if (showContributions) dialog.preview?.get("modifiers")?.jsonArray?.filter { raw -> plan?.get("entries")?.jsonArray.orEmpty().any {
            it.jsonObject.getValue("id") == raw.jsonObject.getValue("id")
        } }?.forEach { report ->
            Divider(color = colors.border)
            Text(modifierName(report.jsonObject.getValue("type").jsonPrimitive.content), style = LocalToolTypography.current.body, color = colors.textPrimary)
            ModifierContributions(report.jsonObject, state, vm)
        }
    }
}
