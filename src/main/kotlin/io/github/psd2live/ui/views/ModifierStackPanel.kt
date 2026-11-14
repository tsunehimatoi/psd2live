package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideItem
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
    if (type in RigSourceModifiers.PART_TYPES) {
        return tr("settings.rig.area.$type")
    }
    ModifierPluginCatalog.algorithm(type)?.takeUnless { it.first.reference.id.startsWith("builtin.") }?.let { return it.second.name }
    return when {
        type == RigSourceModifiers.SCAFFOLD -> tr("modifier.scaffold")
        '/' in type && RigLegacyBindings.find(type) == null -> type
        '/' in type -> tr("settings.rig.area.${type.substringBefore('/')}") + " · " + tr("settings.rig.base.${type.replace('/', '.')}")
        '.' in type -> tr("settings.rig.module.$type")
        else -> tr("modifier.type.$type")
    }
}

private fun modifierDescription(type: String, preset: String? = null): String? = when {
    type == "simulation" && preset in setOf("front_hair", "back_hair") -> tr("modifier.simulation.$preset.hint")
    type == "simulation" && preset == "clothing" -> tr("modifier.clothing.simulation.hint")
    type == "physics" && preset == io.github.psd2live.core.PhysicsGenerator.EYE_JELLY_ID -> tr("settings.rig.module.eye.jelly.hint")
    type == "physics" && preset == io.github.psd2live.core.PhysicsGenerator.FRONT_HAIR_ID -> tr("model.physics.frontHair.hint")
    type == "physics" && preset == io.github.psd2live.core.PhysicsGenerator.BACK_HAIR_ID -> tr("model.physics.backHair.hint")
    type == "motion" && preset != null -> if (preset.startsWith(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX)) {
        tr("settings.rig.base.motion.natural.hint")
    } else {
        tr("modifier.motion.preset.hint")
    }
    type in RigSourceModifiers.PART_TYPES -> tr("settings.rig.area.$type.hint")
    '.' in type && RigLegacyBindings.find(type) != null -> tr("settings.rig.module.$type.hint")
    type in WorkspaceModifierPlan.nativeTypes -> tr("modifier.type.$type.hint")
    else -> null
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
                if (selected == null) {
                    Box(Modifier.fillMaxSize().padding(Spacing.Margin), contentAlignment = Alignment.Center) {
                        ModifierCard(Modifier.widthIn(max = 260.dp)) {
                            Text(
                                text = tr("modifier.empty"),
                                style = LocalToolTypography.current.caption,
                                color = colors.textMuted,
                            )
                            CompactButton(
                                text = tr("modifier.add"),
                                onClick = { vm.openModifierAddition() },
                                enabled = enabled,
                                height = 22.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else key(selected.id) {
                    val foldController = remember(selected.id) { ModifierFoldController() }
                    val report = reports[selected.id]
                    val status = report?.get("status")?.jsonPrimitive?.content ?: "loading"
                    val customName = selected.config["name"]?.jsonPrimitive?.contentOrNull
                    val displayName = customName ?: modifierName(selected.type)
                    val typeSubtitle = if (customName != null) modifierName(selected.type) else null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.panelElevated)
                            .padding(horizontal = Spacing.Margin, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                    ) {
                        CompactCheckbox(selected.enabled, { value ->
                            vm.editModifier("modifier_enable", buildJsonObject {
                                put("id", selected.id); put("enabled", value)
                            })
                        }, enabled = enabled)
                        val titleColor = if (selected.enabled) colors.textPrimary else colors.textMuted
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                style = LocalToolTypography.current.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (typeSubtitle != null) {
                                Text(
                                    text = typeSubtitle,
                                    style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp),
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (status != "active" && status != "disabled") {
                            ModifierStatusBadge(status)
                        }
                    }
                    Divider(color = colors.divider, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth().background(colors.panelElevated),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactTabBar(
                            listOf(tr("modifier.adjust"), tr("modifier.targetList"), tr("modifier.details")),
                            page,
                            { page = it },
                            modifier = Modifier.weight(1f),
                        )
                        if (page != 1) {
                            Row(Modifier.padding(end = Spacing.Gap), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                PanelExpandCollapseButtons(
                                    onExpandAll = { foldController.expandAll() },
                                    onCollapseAll = { foldController.collapseAll() },
                                )
                            }
                        }
                    }

                    CompositionLocalProvider(LocalModifierFoldController provides foldController) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.Margin),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Margin),
                        ) {
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
                                1 -> {
                                    if (selected.targets.isEmpty()) {
                                        ModifierCard {
                                            Text(tr("modifier.targets.empty"), style = LocalToolTypography.current.caption, color = colors.textMuted)
                                        }
                                    } else {
                                        for ((kind, targets) in selected.targets.groupBy { it.kind }) {
                                            RememberedModifierSection(
                                                stateKey = "${selected.id}:targets:$kind",
                                                title = tr("modifier.kind.$kind"),
                                                defaultExpanded = true,
                                                summary = tr("modifier.count", targets.size),
                                            ) {
                                                MenuGlide(Modifier.fillMaxWidth()) {
                                                    Column(Modifier.fillMaxWidth()) {
                                                        targets.forEach { target ->
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(24.dp)
                                                                    .glideItem(enabled = true, spanWidth = true)
                                                                    .handCursor()
                                                                    .clickable(
                                                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                                        indication = null,
                                                                    ) { vm.locateModifierContent(target.kind, target.id) }
                                                                    .padding(horizontal = 6.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                            ) {
                                                                Text(
                                                                    text = contentName(state, target.kind, target.id),
                                                                    style = LocalToolTypography.current.caption,
                                                                    color = colors.textPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                2 -> {
                                    val hasBehavior = remember(selected.type, selected.version) {
                                        val alg = (ModifierPluginCatalog.algorithm(selected.type, selected.version)
                                            ?: ModifierPluginCatalog.algorithm(selected.type))?.second
                                        alg != null && (alg.creates.isNotEmpty() || alg.modifies.isNotEmpty())
                                    }
                                    val hasDeps = RigLegacyBindings.find(selected.type)?.let { RigLegacyBindings.providers(selected).isNotEmpty() } == true
                                    if (hasBehavior || hasDeps) {
                                        RememberedModifierSection(
                                            stateKey = "${selected.id}:behavior",
                                            title = tr("modifier.section.behavior"),
                                            defaultExpanded = true,
                                        ) {
                                            ModifierBehaviorPreview(selected.type, selected.version)
                                            ModifierDependencies(selected, stack, enabled, vm)
                                        }
                                    }
                                    report?.let { ModifierContributions(it, state, vm) }
                                    CompactButton(
                                        tr("modifier.apply"),
                                        { vm.editModifier("modifier_apply", buildJsonObject { put("id", selected.id) }) },
                                        enabled = enabled,
                                        height = 24.dp,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
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
        InspectorFormRow(modifierName(type)) {
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
            }, itemEnabled = { id -> candidates.any { it.id == id } }, enabled = enabled, height = 20.dp, modifier = Modifier.fillMaxWidth())
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
    RememberedModifierSection(
        stateKey = "${instance.id}:modulePhysics",
        title = tr("swing.physics"),
        defaultExpanded = false,
        summary = tr("modifier.count", candidates.size),
    ) {
        candidates.forEach { (slot, actual) ->
            RememberedModifierSection(
                stateKey = "${instance.id}:modulePhysics:$slot",
                title = actual.name,
                defaultExpanded = true,
                level = 1,
            ) {
                ModifierNativeValues("physics", io.github.psd2live.core.Physics3Json.setting(actual).toJson(), state, enabled, vm,
                    editName = actual.id == instance.outputId("physics:$slot"), instanceId = "${instance.id}:physics:$slot") { next, expected, done ->
                    val overrides = JsonObject(instance.config["physics"]?.jsonObject.orEmpty() + (slot to next))
                    vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type)
                        put("config", JsonObject(instance.config + ("physics" to overrides))) }, expected, done)
                }
            }
        }
    }
}

@Composable
private fun ModifierSourceValues(instance: RigModifierInstance, state: PSD2LiveState, enabled: Boolean, vm: PSD2LiveViewModel) {
    if (instance.plugin?.let { !it.id.startsWith("builtin.") } == true) {
        RememberedModifierSection(
            stateKey = "${instance.id}:plugin",
            title = modifierName(instance.type),
            defaultExpanded = true,
        ) {
            ModifierPluginValues(instance, enabled, vm)
        }
        return
    }
    if (instance.type == RigSourceModifiers.SCAFFOLD) {
        RememberedModifierSection(
            stateKey = "${instance.id}:scaffold",
            title = tr("modifier.scaffold"),
            defaultExpanded = true,
        ) {
            InspectorFormRow(tr("model.deformer.featureDisplacement"), labelWidth = 110.dp) {
                CompactCheckbox(instance.config["featureDisplacement"]?.jsonPrimitive?.booleanOrNull == true, { value ->
                    vm.editModifier("modifier_update", buildJsonObject {
                        put("id", instance.id); put("type", instance.type)
                        put("config", JsonObject(instance.config + ("featureDisplacement" to JsonPrimitive(value))))
                    })
                }, enabled = enabled)
            }
            for ((key, label) in listOf("frontHairSway" to "modifier.frontHairSway", "backHairSway" to "modifier.backHairSway")) {
                InspectorFormRow(tr(label), labelWidth = 110.dp) {
                    CompactCheckbox(instance.config[key]?.jsonPrimitive?.booleanOrNull != false, { value ->
                        vm.editModifier("modifier_update", buildJsonObject {
                            put("id", instance.id); put("type", instance.type)
                            put("config", JsonObject(instance.config + (key to JsonPrimitive(value))))
                        })
                    }, enabled = enabled)
                }
            }
        }
        return
    }
    if (instance.type in setOf(RigSourceModifiers.EYE, RigSourceModifiers.BROW, RigSourceModifiers.MOUTH, RigSourceModifiers.FACE, RigSourceModifiers.BODY, RigSourceModifiers.HAIR)) {
        ModifierPartValues(instance, state, enabled, vm)
        return
    }
    if (RigLegacyBindings.find(instance.type) != null) {
        RememberedModifierSection(
            stateKey = "${instance.id}:binding",
            title = modifierName(instance.type),
            defaultExpanded = true,
        ) {
            ModifierBindingValues(instance, enabled, vm) { config, expected, done ->
                vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", config) }, expected, done)
            }
        }
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
    val reportId = report.getValue("id").jsonPrimitive.content
    val contributions = report.getValue("contributions").jsonArray.map { it.jsonObject }
    for ((kind, items) in contributions.groupBy { it.getValue("kind").jsonPrimitive.content }) {
        RememberedModifierSection(
            stateKey = "$reportId:contrib:$kind",
            title = tr("modifier.kind.$kind"),
            defaultExpanded = false,
            summary = tr("modifier.count", items.size),
        ) {
            items.forEach { item ->
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
    }
    val forms = report.getValue("owned_keyforms").jsonArray
    if (forms.isNotEmpty()) {
        RememberedModifierSection(
            stateKey = "$reportId:keyforms",
            title = tr("modifier.keyforms"),
            defaultExpanded = false,
            summary = tr("modifier.count", forms.size),
        ) {
            forms.forEach { element -> val form = element.jsonObject
                val kind = form.getValue("kind").jsonPrimitive.content; val id = form.getValue("id").jsonPrimitive.content
                CompactButton("${form["object_name"]?.jsonPrimitive?.content ?: contentName(state, kind, id)} · ${form["parameter_name"]?.jsonPrimitive?.content ?: contentName(state, "parameter", form.getValue("parameter").jsonPrimitive.content)}" +
                    (form["key"]?.jsonObject?.let { key -> " · " + key.entries.joinToString { (parameter, value) -> contentName(state, "parameter", parameter) + ": " + value.jsonPrimitive.content } } ?: "") +
                    (form["channel"]?.let { channel ->
                        val name = when (val raw = channel.jsonPrimitive.content) { "draw_order" -> "drawOrder"; "multiply_color" -> "multiplyColor"; "screen_color" -> "screenColor"; else -> raw }
                        " · ${tr("keyform.channel.$name")}"
                    } ?: ""), { vm.locateModifierContent(kind, id) }, height = 20.dp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    val overrideIssues = report.getValue("override_issues").jsonArray
    if (overrideIssues.isNotEmpty()) {
        ModifierCard {
            overrideIssues.forEach { raw -> val issue = raw.jsonObject
                Text(tr("modifier.override.${issue.getValue("kind").jsonPrimitive.content}", issue.getValue("target").jsonPrimitive.content), color = colors.warning, style = LocalToolTypography.current.caption)
            }
        }
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
    val pluginRevision by ModifierPluginStore.shared.revision.collectAsState()
    val externalPlugins = remember(pluginRevision, dialog.targets) {
        ModifierPluginStore.shared.runnablePackages()
            .filter { !it.reference.id.startsWith("builtin.") && ModifierPluginStore.shared.resolution(it.reference).available }
            .flatMap { it.manifest.algorithms }
            .filter { algorithm -> dialog.targets.all { it.kind in algorithm.targets } }
            .map { it.type }
            .distinct()
    }
    val isMeshTarget = remember(dialog.targets) { dialog.targets.all { it.kind == "mesh" } }
    val available = remember(isMeshTarget, externalPlugins) {
        val builtins = if (isMeshTarget) {
            (WorkspaceModifierPlan.sourceTypes + WorkspaceModifierPlan.nativeTypes)
                .filterNot { it == RigSourceModifiers.SCAFFOLD }
                .filter { ModifierPluginStore.shared.builtinsEnabled() }
        } else {
            WorkspaceModifierPlan.nativeTypes.filter { ModifierPluginStore.shared.builtinsEnabled() }
        }
        (builtins + externalPlugins).distinct()
    }
    val foldedLegacyUiTypes = remember {
        ModifierPartConfig.features.values.flatMapTo(HashSet()) { it.values } + setOf(
            "eye/fitted", "eye/legacy",
            "brow/fitted", "brow/legacy",
            "mouth/fitted", "mouth/legacy",
            "face/fitted", "face/legacy", "face.depth-turn",
            "body/legacy",
            "hair/chains", "hair/legacy",
        )
    }
    val hideFoldedLegacy = RigSourceModifiers.PART_TYPES.any { it in available }
    val visibleAvailable = available.filter { type ->
        (state.previewModel?.let { !dialog.availability(it, type).hidden } != false) &&
            (!hideFoldedLegacy || type !in foldedLegacyUiTypes)
    }
    fun category(type: String): String = when {
        type in WorkspaceModifierPlan.nativeTypes -> "advanced"
        type in RigSourceModifiers.PART_TYPES -> type
        else -> RigLegacyBindings.find(RigModifierAlgorithms.registry.storedType(type))?.area ?: "plugins"
    }
    fun categoryName(id: String): String = when (id) {
        "advanced" -> tr("modifier.advanced")
        "plugins" -> tr("plugins.title")
        else -> tr("settings.rig.area.$id")
    }
    data class AddRow(val type: String, val preset: String?, val label: String, val description: String?, val count: Int)
    fun rowsFor(cat: String): List<AddRow> {
        val rows = mutableListOf<AddRow>()
        fun add(type: String, preset: String?, label: String) {
            val availability = state.previewModel?.let { dialog.availability(it, type, preset) }
            if (availability?.hidden != true) {
                rows += AddRow(type, preset, label, modifierDescription(type, preset), availability?.existingCount ?: 0)
            }
        }
        if (isMeshTarget && cat == "outfit") {
            add("simulation", "clothing", tr("modifier.clothing.simulation"))
        }
        if (isMeshTarget && cat == "hair") {
            for (preset in listOf("front_hair", "back_hair")) {
                add("simulation", preset, tr("modifier.simulation.$preset"))
            }
        }
        if (isMeshTarget && cat in setOf("hair", "eye")) {
            WorkspaceModifierPlan.physicsPresets().filter { preset ->
                (preset.id == io.github.psd2live.core.PhysicsGenerator.EYE_JELLY_ID) == (cat == "eye")
            }.forEach { preset ->
                add("physics", preset.id, preset.name)
            }
        }
        if (isMeshTarget && cat == "motion") {
            state.previewModel?.let(WorkspaceModifierPlan::selectableMotionPresets).orEmpty().forEach { preset ->
                val name = preset.removePrefix(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX)
                val label = if (name == preset) builtinMotionTitle(name) else "${tr("settings.rig.base.motion.natural")} · ${builtinMotionTitle(name)}"
                add("motion", preset, label)
            }
        }
        visibleAvailable.filter { category(it) == cat }.forEach { type ->
            add(type, null, modifierName(type))
        }
        return rows
    }
    val categoryOrder = listOf("face", "eye", "brow", "mouth", "body", "hair", "outfit", "expression", "motion", "advanced", "plugins")
    val rowsByCategory = categoryOrder.associateWith(::rowsFor).filterValues { it.isNotEmpty() }
    val categories = rowsByCategory.keys.toList()
    var selectedCategory by remember(categories) {
        mutableStateOf(dialog.type?.let(::category)?.takeIf { it in rowsByCategory })
    }
    var selectedRow by remember { mutableStateOf<AddRow?>(null) }
    val activeRows = selectedCategory?.let { rowsByCategory[it] }.orEmpty()
    val activeType = dialog.type ?: selectedRow?.type
    val activePreset = if (dialog.type != null) dialog.preset else selectedRow?.preset
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val paneHeight = 320.dp
    PressMenu(expanded = true, press = Offset.Zero,
        onDismissRequest = { if (!dialog.committing) vm.dismissModifierAddition() },
        preferredMaxWidth = 540.dp) {
        val opensLeft = LocalMenuOpensLeft.current
        val categoryColumn: @Composable () -> Unit = {
            GlidingColumn(Modifier.width(108.dp).heightIn(max = paneHeight).verticalScroll(rememberScrollState()), categories.size,
                highlights = { !dialog.committing },
                onHover = { if (!dialog.committing) selectedCategory = categories[it] }) { index ->
                val id = categories[index]
                FloatingMenuRow(categoryName(id), { selectedCategory = id }, selected = selectedCategory == id, bar = false,
                    enabled = !dialog.committing)
            }
        }
        val itemColumn: @Composable () -> Unit = {
            val cat = selectedCategory
            if (cat != null) {
                key(cat) {
                    GlidingColumn(Modifier.width(188.dp).heightIn(max = paneHeight).verticalScroll(rememberScrollState()), activeRows.size,
                        highlights = { !dialog.committing },
                        onHover = { index ->
                            val row = activeRows[index]
                            if (!dialog.committing && (selectedRow?.type != row.type || selectedRow?.preset != row.preset)) {
                                selectedRow = row
                                vm.previewModifierAddition(row.type, null, preset = row.preset)
                            }
                        }) { index ->
                        val row = activeRows[index]
                        val isSelected = dialog.type == row.type && dialog.preset == row.preset
                        FloatingMenuRow(
                            label = row.label,
                            onClick = {
                                selectedRow = row
                                vm.previewModifierAddition(row.type, null, preset = row.preset)
                            },
                            selected = isSelected,
                            bar = false,
                            enabled = !dialog.committing,
                            trailing = if (row.count > 0) tr("modifier.add.existing", row.count) else null,
                        )
                    }
                }
            }
        }
        val detailColumn: @Composable () -> Unit = {
            if (activeType != null) {
                val activeTitle = selectedRow?.takeIf { it.type == activeType && it.preset == activePreset }?.label
                    ?: modifierName(activeType)
                val activeDesc = selectedRow?.takeIf { it.type == activeType && it.preset == activePreset }?.description
                    ?: modifierDescription(activeType, activePreset)
                Column(Modifier.width(228.dp).height(paneHeight)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = activeTitle,
                            style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!activeDesc.isNullOrBlank()) {
                            Text(
                                text = activeDesc,
                                style = typography.caption.copy(fontSize = 10.sp, lineHeight = 13.5.sp),
                                color = colors.textMuted,
                            )
                        }
                    }
                    FloatingMenuDivider()
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        if (dialog.loading) {
                            FloatingMenuSection(tr(if (dialog.committing) "modifier.adding" else "modifier.previewing"))
                        }
                        dialog.error?.let {
                            Text(
                                text = it,
                                style = typography.caption.copy(fontSize = 10.sp),
                                color = colors.warning,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        val planned = dialog.plan?.get("plan")?.jsonArray.orEmpty()
                        if (planned.isNotEmpty()) {
                            FloatingMenuSection(tr("modifier.details"))
                            planned.forEach { raw ->
                                val item = raw.jsonObject
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = modifierName(item.getValue("type").jsonPrimitive.content),
                                        style = typography.caption.copy(fontSize = 10.5.sp),
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = tr("modifier.plan.${item.getValue("disposition").jsonPrimitive.content}"),
                                        style = typography.caption.copy(fontSize = 9.5.sp),
                                        color = colors.textMuted,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            (dialog.type ?: planned.lastOrNull()?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)?.let { previewType ->
                                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    ModifierBehaviorPreview(previewType)
                                }
                            }
                        }
                    }
                    FloatingMenuDivider()
                    Box(Modifier.fillMaxWidth().padding(6.dp)) {
                        CompactButton(
                            text = tr("modifier.add"),
                            onClick = vm::confirmModifierAddition,
                            enabled = !dialog.loading && dialog.error == null && dialog.plan?.get("entries")?.jsonArray?.isNotEmpty() == true,
                            isPrimary = true,
                            height = 24.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (opensLeft) {
                if (activeType != null) {
                    detailColumn()
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
                }
                if (selectedCategory != null) {
                    itemColumn()
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
                }
                categoryColumn()
            } else {
                categoryColumn()
                if (selectedCategory != null) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
                    itemColumn()
                }
                if (activeType != null) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
                    detailColumn()
                }
            }
        }
    }
}
