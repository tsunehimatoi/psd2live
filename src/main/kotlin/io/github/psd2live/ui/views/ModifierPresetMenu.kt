package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.WorkspaceInitialHair
import io.github.psd2live.application.WorkspaceModifierPlan
import io.github.psd2live.application.WorkspaceModelPresetEntry
import io.github.psd2live.application.WorkspaceModelPresetLibrary
import io.github.psd2live.application.WorkspacePresetBaseline
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.recipe.ModuleTier
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.ModifierAddition
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

@kotlinx.serialization.Serializable
internal enum class ModifierPresetCategory {
    MODERN,
    LEGACY,
    CUSTOM,
}

@kotlinx.serialization.Serializable
internal enum class ModifierSimulationMode(val labelKey: String, val modernId: String, val legacyId: String) {
    NONE("modifier.preset.sim.none", "modern-light", "legacy-light"),
    CLASSIC_PHYSICS("modifier.preset.sim.classic", "modern-hair", "legacy-hair"),
    CLOTHING_SIM("modifier.preset.sim.clothing", "modern", "legacy"),
    FULL_SIM("modifier.preset.sim.full", "modern-dynamic", "legacy-dynamic");

    fun presetId(category: ModifierPresetCategory): String = when (category) {
        ModifierPresetCategory.LEGACY -> legacyId
        else -> modernId
    }
}

@kotlinx.serialization.Serializable
internal enum class ModifierCountTier(val id: String, val labelKey: String) {
    EMPTY("empty", "modifier.preset.count.empty"),
    BASE("base", "modifier.preset.count.base"),
    MINIMAL("minimal", "modifier.preset.count.minimal"),
    STANDARD("standard", "modifier.preset.count.standard"),
    FULL("full", "modifier.preset.count.full"),
}

internal data class ModifierPresetParameterEstimate(
    val ordinary: Int,
    val physics: Int,
)

@kotlinx.serialization.Serializable
internal data class ModifierPresetSelection(
    val category: ModifierPresetCategory,
    val simulation: ModifierSimulationMode,
    val countTier: ModifierCountTier,
    val customId: String?,
)

/** Kept outside the popup so dismissing it or restarting the app retains every control. */
internal class ModifierPresetSelectionStore(private val node: java.util.prefs.Preferences) {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    fun load(): ModifierPresetSelection? = node.get("selection", null)?.let {
        runCatching { json.decodeFromString(ModifierPresetSelection.serializer(), it) }.getOrNull()
    }
    fun save(selection: ModifierPresetSelection) {
        node.put("selection", json.encodeToString(ModifierPresetSelection.serializer(), selection))
        node.flush()
    }
    companion object {
        val shared by lazy { ModifierPresetSelectionStore(java.util.prefs.Preferences.userRoot()
            .node("io.github.psd2live.settings/modifier-preset-menu")) }
    }
}

internal object ModifierPresetMenuModel {
    private const val BASE_ORDINARY_PARAMETER_COUNT = 15
    fun isCustom(entry: WorkspaceModelPresetEntry): Boolean = entry.stack != null || (entry.nameKey == null && entry.id !in setOf(
        "empty", "modern", "modern-light", "modern-hair", "modern-dynamic", "modern-motion",
        "legacy", "legacy-light", "legacy-hair", "legacy-dynamic", "legacy-motion"))

    fun availableCategories(entries: List<WorkspaceModelPresetEntry>): List<ModifierPresetCategory> {
        val categories = mutableListOf(ModifierPresetCategory.MODERN, ModifierPresetCategory.LEGACY)
        if (entries.any { ModifierPresetMenuModel.isCustom(it) }) categories += ModifierPresetCategory.CUSTOM
        return categories
    }

    fun initialSelection(stack: RigModifierStack?, entries: List<WorkspaceModelPresetEntry>): ModifierPresetSelection {
        val customDefault = entries.firstOrNull { ModifierPresetMenuModel.isCustom(it) }?.id
        if (stack != null) {
            if (stack.instances.isEmpty() && entries.any { it.id == "empty" }) {
                return ModifierPresetSelection(ModifierPresetCategory.MODERN, ModifierSimulationMode.CLOTHING_SIM, ModifierCountTier.EMPTY, customDefault)
            }
            val activeGroup = stack.instances.mapNotNull { it.presetGroup }.distinct().singleOrNull()
            val matched = activeGroup?.let { id -> entries.firstOrNull { it.id == id } }
            if (matched != null) {
                if (isCustom(matched)) {
                    return ModifierPresetSelection(ModifierPresetCategory.CUSTOM, ModifierSimulationMode.CLOTHING_SIM, ModifierCountTier.STANDARD, matched.id)
                }
                val category = if (matched.baseline == "legacy") ModifierPresetCategory.LEGACY else ModifierPresetCategory.MODERN
                val simMode = simulationModeForPresetId(matched.id)
                return ModifierPresetSelection(category, simMode, ModifierCountTier.STANDARD, customDefault)
            }
        }
        return ModifierPresetSelection(ModifierPresetCategory.MODERN, ModifierSimulationMode.CLOTHING_SIM, ModifierCountTier.STANDARD, customDefault)
    }

    fun simulationModeForPresetId(id: String): ModifierSimulationMode = when (id) {
        "modern-light", "legacy-light" -> ModifierSimulationMode.NONE
        "modern-hair", "legacy-hair" -> ModifierSimulationMode.CLASSIC_PHYSICS
        "modern-dynamic", "legacy-dynamic" -> ModifierSimulationMode.FULL_SIM
        else -> ModifierSimulationMode.CLOTHING_SIM
    }

    fun resolvePresetEntry(
        category: ModifierPresetCategory,
        simulation: ModifierSimulationMode,
        countTier: ModifierCountTier,
        customId: String?,
        entries: List<WorkspaceModelPresetEntry>,
    ): WorkspaceModelPresetEntry? {
        if (category == ModifierPresetCategory.CUSTOM) {
            return entries.firstOrNull { ModifierPresetMenuModel.isCustom(it) && it.id == customId }
                ?: entries.firstOrNull { ModifierPresetMenuModel.isCustom(it) }
        }
        if (countTier == ModifierCountTier.EMPTY) {
            return entries.firstOrNull { it.id == "empty" }
        }
        val targetId = simulation.presetId(category)
        return entries.firstOrNull { it.id == targetId }
            ?: entries.firstOrNull { it.baseline == if (category == ModifierPresetCategory.LEGACY) "legacy" else "modern" }
    }

    fun estimateParameters(
        entry: WorkspaceModelPresetEntry?,
        countTier: ModifierCountTier,
        model: RigPreviewModel?,
        baseConfig: PipelineConfig = PipelineConfig(),
    ): ModifierPresetParameterEstimate {
        if (entry == null || entry.baseline == "empty" || (countTier == ModifierCountTier.EMPTY && !isCustom(entry))) {
            return ModifierPresetParameterEstimate(ordinary = 0, physics = 0)
        }
        if (entry.stack != null) {
            val stack = runCatching { RigModifierStack.fromJson(entry.stack) }.getOrNull()
                ?: return ModifierPresetParameterEstimate(0, 0)
            val ordinaryIds = LinkedHashSet<String>()
            val physicsIds = LinkedHashSet<String>()
            var extraPhysics = 0
            for (instance in stack.instances) {
                when (instance.type) {
                    "physics" -> {
                        val outputs = instance.config["outputs"]?.jsonArray.orEmpty()
                        if (outputs.isEmpty()) extraPhysics++ else extraPhysics += outputs.size
                    }
                    "simulation" -> extraPhysics++
                    else -> {
                        RigModules.module(instance.type)?.defines?.forEach { param ->
                            if (param.group == "ParamGroupPhysics") physicsIds += param.id else ordinaryIds += param.id
                        }
                    }
                }
            }
            val baseOrdinary = if (stack.instances.any { it.type.startsWith("rig.") || it.type.contains('/') }) BASE_ORDINARY_PARAMETER_COUNT else 0
            return ModifierPresetParameterEstimate(
                ordinary = baseOrdinary + ordinaryIds.size,
                physics = physicsIds.size + extraPhysics,
            )
        }

        val tiers = when (if (isCustom(entry)) ModifierCountTier.entries.first { it.id == entry.richness } else countTier) {
            ModifierCountTier.EMPTY, ModifierCountTier.BASE -> emptySet()
            ModifierCountTier.MINIMAL -> setOf(ModuleTier.CORE)
            ModifierCountTier.STANDARD -> setOf(ModuleTier.CORE, ModuleTier.MAJOR)
            ModifierCountTier.FULL -> ModuleTier.entries.toSet()
        }
        val choices = entry.choices
        val config = WorkspacePresetBaseline.configure(baseConfig, entry)
        val subject = RecipeSubject.of(model?.analysis)
        val excludedModules = buildSet {
            addAll(WorkspaceModifierPlan.PRESET_EXCLUDED_MODULES)
            if (choices.clothing) add("outfit.ribbons")
        }
        val ordinaryIds = LinkedHashSet<String>()
        val physicsIds = LinkedHashSet<String>()
        for (module in RigModules.modules) {
            if (module.id in excludedModules || module.tier !in tiers) continue
            val applies = if (model != null) RigModules.builds(module, config, subject) else true
            if (!applies) continue
            for (param in module.defines) {
                if (param.group == "ParamGroupPhysics") physicsIds += param.id else ordinaryIds += param.id
            }
        }

        val present = model?.let { PhysicsGenerator.Presets.present(it.analysis) }
            ?: PhysicsGenerator.Presets(frontHair = true, backHair = true, eyeJelly = true)
        var physicsCount = physicsIds.size
        if (config.generatePhysics) {
            if (config.physicsFrontHair && present.frontHair) physicsCount++
            if (config.physicsBackHair && present.backHair) physicsCount++
            if (config.physicsEyeJelly && present.eyeJelly) physicsCount++
        }
        if (present.frontHair && choices.frontHair == WorkspaceInitialHair.SIMULATION) physicsCount++
        if (present.backHair && choices.backHair == WorkspaceInitialHair.SIMULATION) physicsCount++
        val hasClothing = model == null ||
            model.analysis.layers.any { it.semantic.tag in ModelPresets.CLOTHING_TAGS && it.opaquePixels > 0 }
        if (choices.clothing && hasClothing) physicsCount++

        return ModifierPresetParameterEstimate(
            ordinary = BASE_ORDINARY_PARAMETER_COUNT + ordinaryIds.size,
            physics = physicsCount,
        )
    }
}

@Composable
internal fun ModifierPresetMenu(
    state: PSD2LiveState,
    stack: RigModifierStack?,
    dialog: ModifierAddition,
    vm: PSD2LiveViewModel,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val library = WorkspaceModelPresetLibrary.shared
    val entries by library.state.collectAsState()
    val initial = remember { ModifierPresetSelectionStore.shared.load() ?: ModifierPresetMenuModel.initialSelection(stack, entries) }
    var category by remember { mutableStateOf(initial.category) }
    var simulation by remember { mutableStateOf(initial.simulation) }
    var countTier by remember { mutableStateOf(initial.countTier) }
    var customId by remember { mutableStateOf(initial.customId) }

    LaunchedEffect(category, simulation, countTier, customId) {
        ModifierPresetSelectionStore.shared.save(ModifierPresetSelection(category, simulation, countTier, customId))
    }
    LaunchedEffect(entries) {
        if (category == ModifierPresetCategory.CUSTOM && entries.none { ModifierPresetMenuModel.isCustom(it) }) {
            category = ModifierPresetCategory.MODERN
        }
        if (entries.none { ModifierPresetMenuModel.isCustom(it) && it.id == customId }) {
            customId = entries.firstOrNull { ModifierPresetMenuModel.isCustom(it) }?.id
        }
    }

    val model = state.previewModel
    val choice = presetChoice(ModifierPresetSelection(category, simulation, countTier, customId), entries)

    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var libraryBusy by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    val controlsEnabled = !dialog.committing && !libraryBusy

    fun runLibraryTask(action: suspend () -> Unit) {
        if (libraryBusy) return
        libraryBusy = true
        libraryError = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                libraryError = failure.message ?: failure.javaClass.simpleName
            } finally {
                libraryBusy = false
            }
        }
    }

    fun saveCurrentPreset() {
        val raw = state.rigEdits.modifierStack ?: return
        val capturedName = presetName.trim()
        val preview = model ?: return
        if (capturedName.isEmpty()) return
        runLibraryTask {
            val saved = withContext(Dispatchers.IO) { library.save(capturedName, raw, preview) }
            naming = false
            category = ModifierPresetCategory.CUSTOM
            customId = saved.id
        }
    }

    PressMenu(
        expanded = true,
        press = Offset.Zero,
        onDismissRequest = { if (controlsEnabled) vm.dismissModifierAddition() },
        width = 284.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModifierPresetControls(ModifierPresetSelection(category, simulation, countTier, customId), entries,
                model, controlsEnabled) { next ->
                category = next.category
                simulation = next.simulation
                countTier = next.countTier
                customId = next.customId
            }

            dialog.error?.let { err ->
                Text(text = err, style = typography.caption, color = colors.error)
            }
            libraryError?.let { err ->
                Text(text = tr("modifier.library.failed", err), style = typography.caption, color = colors.error)
            }

            CompactButton(
                text = if (dialog.committing) tr("modifier.adding") else tr("modifier.preset.apply"),
                onClick = { choice?.let { (entry, richness) -> vm.switchModifierPreset(entry.id, richness) } },
                enabled = controlsEnabled && choice != null,
                isPrimary = true,
                height = 22.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FloatingMenuDivider()

        if (naming) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    modifier = Modifier.fillMaxWidth(),
                    selectAllOnFocus = true,
                    placeholder = tr("modifier.library.name"),
                )
                Text(
                    text = tr("modifier.library.configOnly"),
                    style = typography.caption.copy(fontSize = 10.sp),
                    color = colors.textMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    CompactButton(tr("action.cancel"), { naming = false }, height = 22.dp)
                    CompactButton(
                        tr("modifier.library.save"),
                        ::saveCurrentPreset,
                        enabled = controlsEnabled && presetName.isNotBlank(),
                        isPrimary = true,
                        height = 22.dp,
                    )
                }
            }
        } else {
            FloatingMenuRow(
                label = tr("modifier.library.save"),
                onClick = { naming = true; presetName = "" },
                enabled = controlsEnabled && state.rigEdits.modifierStack != null && model != null,
                bar = false,
            )
            FloatingMenuRow(
                label = tr("modifier.library.import"),
                onClick = {
                    runLibraryTask {
                        val path = withContext(Dispatchers.IO) {
                            NativeFilePicker.chooseJsonFile(title = tr("modifier.library.import"))
                        } ?: return@runLibraryTask
                        val saved = withContext(Dispatchers.IO) {
                            library.importJson(Files.readString(Path.of(path)))
                        }
                        category = ModifierPresetCategory.CUSTOM
                        customId = saved.id
                    }
                },
                enabled = controlsEnabled,
                bar = false,
            )
            FloatingMenuRow(
                label = tr("modifier.library.export"),
                onClick = {
                    val id = choice?.first?.id ?: return@FloatingMenuRow
                    runLibraryTask {
                        val raw = library.exportJson(id, if (category == ModifierPresetCategory.CUSTOM) null else if (countTier == ModifierCountTier.EMPTY) "standard" else countTier.id)
                        val path = withContext(Dispatchers.IO) {
                            NativeFilePicker.chooseSaveJsonFile(
                                title = tr("modifier.library.export"),
                                defaultName = "preset.json",
                            )
                        } ?: return@runLibraryTask
                        withContext(Dispatchers.IO) { Files.writeString(Path.of(path), raw) }
                    }
                },
                enabled = controlsEnabled && choice != null,
                bar = false,
            )
            FloatingMenuRow(
                label = tr("modifier.library.delete"),
                onClick = {
                    val id = choice?.first?.id ?: return@FloatingMenuRow
                    runLibraryTask {
                        withContext(Dispatchers.IO) { library.delete(id) }
                    }
                },
                enabled = controlsEnabled && choice != null,
                danger = true,
                bar = false,
            )
        }
    }
}


internal fun presetChoice(selection: ModifierPresetSelection, entries: List<WorkspaceModelPresetEntry>): Pair<WorkspaceModelPresetEntry, String>? {
    val (category, simulation, tier, customId) = selection
    val entry = ModifierPresetMenuModel.resolvePresetEntry(category, simulation, tier, customId, entries) ?: return null
    val richness = when {
        category == ModifierPresetCategory.CUSTOM -> entry.richness
        tier == ModifierCountTier.EMPTY -> ModifierCountTier.STANDARD.id
        else -> tier.id
    }
    return entry to richness
}

@Composable
internal fun ModifierPresetControls(
    selection: ModifierPresetSelection,
    entries: List<WorkspaceModelPresetEntry>,
    model: RigPreviewModel?,
    controlsEnabled: Boolean,
    onSelect: (ModifierPresetSelection) -> Unit,
) {
    val (category, simulation, countTier, customId) = selection
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val categories = remember(entries) { ModifierPresetMenuModel.availableCategories(entries) }
    val customEntries = remember(entries) { entries.filter { ModifierPresetMenuModel.isCustom(it) } }
    val activeEntry = remember(category, simulation, countTier, customId, entries) {
        ModifierPresetMenuModel.resolvePresetEntry(category, simulation, countTier, customId, entries)
    }
    val paramEstimate = remember(activeEntry, countTier, model) {
        ModifierPresetMenuModel.estimateParameters(activeEntry, countTier, model)
    }
    val hint = activeEntry?.nameKey?.let { key ->
        val hintKey = "$key.hint"
        tr(hintKey).takeUnless { it == hintKey }
    }

    fun categoryLabel(cat: ModifierPresetCategory): String = when (cat) {
        ModifierPresetCategory.MODERN -> tr("modifier.preset.type.modern")
        ModifierPresetCategory.LEGACY -> tr("modifier.preset.type.legacy")
        ModifierPresetCategory.CUSTOM -> tr("modifier.preset.type.custom")
    }

    // Dropdown 1: Preset type + reset button
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompactSelect(
            options = categories,
            selected = category,
            label = ::categoryLabel,
            onSelect = { next ->
                onSelect(selection.copy(category = next, customId = customId ?: customEntries.firstOrNull()?.id))
            },
            enabled = controlsEnabled,
            height = 22.dp,
            modifier = Modifier.weight(1f),
        )
        val canReset = controlsEnabled && (
            category != ModifierPresetCategory.MODERN ||
                simulation != ModifierSimulationMode.CLOTHING_SIM ||
                countTier != ModifierCountTier.STANDARD
            )
        CompactIconButton(
            onClick = {
                onSelect(ModifierPresetMenuModel.initialSelection(null, entries))
            },
            enabled = canReset,
            tooltip = tr("modifier.preset.reset"),
            size = 22.dp,
        ) {
            IconReset(tint = if (canReset) colors.textMuted else colors.textDisabled)
        }
    }

    // Dropdown 2: Simulation mode (or custom preset selector)
    if (category == ModifierPresetCategory.CUSTOM) {
        val selectedCustom = customEntries.firstOrNull { it.id == customId } ?: customEntries.firstOrNull()
        if (selectedCustom != null) {
            CompactSelect(
                options = customEntries,
                selected = selectedCustom,
                label = { it.nameKey?.let { k -> tr(k) } ?: it.name },
                onSelect = { onSelect(selection.copy(customId = it.id)) },
                enabled = controlsEnabled,
                height = 22.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        val simEnabled = controlsEnabled && countTier != ModifierCountTier.EMPTY
        CompactSelect(
            options = ModifierSimulationMode.entries,
            selected = simulation,
            label = { tr(it.labelKey) },
            onSelect = { onSelect(selection.copy(simulation = it)) },
            enabled = simEnabled,
            height = 22.dp,
            modifier = Modifier.fillMaxWidth(),
        )

        val countTiers = ModifierCountTier.entries
        var previewTier by remember { mutableStateOf<ModifierCountTier?>(null) }
        val shownTier = previewTier ?: countTier
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("modifier.preset.richness.title"),
                    style = typography.caption.copy(fontSize = 10.5.sp),
                    color = colors.textMuted,
                )
                Text(
                    text = tr(shownTier.labelKey),
                    style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                )
            }

            PresetRichnessSlider(
                count = countTiers.size,
                selectedIndex = countTier.ordinal,
                enabled = controlsEnabled,
                onPreviewIndex = { index -> previewTier = index?.let(countTiers::get) },
                onSelectIndex = { index -> onSelect(selection.copy(countTier = countTiers[index])) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                countTiers.forEachIndexed { idx, tier ->
                    val isSelected = tier == shownTier
                    Text(
                        text = tr(tier.labelKey),
                        style = typography.caption.copy(
                            fontSize = 9.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (isSelected) colors.textPrimary else colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = when (idx) {
                            0 -> TextAlign.Start
                            countTiers.lastIndex -> TextAlign.End
                            else -> TextAlign.Center
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = controlsEnabled) { onSelect(selection.copy(countTier = tier)) },
                    )
                }
            }
        }
    }

    // Parameter counts (ordinary vs physics)
    Text(
        text = tr("modifier.preset.params", paramEstimate.ordinary, paramEstimate.physics),
        style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
        color = colors.textPrimary,
    )

    if (!hint.isNullOrBlank()) {
        Text(
            text = hint,
            style = typography.caption.copy(fontSize = 10.sp),
            color = colors.textMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }

}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PresetRichnessSlider(
    count: Int,
    selectedIndex: Int,
    enabled: Boolean,
    onPreviewIndex: (Int?) -> Unit,
    onSelectIndex: (Int) -> Unit,
) {
    val colors = LocalToolColors.current
    val selectIndex by rememberUpdatedState(onSelectIndex)
    val previewIndex by rememberUpdatedState(onPreviewIndex)
    val committedIndex by rememberUpdatedState(selectedIndex)
    val maxIdx = (count - 1).coerceAtLeast(1)
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .handCursor(enabled)
            .pointerInput(count, enabled) {
                if (!enabled || count <= 1) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (currentEvent.button != null && currentEvent.button != PointerButton.Primary) return@awaitEachGesture
                    val inset = 8.dp.toPx().coerceAtMost(size.height / 2f)
                    fun updatePreview(x: Float): Int {
                        val usable = (size.width - 2f * inset).coerceAtLeast(1f)
                        val fraction = ((x - inset) / usable).coerceIn(0f, 1f)
                        dragFraction = fraction
                        return (fraction * (count - 1)).roundToInt().also { previewIndex(it) }
                    }
                    try {
                        // Rebuilding the preview can disable the controls. Commit once the gesture has ended.
                        updatePreview(down.position.x)
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            val index = updatePreview(change.position.x)
                            change.consume()
                            if (!change.pressed) {
                                if (index != committedIndex) selectIndex(index)
                                break
                            }
                        }
                    } finally {
                        dragFraction = null
                        previewIndex(null)
                    }
                }
            },
    ) {
        val trackHeight = 16.dp.toPx().coerceAtMost(size.height)
        val top = (size.height - trackHeight) / 2f
        val radius = trackHeight / 2f
        val inset = radius
        val usableWidth = (size.width - 2f * inset).coerceAtLeast(1f)
        val thumbFraction = dragFraction ?: selectedIndex.toFloat() / maxIdx
        val thumbCenterX = inset + usableWidth * thumbFraction
        val thumbRadius = (trackHeight - 4.dp.toPx()) / 2f

        // Inactive pill track
        drawRoundRect(
            color = colors.controlBackground,
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )

        // Active accent fill
        val fillWidth = (thumbCenterX + radius * 0.2f).coerceIn(minOf(trackHeight, size.width), size.width)
        drawRoundRect(
            color = if (enabled) colors.accent else colors.accent.copy(alpha = 0.5f),
            topLeft = Offset(0f, top),
            size = Size(fillWidth, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )

        // Subtle border
        drawRoundRect(
            color = colors.border.copy(alpha = 0.6f),
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        )

        // Discrete stop dots
        val dotRadius = 1.8.dp.toPx()
        val cy = size.height / 2f
        for (i in 0 until count) {
            val f = if (count <= 1) 0.5f else i.toFloat() / maxIdx.toFloat()
            val cx = inset + usableWidth * f
            val onFilledSide = cx <= thumbCenterX
            drawCircle(
                color = if (onFilledSide) colors.accentText.copy(alpha = 0.6f) else colors.textMuted.copy(alpha = 0.55f),
                radius = dotRadius,
                center = Offset(cx, cy),
            )
        }

        // Thumb
        drawCircle(
            color = colors.border.copy(alpha = 0.45f),
            radius = thumbRadius + 0.8.dp.toPx(),
            center = Offset(thumbCenterX, cy),
        )
        drawCircle(
            color = if (enabled) colors.accentText else colors.textDisabled,
            radius = thumbRadius,
            center = Offset(thumbCenterX, cy),
        )
    }
}
