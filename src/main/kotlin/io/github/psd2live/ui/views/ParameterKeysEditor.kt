package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconRedo
import io.github.psd2live.ui.components.IconUndo
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.model.*
import kotlin.math.abs

private val PresetSteps = listOf(1f, 5f, 15f, 30f)

/** Parameter stops. Shapes are captured later, by deforming a component at one of these values. */
@Composable
internal fun ParameterKeysEditor(
    parameter: Parameter,
    model: PuppetModel,
    enabled: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRange: (Float, Float, Float) -> Unit,
    onEdit: (JsonObject) -> Boolean,
) {
    val keys = remember(model, parameter.id, parameter.keys, parameter.kind) {
        val marks = model.parameterKeyMarks()[parameter.id]
        if (parameter.kind == ParameterKind.BLEND_SHAPE) marks?.blendKeys.orEmpty() else marks?.gridKeys.orEmpty()
    }
    var selected by remember(parameter.id) { mutableStateOf<Float?>(null) }
    var snapStep by remember { mutableStateOf<Float?>(5f) }
    var stepDraft by remember { mutableStateOf(formatAxisValue(5f)) }
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val writable = enabled
    LaunchedEffect(keys) {
        if (selected !in keys) selected = keys.minByOrNull { abs(it - (selected ?: parameter.default)) }
    }
    fun edit(action: String, values: List<Float>, from: Float? = null): Boolean = onEdit(buildJsonObject {
        put("op", "parameter_keys")
        put("parameter", parameter.id.raw)
        put("action", action)
        put("values", JsonArray(values.map(::JsonPrimitive)))
        from?.let { put("from", it) }
    })
    fun place(value: Float): Float = snapParameterValue(value, parameter.min, parameter.max, snapStep)
    fun applyStep(value: Float?) {
        snapStep = value?.takeIf { it.isFinite() && it > 0f }
        stepDraft = snapStep?.let(::formatAxisValue).orEmpty()
    }
    fun commitStepDraft() {
        val parsed = stepDraft.trim().toFloatOrNull()
        applyStep(parsed)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("parameters.axisTitle"), style = typography.caption, color = colors.textMuted)
            Text(tr("parameters.keyCount", keys.size), style = typography.caption.copy(fontSize = 10.sp), color = colors.textDisabled)
            Spacer(Modifier.weight(1f))
            CompactIconButton(onUndo, enabled = writable && canUndo, tooltip = tr("parameters.undoPointEdit"), size = 18.dp) {
                IconUndo(modifier = Modifier.size(11.dp), tint = if (writable && canUndo) colors.textPrimary else colors.textDisabled)
            }
            CompactIconButton(onRedo, enabled = writable && canRedo, tooltip = tr("parameters.redoPointEdit"), size = 18.dp) {
                IconRedo(modifier = Modifier.size(11.dp), tint = if (writable && canRedo) colors.textPrimary else colors.textDisabled)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("parameters.step"), style = typography.caption, color = colors.textMuted)
            for (step in PresetSteps) {
                CompactButton(
                    formatAxisValue(step),
                    { applyStep(if (snapStep == step) null else step) },
                    enabled = writable,
                    isPrimary = snapStep == step,
                    height = 18.dp,
                )
            }
            CompactTextField(
                stepDraft,
                { stepDraft = it },
                modifier = Modifier.width(48.dp),
                enabled = writable,
                isMono = true,
                height = 18.dp,
                onCommit = { commitStepDraft() },
                onEditEnd = { commitStepDraft() },
                onFocusLost = { commitStepDraft() },
                selectAllOnFocus = true,
                endEditOnSettle = false,
            )
            Spacer(Modifier.weight(1f))
            CompactButton(
                tr("parameters.distribute"),
                {
                    val next = evenlyDistributedKeys(parameter.min, parameter.max, keys.size.coerceAtLeast(2))
                    if (next.isNotEmpty() && edit("set", next)) selected = selected?.let { place(it) }
                },
                enabled = writable && parameter.max > parameter.min && keys.size >= 2,
                height = 18.dp,
            )
        }
        ParameterKeyAxis(
            keys, parameter.min, parameter.max, parameter.default, selected, writable, snapStep,
            onSelect = { selected = it },
            onAdd = { value ->
                val snapped = place(value)
                if (snapped.isFinite() && snapped in parameter.min..parameter.max && keys.none { abs(it - snapped) < EPS_SPAN }) {
                    if (edit("add", listOf(snapped))) selected = snapped
                }
            },
            onMove = { from, to ->
                val snapped = place(to)
                if (edit("move", listOf(snapped), from)) selected = snapped
            },
            onDelete = { edit("delete", listOf(it)) },
            onRange = onRange,
        )
        Text(
            tr(if (parameter.kind == ParameterKind.BLEND_SHAPE) "parameters.blendAxisHelp" else "parameters.axisHelp"),
            style = typography.caption.copy(fontSize = 10.sp),
            color = colors.textDisabled,
        )
    }
}
