package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Parameter stops. Shapes are captured later, by deforming a component at one of these values. */
@Composable
internal fun ParameterKeysEditor(
    parameter: Parameter,
    model: PuppetModel,
    enabled: Boolean,
    onUndo: (() -> Unit)? = null,
    onEdit: (JsonObject) -> Boolean,
) {
    val keys = remember(model, parameter.id, parameter.keys) {
        model.parameterKeyMarks()[parameter.id]?.gridKeys.orEmpty()
    }
    var selected by remember(parameter.id) { mutableStateOf<Float?>(null) }
    var draft by remember(parameter.id) { mutableStateOf(parameter.default.toString()) }
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val writable = enabled && parameter.kind == ParameterKind.NORMAL
    LaunchedEffect(keys) {
        if (selected !in keys) selected = keys.minByOrNull { abs(it - (selected ?: parameter.default)) }
    }
    LaunchedEffect(selected) { selected?.let { draft = it.toString() } }
    fun edit(action: String, values: List<Float>, from: Float? = null): Boolean = onEdit(buildJsonObject {
        put("op", "parameter_keys")
        put("parameter", parameter.id.raw)
        put("action", action)
        put("values", JsonArray(values.map(::JsonPrimitive)))
        from?.let { put("from", it) }
    })
    fun add(value: Float) {
        if (!value.isFinite() || value !in parameter.min..parameter.max) return
        if (keys.any { abs(it - value) < EPS_SPAN }) return
        if (edit("add", listOf(value))) selected = value
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("parameters.axisTitle"), style = typography.body, color = colors.textPrimary)
            Text(tr("parameters.keyCount", keys.size), style = typography.caption, color = colors.textMuted)
            Spacer(Modifier.weight(1f))
            onUndo?.let { undo -> CompactButton(tr("parameters.undoPointEdit"), undo, enabled = enabled, height = 22.dp) }
        }
        ParameterKeyAxis(
            keys, parameter.min, parameter.max, parameter.default, selected, writable,
            onSelect = { selected = it },
            onAdd = ::add,
            onMove = { from, to -> if (edit("move", listOf(to), from)) selected = to },
            onDelete = { edit("delete", listOf(it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactTextField(
                draft, { draft = it }, modifier = Modifier.width(86.dp), enabled = writable, isMono = true,
                onCommit = {
                    val from = selected
                    val to = draft.toFloatOrNull()
                    if (from != null && to != null && to.isFinite() && to in parameter.min..parameter.max &&
                        keys.filter { it != from }.none { abs(it - to) < EPS_SPAN }
                    ) {
                        if (edit("move", listOf(to), from)) selected = to
                    }
                },
            )
            val value = draft.toFloatOrNull()
            val valid = value != null && value.isFinite() && value in parameter.min..parameter.max
            CompactButton(tr("parameters.applyKey"), {
                selected?.let { if (edit("move", listOf(value!!), it)) selected = value }
            }, enabled = writable && valid && selected != null && value != selected && keys.filter { it != selected }.none { abs(it - value!!) < EPS_SPAN })
            CompactButton("+", { value?.let(::add) }, enabled = writable && valid && keys.none { abs(it - value!!) < EPS_SPAN })
            CompactButton("−", { selected?.let { edit("delete", listOf(it)) } }, enabled = writable && selected != null && keys.isNotEmpty())
            Spacer(Modifier.weight(1f))
            for (count in listOf(2, 3, 5)) CompactButton(tr("parameters.addPoints", count), {
                edit("add", (0 until count).map { index ->
                    if (count == 3 && index == 1) parameter.default else parameter.min + (parameter.max - parameter.min) * index / (count - 1)
                }.distinct())
            }, enabled = writable && parameter.max > parameter.min)
        }
        Text(
            tr(if (parameter.kind == ParameterKind.BLEND_SHAPE) "parameters.blendKeysReadOnly" else "parameters.axisHelp"),
            style = typography.caption,
            color = colors.textMuted,
        )
    }
}
