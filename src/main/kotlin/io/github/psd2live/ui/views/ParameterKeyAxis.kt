package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import java.awt.Cursor
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.eval.EPS_SPAN
import kotlin.math.abs

/** Trim binary float noise so axis labels stay readable. */
internal fun formatAxisValue(value: Float): String {
    if (!value.isFinite()) return "—"
    val rounded = (value * 1000f).roundToInt() / 1000f
    if (rounded == rounded.toInt().toFloat()) return rounded.toInt().toString()
    return "%.3f".format(rounded).trimEnd('0').trimEnd('.')
}

/** Keep dragged stops separated without silently moving or merging their neighbours. */
internal fun parameterKeyDestination(keys: List<Float>, from: Float, requested: Float, min: Float, max: Float): Float {
    if (!requested.isFinite() || from !in keys || min >= max) return from
    val gap = EPS_SPAN * 1.01f
    val low = maxOf(min, keys.filter { it < from }.maxOrNull()?.plus(gap) ?: min)
    val high = minOf(max, keys.filter { it > from }.minOrNull()?.minus(gap) ?: max)
    return if (low <= high) requested.coerceIn(low, high) else from
}

/** Photoshop-style stop rail: empty-space click inserts, handles drag, dragging out removes. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ParameterKeyAxis(
    keys: List<Float>, min: Float, max: Float, default: Float, selected: Float?, enabled: Boolean,
    onSelect: (Float) -> Unit, onAdd: (Float) -> Unit, onMove: (Float, Float) -> Unit, onDelete: (Float) -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val focus = remember { FocusRequester() }
    val select by rememberUpdatedState(onSelect)
    val add by rememberUpdatedState(onAdd)
    val move by rememberUpdatedState(onMove)
    val remove by rememberUpdatedState(onDelete)
    var dragging by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var removing by remember { mutableStateOf(false) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val description = tr("parameters.axisHelp")
    Column {
        Canvas(Modifier.fillMaxWidth().height(72.dp)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            .onPointerEvent(PointerEventType.Move) { event -> hoverX = event.changes.firstOrNull()?.position?.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .semantics { contentDescription = description }
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown || selected == null) false
                else when (event.key) {
                    Key.Delete, Key.Backspace -> { if (keys.isNotEmpty()) remove(selected); true }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val step = (max - min) / if (event.isShiftPressed) 100f else 1000f
                        val value = parameterKeyDestination(keys, selected, selected + if (event.key == Key.DirectionLeft) -step else step, min, max)
                        if (value != selected) move(selected, value)
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            .pointerInput(keys, min, max, enabled) {
                if (!enabled || min >= max) return@pointerInput
                val inset = 12.dp.toPx()
                fun xOf(value: Float) = inset + (value - min) / (max - min) * (size.width - 2 * inset)
                fun valueOf(x: Float) = (min + ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f) * (max - min))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (currentEvent.button != null && currentEvent.button != PointerButton.Primary) return@awaitEachGesture
                    focus.requestFocus()
                    val hit = keys.minByOrNull { abs(xOf(it) - down.position.x) }?.takeIf { abs(xOf(it) - down.position.x) <= 9.dp.toPx() }
                    var last = down.position
                    var moved = false
                    if (hit != null) select(hit)
                    down.consume()
                    try {
                        val released = drag(down.id) { change ->
                            last = change.position
                            moved = moved || (last - down.position).getDistance() > 3.dp.toPx()
                            if (hit != null && moved) {
                                dragging = hit to parameterKeyDestination(keys, hit, valueOf(last.x), min, max)
                                removing = keys.isNotEmpty() && (last.y > size.height + 12.dp.toPx() || last.y < -20.dp.toPx())
                            }
                            change.consume()
                        }
                        if (released) {
                            if (hit == null && !moved) add(valueOf(last.x))
                            else if (hit != null && moved) {
                                val destination = dragging?.second ?: hit
                                if (removing) remove(hit) else if (destination != hit) move(hit, destination)
                            }
                        }
                    } finally { dragging = null; removing = false }
                }
            }) {
            val inset = 12.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            fun xOf(value: Float) = inset + ((value - min) / (max - min)).coerceIn(0f, 1f) * width
            val top = 12.dp.toPx()
            val bottom = 34.dp.toPx()
            drawRoundRect(colors.controlBackground, Offset(inset, top), Size(width, bottom - top), CornerRadius(3.dp.toPx()))
            drawRoundRect(colors.border, Offset(inset, top), Size(width, bottom - top), CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
            val pointer = hoverX
            if (pointer != null && dragging == null && enabled && keys.none { abs(xOf(it) - pointer) <= 9.dp.toPx() }) {
                val x = pointer.coerceIn(inset, inset + width)
                drawLine(colors.accent.copy(alpha = 0.45f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            }
            for (index in 0..10) {
                val x = inset + width * index / 10f
                drawLine(colors.textMuted.copy(alpha = 0.45f), Offset(x, top), Offset(x, top + 4.dp.toPx()), 1.dp.toPx())
            }
            val neutralX = xOf(default)
            drawLine(colors.textMuted, Offset(neutralX, top - 5.dp.toPx()), Offset(neutralX, bottom), 1.dp.toPx())
            for (key in keys) {
                val value = if (dragging?.first == key) dragging!!.second else key
                val x = xOf(value)
                val active = selected == key
                val tint = if (removing && dragging?.first == key) colors.error else if (active) colors.accent else colors.textMuted
                val stop = Path().apply {
                    moveTo(x, bottom + 2.dp.toPx())
                    lineTo(x - 6.dp.toPx(), bottom + 9.dp.toPx())
                    lineTo(x - 6.dp.toPx(), bottom + 21.dp.toPx())
                    lineTo(x + 6.dp.toPx(), bottom + 21.dp.toPx())
                    lineTo(x + 6.dp.toPx(), bottom + 9.dp.toPx())
                    close()
                }
                if (active) drawLine(tint, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
                drawPath(stop, if (active) tint else colors.panelElevated)
                drawPath(stop, tint, style = Stroke(if (active) 2.dp.toPx() else 1.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatAxisValue(min), style = typography.mono, color = colors.textMuted)
            Text((dragging?.second ?: selected)?.let(::formatAxisValue) ?: "—", style = typography.mono, color = colors.accent)
            Text(formatAxisValue(max), style = typography.mono, color = colors.textMuted)
        }
    }
}
