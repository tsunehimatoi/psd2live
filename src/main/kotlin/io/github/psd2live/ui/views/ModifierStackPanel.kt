package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.WorkspaceModifierPlan
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.availability
import io.github.psd2live.ui.theme.*
import kotlinx.serialization.json.*

internal fun modifierName(type: String): String {
    val registry = RigModifierAlgorithms.registry
    val stored = registry.storedType(type)
    if (stored != type) return tr("modifier.algorithm.version", modifierName(stored), requireNotNull(registry.defaultDefinition(type)).version)
    if (type in setOf(RigSourceModifiers.EYE, RigSourceModifiers.BROW, RigSourceModifiers.MOUTH, RigSourceModifiers.FACE, RigSourceModifiers.BODY, RigSourceModifiers.HAIR)) {
        return tr("settings.rig.area.$type")
    }
    ModifierPluginCatalog.algorithm(type)?.let { return it.second.name }
    return when {
        type == RigSourceModifiers.SCAFFOLD -> tr("modifier.scaffold")
        '/' in type && RigLegacyBindings.find(type) == null -> type
        '/' in type -> tr("settings.rig.area.${type.substringBefore('/')}") + " · " + tr("settings.rig.base.${type.replace('/', '.')}")
        '.' in type -> tr("settings.rig.module.$type")
        else -> tr("modifier.type.$type")
    }
}

internal fun contentName(state: PSD2LiveState, kind: String, id: String): String {
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
    var editOrder by rememberSaveable { mutableStateOf(false) }
    val selected = stack.instances.firstOrNull { it.id == selectedId } ?: stack.instances.firstOrNull()
    val enabled = !state.isBusy && !state.workspaceEditBusy && !state.editorDraftBusy
    var listRatio by rememberSaveable { mutableFloatStateOf(0.38f) }
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        val labels = listOf(tr("modifier.add"), tr("modifier.switch"))
        PanelToolbar(labels = labels, iconCount = 3) { labelsShown ->
            Box {
                PanelToolButton(labels[0], labelsShown > 0, { vm.openModifierAddition() }, enabled, labels[0]) { IconAdd() }
                if (vm.modifierAddition?.group == null && vm.modifierAddition != null) ModifierAdditionMenu(state, vm)
            }
            Box {
                PanelToolButton(labels[1], labelsShown > 1, { vm.openModifierAddition(group = true) }, enabled, labels[1]) { IconFolder() }
                if (vm.modifierAddition?.group != null) ModifierAdditionMenu(state, vm)
            }
            PresetLibraryControls(state, null, enabled)
        }
        CompactTabBar(listOf(tr("modifier.mode.settings"), tr("modifier.mode.ordering")), if (editOrder) 1 else 0, { editOrder = it == 1 })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val extent = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val minimum = (with(density) { 120.dp.toPx() } / extent).coerceAtMost(0.4f)
            val maximum = 1f - (with(density) { 180.dp.toPx() } / extent).coerceAtMost(0.4f)
            Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(listRatio.coerceIn(minimum, maximum)).fillMaxHeight()) {
                ModifierStackList(stack, reports, vm, editOrder, enabled, selected?.id) { selectedId = it; page = 0 }
            }
            Box(Modifier.panelDivider(horizontal = true) { delta ->
                listRatio = (listRatio.coerceIn(minimum, maximum) + delta / extent).coerceIn(minimum, maximum)
            }.width(Spacing.Gap).fillMaxHeight())
            Column(Modifier.weight(1f - listRatio.coerceIn(minimum, maximum)).fillMaxHeight()) {
                    if (selected != null) key(selected.id) {
                        CompactTabBar(listOf(tr("modifier.adjust"), tr("modifier.targetList"), tr("modifier.details")), page, { page = it })
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.Margin), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val report = reports[selected.id]
                            val missing = report?.get("missing_dependencies")?.jsonArray.orEmpty()
                            if (missing.isNotEmpty()) InspectorHint(tr("modifier.dependencies", missing.joinToString { it.jsonPrimitive.content }), warning = true)
                            when (page) {
                                0 -> {
                                    ModifierSourceValues(selected, state, enabled, vm)
                                    ModifierModulePhysicsValues(selected, report, state, enabled, vm)
                                    ModifierNativeValues(selected.type, selected.config, state, enabled, vm, instanceId = selected.id) { config, captured, done ->
                                        vm.editModifier("modifier_update", buildJsonObject { put("id", selected.id); put("type", selected.type); put("config", config) }, captured, done)
                                    }
                                }
                                1 -> selected.targets.forEach { target ->
                                    CompactButton(contentName(state, target.kind, target.id), { vm.locateModifierContent(target.kind, target.id) }, height = 20.dp)
                                }
                                2 -> {
                                    ModifierBehaviorPreview(selected.type, selected.version)
                                    ModifierDependencies(selected, stack, enabled, vm)
                                    report?.let { ModifierContributions(it, state, vm) }
                                    CompactButton(tr("modifier.apply"), { vm.editModifier("modifier_apply", buildJsonObject { put("id", selected.id) }) }, enabled = enabled, height = 22.dp)
                                }
                            }
                        }
                    }
            }
            }
        }
    }
}

@Composable
private fun ModifierDependencies(instance: RigModifierInstance, stack: RigModifierStack, enabled: Boolean, vm: PSD2LiveViewModel) {
    val spec = RigLegacyBindings.find(instance.type) ?: return
    val capabilities = stack.appliedCapabilities + stack.instances.take(stack.index(instance.id)).map { it.capability() }
    val providers = RigLegacyBindings.providers(instance)
    for ((type, selected) in providers) {
        val candidates = capabilities.filter { cap -> RigModifierAlgorithms.registry.provides(cap, type) && cap.id != null && instance.targets.all { it in cap.targets } &&
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
    val hasPhysics = RigLegacyBindings.find(instance.type)?.hasPhysics == true ||
        instance.type in setOf(RigSourceModifiers.EYE, RigSourceModifiers.BROW, RigSourceModifiers.BODY, RigSourceModifiers.HAIR)
    if (!hasPhysics || report?.get("status")?.jsonPrimitive?.content != "active") return
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
private fun ModifierSourceValues(instance: RigModifierInstance, state: PSD2LiveState, enabled: Boolean, vm: PSD2LiveViewModel) {
    if (instance.plugin?.let { !it.id.startsWith("builtin.") } == true) {
        ModifierPluginValues(instance, enabled, vm)
        return
    }
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
    if (instance.type in setOf(RigSourceModifiers.EYE, RigSourceModifiers.BROW, RigSourceModifiers.MOUTH, RigSourceModifiers.FACE, RigSourceModifiers.BODY, RigSourceModifiers.HAIR)) {
        ModifierPartValues(instance, state, enabled, vm)
        return
    }
    ModifierBindingValues(instance, enabled, vm) { config, expected, done ->
        vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", config) }, expected, done)
    }
}

@Composable
private fun ModifierBehaviorPreview(type: String, version: Int? = null) {
    val algorithm = remember(type, version) {
        (version?.let { ModifierPluginCatalog.algorithm(type, it) } ?: ModifierPluginCatalog.algorithm(type))?.second
    } ?: return
    if (algorithm.creates.isEmpty() && algorithm.modifies.isEmpty()) return
    val colors = LocalToolColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (algorithm.creates.isNotEmpty()) {
            Text(tr("modifier.behavior.creates", algorithm.creates.joinToString()), color = colors.textMuted,
                style = LocalToolTypography.current.caption)
        }
        if (algorithm.modifies.isNotEmpty()) {
            Text(tr("modifier.behavior.modifies", algorithm.modifies.joinToString()), color = colors.textMuted,
                style = LocalToolTypography.current.caption)
        }
    }
}

internal fun unit(unit: RigTuning.Unit): String = when (unit) {
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
    val finish = { if (editing) { editing = false; if (draft != value) commit(draft, expected) { if (!editing) draft = actualValue } } }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModifierAdditionMenu(state: PSD2LiveState, vm: PSD2LiveViewModel) {
    val dialog = vm.modifierAddition ?: return
    val groups = dialog.group != null
    if (groups) {
        PressMenu(expanded = true, press = Offset.Zero, onDismissRequest = { if (!dialog.committing) vm.dismissModifierAddition() }, width = 260.dp) {
            FloatingMenuSection(tr("modifier.switch.description"))
            val baselines by io.github.psd2live.application.WorkspaceModelPresetLibrary.shared.state.collectAsState()
            GlidingColumn(Modifier.fillMaxWidth(), baselines.size, highlights = { !dialog.committing }) { index ->
                val entry = baselines[index]
                FloatingMenuRow(entry.nameKey?.let { tr(it) } ?: entry.name, { vm.switchModifierPreset(entry.id) }, enabled = !dialog.committing, bar = false)
            }
            if (dialog.committing) FloatingMenuSection(tr("modifier.adding"))
            dialog.error?.let { FloatingMenuRow(tr("modifier.switch"), {}, enabled = false, hint = it) }
        }
        return
    }
    val available = if (groups) WorkspaceModifierPlan.groupCatalog.map { it.id }
        else if (dialog.targets.all { it.kind == "mesh" }) WorkspaceModifierPlan.types.filterNot { it == RigSourceModifiers.SCAFFOLD } else listOf("swing") + ModifierPluginStore.shared.runnablePackages().filter { ModifierPluginStore.shared.resolution(it.reference).available }.flatMap { it.manifest.algorithms }.filter { algorithm -> dialog.targets.all { it.kind in algorithm.targets } }.map { it.type }
    val pluginRevision by ModifierPluginStore.shared.revision.collectAsState()
    val foldedLegacyUiTypes = setOf(
        "eye/fitted", "eye/legacy", "eye.wide", "eye.smile", "eye.emotion", "eye.gaze-lid",
        "eye.highlight", "eye.lid-fade", "eye.squash", "eye.aftershock",
        "brow/fitted", "brow/legacy", "brow.shape", "brow.spring", "brow.blink", "brow.lid-coupling",
        "face/fitted", "face/legacy", "face.depth-turn", "face.neck",
        "body/legacy", "body.bust", "body.arms",
        "hair/chains", "hair/legacy",
    )
    val hideFoldedLegacy = !groups && RigSourceModifiers.EYE in available
    val visibleAvailable = available.filter { type ->
        (groups || state.previewModel?.let { !dialog.availability(it, type).hidden } != false) &&
            (!hideFoldedLegacy || type !in foldedLegacyUiTypes)
    }
    fun category(type: String): String = when {
        groups -> type.substringBefore('-')
        type == RigSourceModifiers.SCAFFOLD -> "structure"
        type == "physics" || type == "simulation" || type == "swing" -> "advanced"
        type == "motion" -> "expression"
        type in setOf(RigSourceModifiers.EYE, RigSourceModifiers.BROW, RigSourceModifiers.MOUTH, RigSourceModifiers.FACE, RigSourceModifiers.BODY, RigSourceModifiers.HAIR) -> type
        else -> RigLegacyBindings.find(RigModifierAlgorithms.registry.storedType(type))?.area ?: "plugins"
    }
    fun categoryName(id: String): String = when {
        id == "legacy" || groups -> tr("modifier.algorithm.$id")
        id == "structure" -> tr("modifier.scaffold")
        id == "advanced" -> tr("modifier.advanced")
        id == "plugins" -> tr("plugins.title")
        id in WorkspaceModifierPlan.nativeTypes -> tr("modifier.type.$id")
        else -> tr("settings.rig.area.$id")
    }
    val categories = (visibleAvailable.map(::category) + listOf("hair", "eye", "expression", "outfit")).distinct().sortedBy { it == "advanced" }
    var selectedCategory by remember { mutableStateOf(category(dialog.group ?: dialog.type ?: visibleAvailable.firstOrNull() ?: "simulation")) }
    PressMenu(expanded = true, press = Offset.Zero,
        onDismissRequest = { if (!dialog.committing) vm.dismissModifierAddition() }, width = 440.dp) {
        Row(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            GlidingColumn(Modifier.width(132.dp).verticalScroll(rememberScrollState()), categories.size,
                highlights = { !dialog.committing },
                onHover = { if (!dialog.committing) selectedCategory = categories[it] }) { index ->
                val id = categories[index]
                FloatingMenuRow(categoryName(id), { selectedCategory = id }, selected = selectedCategory == id, bar = false,
                    enabled = !dialog.committing)
            }
            key(selectedCategory) {
                data class AddRow(val label: String, val action: () -> Unit, val selected: Boolean, val count: Int)
                val rows = mutableListOf<AddRow>()
                fun add(type: String, preset: String?, label: String, selected: Boolean = false, action: () -> Unit) {
                    val availability = state.previewModel?.let { dialog.availability(it, type, preset) }
                    if (availability?.hidden != true) rows += AddRow(label, action, selected, availability?.existingCount ?: 0)
                }
                if (selectedCategory == "outfit") add("simulation", "clothing", tr("modifier.clothing.simulation")) { vm.previewModifierAddition("simulation", null, preset = "clothing") }
                if (selectedCategory == "hair") for (preset in listOf("front_hair", "back_hair")) {
                    add("simulation", preset, tr("modifier.simulation.$preset")) { vm.previewModifierAddition("simulation", null, preset = preset) }
                }
                if (!groups && selectedCategory in setOf("hair", "eye")) {
                    WorkspaceModifierPlan.physicsPresets().filter { preset ->
                        (preset.id == io.github.psd2live.core.PhysicsGenerator.EYE_JELLY_ID) == (selectedCategory == "eye")
                    }.forEach { preset ->
                        add("physics", preset.id, preset.name) { vm.previewModifierAddition("physics", null, preset = preset.id) }
                    }
                }
                if (!groups && selectedCategory == "expression") {
                    val presets = remember(state.previewModel) { state.previewModel?.let(WorkspaceModifierPlan::selectableMotionPresets).orEmpty() }
                    presets.forEach { preset ->
                        val name = preset.removePrefix(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX)
                        add("motion", preset, if (name == preset) builtinMotionTitle(name) else "${tr("settings.rig.base.motion.natural")} · ${builtinMotionTitle(name)}") { vm.previewModifierAddition("motion", null, preset = preset) }
                    }
                }
                visibleAvailable.filter { category(it) == selectedCategory }.forEach { type ->
                    add(type, null, if (groups) tr("modifier.richness.${type.substringAfter('-')}") else modifierName(type),
                        dialog.plan != null && (dialog.group ?: dialog.type) == type) {
                        vm.previewModifierAddition(if (groups) null else type, if (groups) type else null)
                    }
                }
                GlidingColumn(Modifier.weight(1f).verticalScroll(rememberScrollState()), rows.size,
                    highlights = { !dialog.committing }) { index ->
                    val (label, action, selected, count) = rows[index]
                    FloatingMenuRow(label, action, selected = selected, bar = false, enabled = !dialog.committing,
                        trailing = if (count > 0) tr("modifier.add.existing", count) else null)
                }
            }
        }
        if (dialog.loading) FloatingMenuSection(tr(if (dialog.committing) "modifier.adding" else "modifier.previewing"))
        dialog.error?.let { FloatingMenuRow(tr("modifier.add"), {}, enabled = false, hint = it) }
        val planned = dialog.plan?.get("plan")?.jsonArray.orEmpty()
        if (planned.isNotEmpty()) {
            FloatingMenuDivider()
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                planned.forEach { raw -> val item = raw.jsonObject
                    FloatingMenuRow(modifierName(item.getValue("type").jsonPrimitive.content), {}, enabled = false,
                        trailing = tr("modifier.plan.${item.getValue("disposition").jsonPrimitive.content}"))
                }
            }
            (dialog.type ?: planned.lastOrNull()?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)?.let { previewType ->
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.Margin)) {
                    ModifierBehaviorPreview(previewType)
                }
            }
            FloatingMenuRow(tr("modifier.add"), vm::confirmModifierAddition,
                enabled = !dialog.loading && dialog.error == null && dialog.plan?.get("entries")?.jsonArray?.isNotEmpty() == true)
        }
    }
}
