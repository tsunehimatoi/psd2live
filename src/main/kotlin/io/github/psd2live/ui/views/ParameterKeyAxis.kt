package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.eval.EPS_SPAN
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.roundToInt

/** Trim binary float noise so axis labels stay readable. */
internal fun formatAxisValue(value: Float): String {
    if (!value.isFinite()) return "—"
    val rounded = (value * 1000f).roundToInt() / 1000f
    if (rounded == rounded.toInt().toFloat()) return rounded.toInt().toString()
    return "%.3f".format(rounded).trimEnd('0').trimEnd('.')
}

/** Multiples of [step] inside [min]..[max]; used as the snap lattice for key placement. */
internal fun parameterStepLattice(min: Float, max: Float, step: Float): List<Float> {
    if (!min.isFinite() || !max.isFinite() || !step.isFinite() || step <= 0f || min >= max) return emptyList()
    val first = kotlin.math.ceil(min / step.toDouble() - 1e-6).toInt()
    val last = kotlin.math.floor(max / step.toDouble() + 1e-6).toInt()
    if (first > last) return emptyList()
    return (first..last).map { (it.toDouble() * step.toDouble()).toFloat() }.filter { it in min..max }
}

/** Snap [value] onto the current step lattice, staying inside the parameter range. */
internal fun snapParameterValue(value: Float, min: Float, max: Float, step: Float?): Float {
    val clamped = value.coerceIn(min, max)
    if (step == null || step <= 0f) return clamped
    val lattice = parameterStepLattice(min, max, step)
    return lattice.minByOrNull { abs(it - clamped) } ?: clamped
}

/** Evenly space [count] stops from [min] to [max], inclusive when count >= 2. */
internal fun evenlyDistributedKeys(min: Float, max: Float, count: Int): List<Float> {
    if (!min.isFinite() || !max.isFinite() || min >= max || count <= 0) return emptyList()
    if (count == 1) return listOf(((min + max) / 2f).coerceIn(min, max))
    return (0 until count).map { index -> min + (max - min) * index / (count - 1) }
}

/** Keep dragged stops separated without silently moving or merging their neighbours. */
internal fun parameterKeyDestination(keys: List<Float>, from: Float, requested: Float, min: Float, max: Float): Float {
    if (!requested.isFinite() || from !in keys || min >= max) return from
    val gap = EPS_SPAN * 1.01f
    val low = maxOf(min, keys.filter { it < from }.maxOrNull()?.plus(gap) ?: min)
    val high = minOf(max, keys.filter { it > from }.minOrNull()?.minus(gap) ?: max)
    return if (low <= high) requested.coerceIn(low, high) else from
}

/** Stop rail. Range handles sit on the axis; the selected stop's number follows it and edits in place. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ParameterKeyAxis(
    keys: List<Float>,
    min: Float,
    max: Float,
    default: Float,
    selected: Float?,
    enabled: Boolean,
    snapStep: Float?,
    onSelect: (Float) -> Unit,
    onAdd: (Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onDelete: (Float) -> Unit,
    onRange: (Float, Float, Float) -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val select by rememberUpdatedState(onSelect)
    val add by rememberUpdatedState(onAdd)
    val move by rememberUpdatedState(onMove)
    val remove by rememberUpdatedState(onDelete)
    val range by rememberUpdatedState(onRange)
    var dragging by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var removing by remember { mutableStateOf(false) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(0) }
    var editingKey by remember { mutableStateOf<Float?>(null) }
    var editingEnd by remember { mutableStateOf<String?>(null) }
    val description = tr("parameters.axisHelp")
    val inset = 16.dp
    fun xOf(value: Float, width: Float): Float {
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val inner = (width - with(density) { inset.toPx() } * 2).coerceAtLeast(1f)
        return with(density) { inset.toPx() } + ((value - min) / span).coerceIn(0f, 1f) * inner
    }
    fun editEnd(which: String) {
        editingKey = null
        editingEnd = which
    }
    fun editKey(key: Float) {
        editingEnd = null
        onSelect(key)
        editingKey = key
    }
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { widthPx = it.size.width },
    ) {
        Box(Modifier.fillMaxWidth().height(16.dp)) {
            if (widthPx > 0) {
                val width = widthPx.toFloat()
                AxisEndNumber(min, tr("parameters.minimum"), enabled, editingEnd == "min", {
                    editEnd("min")
                }, {
                    editingEnd = null
                    val next = it
                    if (next.isFinite() && next < max) {
                        val neutral = default.coerceIn(next, max)
                        range(next, neutral, max)
                    }
                }, Modifier.align(Alignment.CenterStart))
                AxisEndNumber(default, tr("parameters.default"), enabled, editingEnd == "default", {
                    editEnd("default")
                }, {
                    editingEnd = null
                    if (it.isFinite() && it in min..max) range(min, it, max)
                }, Modifier.offset { IntOffset(xOf(default, width).roundToInt() - with(density) { 22.dp.roundToPx() }, 0) }.width(44.dp))
                AxisEndNumber(max, tr("parameters.maximum"), enabled, editingEnd == "max", {
                    editEnd("max")
                }, {
                    editingEnd = null
                    val next = it
                    if (next.isFinite() && min < next) {
                        val neutral = default.coerceIn(min, next)
                        range(min, neutral, next)
                    }
                }, Modifier.align(Alignment.CenterEnd))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(46.dp)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            .onPointerEvent(PointerEventType.Move) { event -> hoverX = event.changes.firstOrNull()?.position?.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (!enabled || event.button != PointerButton.Secondary) return@onPointerEvent
                val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                val pad = with(density) { inset.toPx() }
                val width = size.width.toFloat()
                fun xPx(value: Float) = pad + (value - min) / (max - min).coerceAtLeast(1e-6f) * (width - 2 * pad)
                val hit = keys.minByOrNull { abs(xPx(it) - x) }?.takeIf { abs(xPx(it) - x) <= with(density) { 8.dp.toPx() } }
                if (hit != null) {
                    select(hit)
                    remove(hit)
                    event.changes.forEach { it.consume() }
                }
            }
            .semantics { contentDescription = description }
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown || selected == null) false
                else when (event.key) {
                    Key.Delete, Key.Backspace -> { if (keys.isNotEmpty()) remove(selected); true }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val step = snapStep ?: ((max - min) / if (event.isShiftPressed) 100f else 1000f)
                        val requested = selected + if (event.key == Key.DirectionLeft) -step else step
                        val value = parameterKeyDestination(keys, selected, snapParameterValue(requested, min, max, snapStep), min, max)
                        if (value != selected) move(selected, value)
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            .pointerInput(keys, min, max, enabled, snapStep) {
                if (!enabled || min >= max) return@pointerInput
                val pad = inset.toPx()
                fun xPx(value: Float) = pad + (value - min) / (max - min) * (size.width - 2 * pad)
                fun valueOf(x: Float) = snapParameterValue(
                    min + ((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f) * (max - min),
                    min, max, snapStep,
                )
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (currentEvent.button != null && currentEvent.button != PointerButton.Primary) return@awaitEachGesture
                    // Taking focus commits any open number editor via onFocusLost.
                    focus.requestFocus()
                    val hit = keys.minByOrNull { abs(xPx(it) - down.position.x) }?.takeIf { abs(xPx(it) - down.position.x) <= 8.dp.toPx() }
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
                                removing = keys.isNotEmpty() && (last.y > size.height + 8.dp.toPx() || last.y < -16.dp.toPx())
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
            val pad = inset.toPx()
            val inner = (size.width - 2 * pad).coerceAtLeast(1f)
            fun xPx(value: Float) = pad + ((value - min) / (max - min)).coerceIn(0f, 1f) * inner
            val top = 8.dp.toPx()
            val bottom = 20.dp.toPx()
            drawRoundRect(colors.inputBackground, Offset(pad, top), Size(inner, bottom - top), CornerRadius(2.dp.toPx()))
            drawRoundRect(colors.border, Offset(pad, top), Size(inner, bottom - top), CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
            val pointer = hoverX
            if (pointer != null && dragging == null && enabled && keys.none { abs(xPx(it) - pointer) <= 8.dp.toPx() }) {
                val x = pointer.coerceIn(pad, pad + inner)
                drawLine(colors.accent.copy(alpha = 0.55f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            }
            if (snapStep != null && snapStep > 0f) {
                for (tick in parameterStepLattice(min, max, snapStep)) {
                    val x = xPx(tick)
                    drawLine(colors.textMuted.copy(alpha = 0.35f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                }
            }
            val neutralX = xPx(default)
            val notch = Path().apply {
                moveTo(neutralX, top - 5.dp.toPx())
                lineTo(neutralX - 3.5.dp.toPx(), top - 1.dp.toPx())
                lineTo(neutralX + 3.5.dp.toPx(), top - 1.dp.toPx())
                close()
            }
            drawPath(notch, colors.textMuted)
            for (key in keys) {
                val value = if (dragging?.first == key) dragging!!.second else key
                val x = xPx(value)
                val active = selected == key
                val tint = if (removing && dragging?.first == key) colors.error else if (active) colors.accent else colors.textMuted
                val stop = Path().apply {
                    moveTo(x, bottom)
                    lineTo(x - 4.5.dp.toPx(), bottom + 6.dp.toPx())
                    lineTo(x - 4.5.dp.toPx(), bottom + 16.dp.toPx())
                    lineTo(x + 4.5.dp.toPx(), bottom + 16.dp.toPx())
                    lineTo(x + 4.5.dp.toPx(), bottom + 6.dp.toPx())
                    close()
                }
                if (active) drawLine(tint, Offset(x, top), Offset(x, bottom), 1.5.dp.toPx())
                drawPath(stop, if (active) tint else colors.panelElevated)
                drawPath(stop, tint, style = Stroke(if (active) 1.5.dp.toPx() else 1.dp.toPx()))
            }
        }
        Box(Modifier.fillMaxWidth().height(18.dp)) {
            if (widthPx > 0) {
                val width = widthPx.toFloat()
                var lastLabel = Float.NEGATIVE_INFINITY
                val gap = with(density) { 28.dp.toPx() }
                for (key in keys.sortedBy { if (it == selected || dragging?.first == it) 1 else 0 }) {
                    val shown = if (dragging?.first == key) dragging!!.second else key
                    val active = selected == key || dragging?.first == key
                    val x = xOf(shown, width)
                    if (!active && x - lastLabel < gap) continue
                    if (!active) lastLabel = x
                    AxisEndNumber(
                        shown,
                        formatAxisValue(shown),
                        enabled && dragging == null,
                        editingKey == key,
                        { editKey(key) },
                        {
                            editingKey = null
                            val snapped = snapParameterValue(it, min, max, snapStep)
                            if (snapped.isFinite() && snapped in min..max && keys.filter { existing -> existing != key }.none { existing -> abs(existing - snapped) < EPS_SPAN }) {
                                if (snapped != key) move(key, snapped)
                            }
                        },
                        Modifier.offset { IntOffset(xOf(shown, width).roundToInt() - with(density) { 22.dp.roundToPx() }, 0) }.width(44.dp),
                        accent = active,
                    )
                }
            }
        }
    }
}

@Composable
private fun AxisEndNumber(
    value: Float,
    description: String,
    enabled: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier,
    accent: Boolean = false,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val fieldFocus = remember { FocusRequester() }
    var draft by remember(value, editing) { mutableStateOf(formatAxisValue(value)) }
    var committed by remember(editing) { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) runCatching { fieldFocus.requestFocus() } }
    fun commit() {
        if (!editing || committed) return
        committed = true
        onCommit(draft.toFloatOrNull() ?: value)
    }
    if (editing) {
        CompactTextField(
            draft,
            { draft = it },
            modifier = modifier
                .width(44.dp)
                .focusRequester(fieldFocus)
                .semantics { contentDescription = description },
            enabled = enabled,
            isMono = true,
            height = 18.dp,
            onCommit = { commit() },
            selectAllOnFocus = true,
            endEditOnSettle = false,
            onFocusLost = { commit() },
        )
    } else {
        Text(
            formatAxisValue(value),
            modifier = modifier
                .width(44.dp)
                .semantics { contentDescription = description }
                .clickable(enabled = enabled, onClickLabel = description, onClick = onEdit),
            style = typography.mono.copy(fontSize = 10.sp, textAlign = TextAlign.Center, color = if (accent) colors.accent else colors.textMuted),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
