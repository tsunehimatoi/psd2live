package io.github.psd2live.ui.views.shadow

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.algorithms.shadow.*
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.modifier.ShadowAlgorithmPlugin
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.*
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.PhysicsRowDivider
import io.github.psd2live.ui.views.physics.PhysicsSection
import io.github.psd2live.ui.views.physics.RowBadge
import kotlinx.serialization.json.*

private val SHADOW_SECTIONS = setOf("world", "items", "editor")

@Composable
internal fun ShadowPanelView(
    vm: PSD2LiveViewModel,
    state: PSD2LiveState,
) {
    val colors = LocalToolColors.current
    val typo = LocalToolTypography.current
    val stack = remember(state.rigEdits.modifierStack) {
        state.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: RigModifierStack()
    }
    val worldInstance = remember(stack) {
        stack.instances.lastOrNull { it.type == ShadowAlgorithmPlugin.TYPE_WORLD }
    }
    val world = remember(worldInstance) {
        worldInstance?.let { ShadowWorldConfig.fromJson(it.config) } ?: ShadowWorldConfig.Default
    }
    val items = remember(stack) {
        stack.instances.filter { it.type == ShadowAlgorithmPlugin.TYPE_CAST || it.type == ShadowAlgorithmPlugin.TYPE_FORM }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
    LaunchedEffect(items) {
        if (selectedId == null || items.none { it.id == selectedId }) {
            selectedId = items.firstOrNull()?.id
        }
    }

    var openSections by remember { mutableStateOf(SHADOW_SECTIONS) }
    var showWorldAdvanced by remember { mutableStateOf(false) }
    var showItemAdvanced by remember { mutableStateOf(false) }

    val puppet = state.previewModel?.rig?.puppet
    val hasProject = puppet != null
    val selectedMeshes = remember(state.selectedLayerIds, state.selectedLayerId) {
        state.selectedLayerIds.toList().ifEmpty { listOfNotNull(state.selectedLayerId) }
    }
    val drawableNames = remember(puppet) {
        puppet?.drawables?.associate { it.id.raw to it.name }.orEmpty()
    }

    fun submit(summary: String, op: String, payload: JsonObject) {
        vm.submitShadowEdit(summary, WorkspaceDocumentOperation(op, payload))
    }

    fun commitWorld(next: ShadowWorldConfig) {
        submit(
            tr("shadow.world.title"),
            "shadow_world_set",
            buildJsonObject {
                put("azimuth", next.azimuth)
                put("elevation", next.elevation)
                put("style", next.style.name.lowercase())
                put("color_mode", next.colorMode.name.lowercase())
                put("custom_color", next.customColor)
                put("intensity", next.intensity)
                put("composite_mode", next.compositeMode.name.lowercase())
                put("preview_enabled", next.previewEnabled)
            },
        )
    }

    val labels = listOf(tr("shadow.auto"), tr("shadow.new.cast"), tr("shadow.new.form"), tr("shadow.preview"))

    Column(modifier = Modifier.fillMaxSize().background(colors.panelBackground)) {
        PanelToolbar(labels = labels, iconCount = 6) { labelsShown ->
            PanelToolButton(
                label = labels[0],
                showLabel = labelsShown > 0,
                onClick = { submit(tr("shadow.auto"), "shadow_auto_generate", buildJsonObject { }) },
                enabled = hasProject && !state.canvasEditBusy,
                tooltip = tr("shadow.auto"),
            ) {
                IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
            }
            PanelToolButton(
                label = labels[1],
                showLabel = labelsShown > 1,
                onClick = {
                    val allIds = puppet?.drawables?.map { it.id.raw }.orEmpty()
                    val casters = selectedMeshes.ifEmpty { allIds.take(1) }
                    val receivers = (allIds - casters.toSet()).take(1).ifEmpty { casters }
                    if (casters.isNotEmpty() && receivers.isNotEmpty()) {
                        submit(
                            tr("shadow.new.cast"),
                            "shadow_put",
                            buildJsonObject {
                                put("kind", "cast")
                                put("name", tr("shadow.kind.cast"))
                                put("casters", JsonArray(casters.map { JsonPrimitive(it) }))
                                put("receivers", JsonArray(receivers.map { JsonPrimitive(it) }))
                            },
                        )
                    }
                },
                enabled = hasProject && !state.canvasEditBusy,
                tooltip = tr("shadow.new.cast"),
            ) {
                IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
            }
            PanelToolButton(
                label = labels[2],
                showLabel = labelsShown > 2,
                onClick = {
                    val allIds = puppet?.drawables?.map { it.id.raw }.orEmpty()
                    val targets = selectedMeshes.ifEmpty { allIds.take(1) }
                    if (targets.isNotEmpty()) {
                        submit(
                            tr("shadow.new.form"),
                            "shadow_put",
                            buildJsonObject {
                                put("kind", "form")
                                put("name", tr("shadow.kind.form"))
                                put("targets", JsonArray(targets.map { JsonPrimitive(it) }))
                            },
                        )
                    }
                },
                enabled = hasProject && !state.canvasEditBusy,
                tooltip = tr("shadow.new.form"),
            ) {
                IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
            }
            PanelToolbarSeparator()
            PanelToolSwitch(
                label = labels[3],
                showLabel = labelsShown > 3,
                checked = world.previewEnabled,
                onCheckedChange = { enabled -> commitWorld(world.copy(previewEnabled = enabled)) },
                enabled = hasProject && !state.canvasEditBusy,
                tooltip = tr("shadow.preview"),
            )
            Spacer(Modifier.weight(1f))
            PanelExpandCollapseButtons(
                onExpandAll = { openSections = SHADOW_SECTIONS },
                onCollapseAll = { openSections = emptySet() },
            )
        }

        if (!hasProject) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(tr("shadow.empty.project"), style = typo.body, color = colors.textMuted)
            }
            return@Column
        }

        val scrollState = rememberScrollState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = 12.dp),
            ) {
                // 1. World Lighting
                PhysicsSection(
                    title = tr("shadow.world.title"),
                    open = "world" in openSections,
                    onToggle = {
                        openSections = if ("world" in openSections) openSections - "world" else openSections + "world"
                    },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LightSphereControl(
                            azimuth = world.azimuth,
                            elevation = world.elevation,
                            onPreview = { _, _ -> },
                            onCommit = { az, el -> commitWorld(world.copy(azimuth = az, elevation = el)) },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.world.style")) }
                            CompactDropdown(
                                items = ShadowStyle.entries,
                                selectedItem = world.style,
                                onItemSelected = { commitWorld(world.copy(style = it)) },
                                modifier = Modifier.weight(1f),
                                itemLabel = { tr("shadow.style.${it.name.lowercase()}") },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.world.colorMode")) }
                            CompactDropdown(
                                items = ShadowColorMode.entries,
                                selectedItem = world.colorMode,
                                onItemSelected = { commitWorld(world.copy(colorMode = it)) },
                                modifier = Modifier.weight(1f),
                                itemLabel = { tr("shadow.colorMode.${it.name.lowercase()}") },
                            )
                        }

                        if (world.colorMode == ShadowColorMode.CUSTOM) {
                            var colorHex by remember(world.customColor) { mutableStateOf(world.customColor) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.world.customColor")) }
                                CompactTextField(
                                    value = colorHex,
                                    onValueChange = { colorHex = it },
                                    onCommit = {
                                        if (colorHex != world.customColor) commitWorld(world.copy(customColor = colorHex))
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        var intensityDraft by remember(world.intensity) { mutableStateOf(world.intensity) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.world.intensity")) }
                            CompactSlider(
                                value = intensityDraft,
                                onValueChange = { intensityDraft = it },
                                valueRange = 0f..1.5f,
                                onValueChangeFinished = {
                                    if (intensityDraft != world.intensity) commitWorld(world.copy(intensity = intensityDraft))
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Text(
                            text = if (showWorldAdvanced) "▾ ${tr("shadow.advanced")}" else "▸ ${tr("shadow.advanced")}",
                            style = typo.caption,
                            color = colors.textMuted,
                            modifier = Modifier.clickable { showWorldAdvanced = !showWorldAdvanced }.padding(vertical = 2.dp),
                        )
                        if (showWorldAdvanced) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.world.composite")) }
                                CompactDropdown(
                                    items = ShadowCompositeMode.entries,
                                    selectedItem = world.compositeMode,
                                    onItemSelected = { commitWorld(world.copy(compositeMode = it)) },
                                    modifier = Modifier.weight(1f),
                                    itemLabel = { tr("shadow.composite.${it.name.lowercase()}") },
                                )
                            }
                        }
                    }
                }

                // 2. Shadow List
                PhysicsSection(
                    title = tr("shadow.items.title"),
                    open = "items" in openSections,
                    count = items.size,
                    onToggle = {
                        openSections = if ("items" in openSections) openSections - "items" else openSections + "items"
                    },
                ) {
                    if (items.isEmpty()) {
                        Text(
                            text = tr("shadow.items.empty"),
                            style = typo.caption,
                            color = colors.textMuted,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            items.forEachIndexed { index, item ->
                                if (index > 0) PhysicsRowDivider()
                                val itemName = if (item.type == ShadowAlgorithmPlugin.TYPE_CAST) {
                                    CastShadowConfig.fromJson(item.config).name
                                } else {
                                    FormShadowConfig.fromJson(item.config).name
                                }
                                val kindLabel = tr(
                                    if (item.type == ShadowAlgorithmPlugin.TYPE_CAST) "shadow.kind.cast" else "shadow.kind.form"
                                )
                                ShadowListRow(
                                    name = itemName,
                                    kindLabel = kindLabel,
                                    enabled = item.enabled,
                                    selected = selected?.id == item.id,
                                    onSelect = { selectedId = item.id },
                                    onToggleEnabled = { next ->
                                        submit(
                                            itemName,
                                            "shadow_put",
                                            buildJsonObject {
                                                put("id", item.id)
                                                put("enabled", next)
                                            },
                                        )
                                    },
                                    onDelete = {
                                        submit(
                                            tr("shadow.delete"),
                                            "shadow_delete",
                                            buildJsonObject { put("id", item.id) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                // 3. Selected Item Editor
                if (selected != null) {
                    PhysicsSection(
                        title = tr("shadow.editor.title"),
                        open = "editor" in openSections,
                        onToggle = {
                            openSections = if ("editor" in openSections) openSections - "editor" else openSections + "editor"
                        },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (selected.type == ShadowAlgorithmPlugin.TYPE_CAST) {
                                val cfg = CastShadowConfig.fromJson(selected.config)
                                CastShadowEditor(
                                    id = selected.id,
                                    cfg = cfg,
                                    selectedMeshes = selectedMeshes,
                                    drawableNames = drawableNames,
                                    showAdvanced = showItemAdvanced,
                                    onToggleAdvanced = { showItemAdvanced = !showItemAdvanced },
                                    onPatch = { patch ->
                                        submit(cfg.name, "shadow_put", JsonObject(patch + ("id" to JsonPrimitive(selected.id))))
                                    },
                                )
                            } else {
                                val cfg = FormShadowConfig.fromJson(selected.config)
                                FormShadowEditor(
                                    id = selected.id,
                                    cfg = cfg,
                                    selectedMeshes = selectedMeshes,
                                    drawableNames = drawableNames,
                                    showAdvanced = showItemAdvanced,
                                    onToggleAdvanced = { showItemAdvanced = !showItemAdvanced },
                                    onPatch = { patch ->
                                        submit(cfg.name, "shadow_put", JsonObject(patch + ("id" to JsonPrimitive(selected.id))))
                                    },
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                CompactButton(
                                    text = tr("shadow.apply"),
                                    onClick = {
                                        submit(
                                            tr("shadow.apply"),
                                            "shadow_apply",
                                            buildJsonObject { put("id", selected.id) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ShadowListRow(
    name: String,
    kindLabel: String,
    enabled: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typo = LocalToolTypography.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> colors.accent.copy(alpha = 0.22f)
        hovered -> colors.accent.copy(alpha = 0.12f)
        else -> colors.panelBackground
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompactCheckbox(checked = enabled, onCheckedChange = onToggleEnabled)
        Text(
            text = name,
            style = typo.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (enabled) colors.textPrimary else colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RowBadge(kindLabel)
        CompactIconButton(
            onClick = onDelete,
            tooltip = tr("shadow.delete"),
        ) {
            IconClose(tint = colors.textMuted)
        }
    }
}

@Composable
private fun CastShadowEditor(
    id: String,
    cfg: CastShadowConfig,
    selectedMeshes: List<String>,
    drawableNames: Map<String, String>,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onPatch: (Map<String, JsonElement>) -> Unit,
) {
    val colors = LocalToolColors.current
    val typo = LocalToolTypography.current

    TargetSummaryRow(
        label = tr("shadow.cast.casters"),
        ids = cfg.casters,
        drawableNames = drawableNames,
        canFillFromSelection = selectedMeshes.isNotEmpty(),
        onFillFromSelection = {
            onPatch(mapOf("casters" to JsonArray(selectedMeshes.map { JsonPrimitive(it) })))
        },
    )
    TargetSummaryRow(
        label = tr("shadow.cast.receivers"),
        ids = cfg.receivers,
        drawableNames = drawableNames,
        canFillFromSelection = selectedMeshes.isNotEmpty(),
        onFillFromSelection = {
            onPatch(mapOf("receivers" to JsonArray(selectedMeshes.map { JsonPrimitive(it) })))
        },
    )

    var distDraft by remember(id, cfg.distance) { mutableStateOf(cfg.distance) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.cast.distance")) }
        CompactSlider(
            value = distDraft,
            onValueChange = { distDraft = it },
            valueRange = 0f..80f,
            onValueChangeFinished = { if (distDraft != cfg.distance) onPatch(mapOf("distance" to JsonPrimitive(distDraft))) },
            modifier = Modifier.weight(1f),
        )
    }

    var opDraft by remember(id, cfg.opacityOverride) { mutableStateOf(cfg.opacityOverride ?: 0.32f) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.opacity")) }
        CompactSlider(
            value = opDraft,
            onValueChange = { opDraft = it },
            valueRange = 0f..1f,
            onValueChangeFinished = { onPatch(mapOf("opacity_override" to JsonPrimitive(opDraft))) },
            modifier = Modifier.weight(1f),
        )
    }

    var softDraft by remember(id, cfg.softnessOverride) { mutableStateOf(cfg.softnessOverride ?: 8f) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.softness")) }
        CompactSlider(
            value = softDraft,
            onValueChange = { softDraft = it },
            valueRange = 0f..32f,
            onValueChangeFinished = { onPatch(mapOf("softness_override" to JsonPrimitive(softDraft))) },
            modifier = Modifier.weight(1f),
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.cast.placement")) }
        CompactDropdown(
            items = ShadowPlacement.entries,
            selectedItem = cfg.placement,
            onItemSelected = { onPatch(mapOf("placement" to JsonPrimitive(it.name.lowercase()))) },
            modifier = Modifier.weight(1f),
            itemLabel = { tr("shadow.placement.${it.name.lowercase()}") },
        )
    }

    Text(
        text = if (showAdvanced) "▾ ${tr("shadow.advanced")}" else "▸ ${tr("shadow.advanced")}",
        style = typo.caption,
        color = colors.textMuted,
        modifier = Modifier.clickable(onClick = onToggleAdvanced).padding(vertical = 2.dp),
    )
    if (showAdvanced) {
        var pinDraft by remember(id, cfg.rootPinning) { mutableStateOf(cfg.rootPinning) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.cast.rootPinning")) }
            CompactSlider(
                value = pinDraft,
                onValueChange = { pinDraft = it },
                valueRange = 0f..1f,
                onValueChangeFinished = { onPatch(mapOf("root_pinning" to JsonPrimitive(pinDraft))) },
                modifier = Modifier.weight(1f),
            )
        }
        var parDraft by remember(id, cfg.parallax) { mutableStateOf(cfg.parallax) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.cast.parallax")) }
            CompactSlider(
                value = parDraft,
                onValueChange = { parDraft = it },
                valueRange = 0f..2.5f,
                onValueChangeFinished = { onPatch(mapOf("parallax" to JsonPrimitive(parDraft))) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FormShadowEditor(
    id: String,
    cfg: FormShadowConfig,
    selectedMeshes: List<String>,
    drawableNames: Map<String, String>,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onPatch: (Map<String, JsonElement>) -> Unit,
) {
    val colors = LocalToolColors.current
    val typo = LocalToolTypography.current

    TargetSummaryRow(
        label = tr("shadow.form.targets"),
        ids = cfg.targets,
        drawableNames = drawableNames,
        canFillFromSelection = selectedMeshes.isNotEmpty(),
        onFillFromSelection = {
            onPatch(mapOf("targets" to JsonArray(selectedMeshes.map { JsonPrimitive(it) })))
        },
    )

    var covDraft by remember(id, cfg.coverage) { mutableStateOf(cfg.coverage) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.form.coverage")) }
        CompactSlider(
            value = covDraft,
            onValueChange = { covDraft = it },
            valueRange = 0.05f..0.75f,
            onValueChangeFinished = { onPatch(mapOf("coverage" to JsonPrimitive(covDraft))) },
            modifier = Modifier.weight(1f),
        )
    }

    var opDraft by remember(id, cfg.opacityOverride) { mutableStateOf(cfg.opacityOverride ?: 0.32f) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.opacity")) }
        CompactSlider(
            value = opDraft,
            onValueChange = { opDraft = it },
            valueRange = 0f..1f,
            onValueChangeFinished = { onPatch(mapOf("opacity_override" to JsonPrimitive(opDraft))) },
            modifier = Modifier.weight(1f),
        )
    }

    Text(
        text = if (showAdvanced) "▾ ${tr("shadow.advanced")}" else "▸ ${tr("shadow.advanced")}",
        style = typo.caption,
        color = colors.textMuted,
        modifier = Modifier.clickable(onClick = onToggleAdvanced).padding(vertical = 2.dp),
    )
    if (showAdvanced) {
        var expDraft by remember(id, cfg.cutterExpandPx) { mutableStateOf(cfg.cutterExpandPx) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(68.dp)) { FieldLabel(tr("shadow.form.cutterExpand")) }
            CompactSlider(
                value = expDraft,
                onValueChange = { expDraft = it },
                valueRange = 0f..12f,
                onValueChangeFinished = { onPatch(mapOf("cutter_expand_px" to JsonPrimitive(expDraft))) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TargetSummaryRow(
    label: String,
    ids: List<String>,
    drawableNames: Map<String, String>,
    canFillFromSelection: Boolean,
    onFillFromSelection: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typo = LocalToolTypography.current
    val summary = if (ids.isEmpty()) tr("shadow.targets.none") else {
        ids.take(2).joinToString(", ") { drawableNames[it] ?: it } +
            if (ids.size > 2) " +${ids.size - 2}" else ""
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(68.dp)) { FieldLabel(label) }
        Text(
            text = summary,
            style = typo.caption,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CompactButton(
            text = tr("shadow.targets.useSelection"),
            enabled = canFillFromSelection,
            onClick = onFillFromSelection,
        )
    }
}
