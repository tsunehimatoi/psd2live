package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.modifier.ClosedMouthVisibility
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.ModifierEditOrigin
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** The modifier owns the opacity curve; the parameter rail only edits this draft's channel keys. */
@Composable
internal fun ClosedMouthVisibilitySection(instance: RigModifierInstance, state: PSD2LiveState, enabled: Boolean,
    vm: PSD2LiveViewModel, commit: (JsonObject, String?, (String?) -> Unit) -> Unit) {
    val model = state.previewModel ?: return
    val puppet = model.rig.puppet
    val layers = model.analysis.layers.associateBy { it.source.id.raw }
    val targetIds = instance.targets.mapTo(HashSet()) { it.id }
    val saved = ClosedMouthVisibility.fromConfig(instance.config)
    val closed = puppet.drawables.firstOrNull { drawable ->
        val layer = layers[model.rig.ownLayerIdByDrawableId[drawable.id.raw]]
        drawable.id.raw in targetIds && layer?.semantic?.tag == SemanticTag.MOUTH_CLOSE && layer.opaquePixels > 0 &&
            (saved != null || drawable.channelGrids[FormChannel.OPACITY]?.axes?.any { it.parameterId == ClosedMouthVisibility.PARAMETER } == true)
    } ?: return
    if (puppet.drawables.none { drawable ->
        val layer = layers[model.rig.ownLayerIdByDrawableId[drawable.id.raw]]
        drawable.id.raw in targetIds && layer != null && layer.opaquePixels > 0 &&
            layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
    }) return
    val defaults = puppet.parameters.associate { it.id to it.default }
    val initial = remember(saved, closed.channelGrids) {
        saved ?: run {
            val points = requireNotNull(closed.channelGrids[FormChannel.OPACITY]).axes
            .single { it.parameterId == ClosedMouthVisibility.PARAMETER }.keys.filter { it in 0f..1f }.map { open ->
                ClosedMouthVisibility.Point(open, closed.channelGrids.scalarAt(FormChannel.OPACITY, closed.opacity,
                    { if (it == ClosedMouthVisibility.PARAMETER) open else defaults[it] ?: 0f }).coerceIn(0f, 1f))
            }
            // 3.8.2 scales generated tracks by source alpha; older workers used absolute 0/1.
            // The editor expresses visibility relative to that artwork's fully visible value.
            val full = maxOf(closed.opacity, points.maxOf { it.opacity }).takeIf { it > 0f } ?: 1f
            ClosedMouthVisibility(points.map { it.copy(opacity = it.opacity / full) })
        }
    }
    var draft by remember(state.projectOpenGeneration, instance.id, initial) { mutableStateOf(initial) }
    var selected by remember(state.projectOpenGeneration, instance.id) { mutableFloatStateOf(0f) }
    var expected by remember(state.projectOpenGeneration, instance.id, initial) { mutableStateOf<ModifierEditOrigin?>(null) }
    var failure by remember(state.projectOpenGeneration, instance.id, initial) { mutableStateOf<String?>(null) }
    var pending by remember(state.projectOpenGeneration, instance.id) { mutableStateOf(false) }
    var undo by remember(state.projectOpenGeneration, instance.id, initial) { mutableStateOf<List<ClosedMouthVisibility>>(emptyList()) }
    var redo by remember(state.projectOpenGeneration, instance.id, initial) { mutableStateOf<List<ClosedMouthVisibility>>(emptyList()) }
    val writable = enabled && !pending
    val keys = draft.points.map { it.open }
    val active = selected.takeIf { it in keys } ?: keys.first()
    val marks = puppet.drawables.filter { it.id.raw in targetIds }.flatMap { drawable ->
        drawable.geometryGrid?.axes?.firstOrNull { it.parameterId == ClosedMouthVisibility.PARAMETER }?.keys?.toList().orEmpty()
    }.filter { it in 0f..1f }.distinct().sorted()
    fun change(update: () -> ClosedMouthVisibility) {
        runCatching(update).onSuccess { next ->
            if (next != draft) {
                if (expected == null) expected = vm.modifierEditOrigin()
                undo = undo + draft; redo = emptyList(); draft = next
            }
            failure = null
        }.onFailure { failure = it.message }
    }
    RememberedModifierSection("${instance.id}:closedVisibility", tr("mouth.visibility.title"), defaultExpanded = false, level = 1) {
        Text(tr("mouth.visibility.guides", marks.joinToString(", ", transform = ::formatAxisValue)),
            style = LocalToolTypography.current.caption, color = LocalToolColors.current.textMuted)
        Text(tr("mouth.visibility.open"), style = LocalToolTypography.current.caption, color = LocalToolColors.current.textMuted)
        ParameterKeyAxis(keys, 0f, 1f, 0f, active, writable, null,
            onSelect = { selected = it },
            onAdd = { value -> change { draft.insert(value) }; selected = value },
            onMove = { from, to -> change { draft.move(from, to) }; selected = to },
            onDelete = { value -> change { draft.remove(value) } },
            onRange = { _, _, _ -> }, rangeEditable = false)
        key(active) {
            ModifierNumber(tr("keyform.channel.opacity"), draft.sample(active) * 100f, 0f..100f, "%", writable, vm) { value, _, done ->
                change { draft.setOpacity(active, value / 100f) }; done(null)
            }
        }
        InspectorHint(tr("mouth.visibility.help"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactIconButton({
                redo = redo + draft; draft = undo.last(); undo = undo.dropLast(1)
            }, enabled = writable && undo.isNotEmpty(), tooltip = tr("parameters.undoPointEdit"), size = 20.dp) {
                IconUndo(Modifier.size(12.dp), LocalToolColors.current.textPrimary)
            }
            CompactIconButton({
                undo = undo + draft; draft = redo.last(); redo = redo.dropLast(1)
            }, enabled = writable && redo.isNotEmpty(), tooltip = tr("parameters.redoPointEdit"), size = 20.dp) {
                IconRedo(Modifier.size(12.dp), LocalToolColors.current.textPrimary)
            }
            Spacer(Modifier.weight(1f))
            CompactButton(tr("mouth.visibility.discard"), {
                draft = initial; undo = emptyList(); redo = emptyList(); expected = null; failure = null
            }, enabled = writable && (draft != initial || failure != null), height = 20.dp)
            CompactButton(tr("mouth.visibility.reset"), { change { ClosedMouthVisibility.Default } }, enabled = writable, height = 20.dp)
            CompactButton(tr("mouth.visibility.apply"), {
                pending = true
                commit(JsonObject(instance.config + (ClosedMouthVisibility.CONFIG to draft.toJson())), expected?.let(vm::modifierEditState)) { error ->
                    pending = false; failure = error
                    if (error == null) { undo = emptyList(); redo = emptyList(); expected = null }
                }
            }, enabled = writable && draft != initial, height = 20.dp, isPrimary = true)
        }
        failure?.let { Text(it, color = LocalToolColors.current.warning, style = LocalToolTypography.current.caption) }
    }
}
