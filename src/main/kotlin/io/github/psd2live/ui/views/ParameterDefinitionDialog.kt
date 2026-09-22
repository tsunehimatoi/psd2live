package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.psd2live.core.ParameterKeyEdits
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.InlineEditorRegions
import io.github.psd2live.ui.components.LocalInlineEditorRegions
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.edit.freshParameterId
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterRange
import org.umamo.edit.withParameterRenamed
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ParameterDefinitionDialog(
    parameter: Parameter?,
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val focusManager = LocalFocusManager.current
    val baseModel = remember { state.previewModel?.rig?.puppet }
    val expectedState = remember { state.historySnapshot?.headNodeId }
    var copying by remember { mutableStateOf(false) }
    val creating = parameter == null || copying
    var id by remember { mutableStateOf(parameter?.id?.raw ?: baseModel?.freshParameterId()?.raw.orEmpty()) }
    var name by remember { mutableStateOf(parameter?.name ?: tr("parameters.newName")) }
    var min by remember { mutableStateOf((parameter?.min ?: -1f).toString()) }
    var default by remember { mutableStateOf((parameter?.default ?: 0f).toString()) }
    var max by remember { mutableStateOf((parameter?.max ?: 1f).toString()) }
    var deleting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var keyEdits by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var undone by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val low = min.toFloatOrNull()
    val neutral = default.toFloatOrNull()
    val high = max.toFloatOrNull()
    val valid = id.isNotBlank() && name.isNotBlank() &&
        (!creating || baseModel?.parameters.orEmpty().none { it.id.raw == id.trim() }) &&
        low != null && neutral != null && high != null && low.isFinite() && neutral.isFinite() && high.isFinite() &&
        low < high && neutral in low..high
    val normalizedKeyEdits = keyEdits.map { JsonObject(it + ("parameter" to JsonPrimitive(id.trim()))) }
    val staged = remember(baseModel, id, name, low, neutral, high, valid, keyEdits, creating) {
        runCatching {
            check(valid)
            val base = requireNotNull(baseModel)
            val paramId = ParameterId(id.trim())
            val definition = if (creating) base.withParameterCreated(paramId, name.trim(), parameter?.kind ?: ParameterKind.NORMAL)
            else base.withParameterRenamed(paramId, name.trim())
            normalizedKeyEdits.fold(definition.withParameterRange(paramId, low, neutral, high), ParameterKeyEdits::apply)
        }
    }
    val fallback = remember(baseModel, parameter?.id, keyEdits) {
        val base = baseModel ?: return@remember null
        val existing = parameter ?: return@remember null
        if (keyEdits.isEmpty()) base else runCatching {
            keyEdits.map { JsonObject(it + ("parameter" to JsonPrimitive(existing.id.raw))) }
                .fold(base, ParameterKeyEdits::apply)
        }.getOrNull() ?: base
    }
    val draftModel = staged.getOrNull()
    val axisModel = draftModel ?: if (creating) null else fallback
    val axisParameter = when {
        draftModel != null -> draftModel.parameters.find { it.id.raw == id.trim() }
        else -> axisModel?.parameters?.find { it.id == parameter?.id }
    }
    val busy = state.canvasEditBusy
    fun submit(action: String) {
        // Flush any open inline editors before reading dialog state.
        focusManager.clearFocus(force = true)
        failure = null
        viewModel.saveParameterDefinition(
            action,
            id.trim(),
            name.trim(),
            low ?: 0f,
            neutral ?: 0f,
            high ?: 1f,
            parameterKind = parameter?.kind ?: ParameterKind.NORMAL,
            keyEdits = if (action == "delete") emptyList() else normalizedKeyEdits,
            expectedState = expectedState,
        ) { if (it == null) onDismiss() else failure = it }
    }
    val editorRegions = remember { InlineEditorRegions() }
    val focusState = rememberUpdatedState(focusManager)
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        CompositionLocalProvider(LocalInlineEditorRegions provides editorRegions) {
            Surface(color = colors.panelElevated, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, colors.divider)) {
                Column(
                    Modifier
                        .width(440.dp)
                        .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) {
                            editorRegions.pressedInside = false
                        }
                        .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Final) {
                            if (!editorRegions.pressedInside) focusState.value.clearFocus(force = true)
                        }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tr(when {
                            deleting -> "parameters.delete"
                            copying -> "parameters.duplicate"
                            parameter == null -> "parameters.create"
                            else -> "parameters.properties"
                        }),
                        style = typography.header,
                        color = colors.textPrimary,
                    )
                    if (deleting) {
                        Text(
                            tr("parameters.deleteWarning", parameter?.name.orEmpty()),
                            style = typography.body,
                            color = colors.textPrimary,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DefinitionField("ID", id, creating && !busy) { id = it }
                            DefinitionField(tr("parameters.fieldName"), name, !busy) { name = it }
                        }
                        if (copying) {
                            Text(tr("parameters.duplicateHint"), style = typography.caption, color = colors.textMuted)
                        }
                        if (!valid) {
                            Text(tr("parameters.invalidDefinition"), style = typography.caption, color = colors.error)
                        }
                        if (axisModel != null && axisParameter != null) {
                            ParameterKeysEditor(
                                parameter = axisParameter,
                                model = axisModel,
                                enabled = !busy && valid && staged.isSuccess,
                                canUndo = keyEdits.isNotEmpty(),
                                canRedo = undone.isNotEmpty(),
                                onUndo = {
                                    if (keyEdits.isNotEmpty()) {
                                        undone = undone + keyEdits.last()
                                        keyEdits = keyEdits.dropLast(1)
                                        failure = null
                                    }
                                },
                                onRedo = {
                                    if (undone.isNotEmpty()) {
                                        keyEdits = keyEdits + undone.last()
                                        undone = undone.dropLast(1)
                                        failure = null
                                    }
                                },
                                onRange = { nextMin, nextDefault, nextMax ->
                                    min = formatAxisValue(nextMin)
                                    default = formatAxisValue(nextDefault)
                                    max = formatAxisValue(nextMax)
                                },
                            ) { command ->
                                try {
                                    require(keyEdits.size < 127) { tr("parameters.axisEditLimit") }
                                    val next = ParameterKeyEdits.apply(draftModel ?: axisModel, command)
                                    if (next === (draftModel ?: axisModel)) return@ParameterKeysEditor false
                                    keyEdits = keyEdits + command
                                    undone = emptyList()
                                    failure = null
                                    true
                                } catch (error: IllegalArgumentException) {
                                    failure = error.message
                                    false
                                } catch (error: IllegalStateException) {
                                    failure = error.message
                                    false
                                }
                            }
                        }
                        if (valid && staged.isFailure) {
                            Text(staged.exceptionOrNull()?.message.orEmpty(), style = typography.caption, color = colors.error)
                        }
                    }
                    failure?.let { Text(it, style = typography.caption, color = colors.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!creating && !deleting) {
                            CompactButton(tr("parameters.delete"), { deleting = true }, enabled = !busy, danger = true, height = 22.dp)
                            CompactButton(tr("parameters.copy"), {
                                copying = true
                                deleting = false
                                keyEdits = emptyList()
                                undone = emptyList()
                                failure = null
                                id = baseModel?.freshParameterId()?.raw.orEmpty()
                                name = tr("parameters.copyName", parameter.name)
                            }, enabled = !busy, height = 22.dp)
                        }
                        Spacer(Modifier.weight(1f))
                        CompactButton(
                            tr("parameters.cancel"),
                            { if (deleting) deleting = false else onDismiss() },
                            enabled = !busy,
                            height = 22.dp,
                        )
                        CompactButton(
                            tr(if (deleting) "parameters.delete" else "parameters.save"),
                            { submit(if (deleting) "delete" else if (creating) "create" else "update") },
                            enabled = !busy && (deleting || (valid && staged.isSuccess)),
                            isPrimary = !deleting,
                            danger = deleting,
                            height = 22.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DefinitionField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = typography.caption, color = colors.textMuted)
        CompactTextField(value, onValue, modifier = Modifier.fillMaxWidth(), enabled = enabled, height = 22.dp)
    }
}
