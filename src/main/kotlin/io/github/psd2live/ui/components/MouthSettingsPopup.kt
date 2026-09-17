package io.github.psd2live.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

@Composable
fun MouthSettingsPopupButton(
    state: PSD2LiveState,
    enabled: Boolean,
    onApply: (String, MouthCurve) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val sampledColor = remember(state.analysis, state.alphaThreshold) {
        state.analysis?.layers?.firstOrNull {
            it.source !is MouthLipLayer && it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && it.opaquePixels > 0
        }?.let { MouthLipLayers.perimeterColor(it.source.raster, state.alphaThreshold) } ?: 0x482C32
    }
    val rgb = state.mouthColor ?: sampledColor
    Box(Modifier.fillMaxWidth()) {
        CompactButton(
            text = tr("mouth.settings") + " · " + tr("mouth.shape.${state.mouthShape}"),
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), height = 22.dp,
        )
        if (expanded) Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, with(density) { 26.dp.roundToPx() }),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            MouthSettingsEditor(
                initialShape = state.mouthShape,
                initialCurve = state.mouthCurve,
                previewColor = rgb,
                previewThickness = state.mouthThickness,
                onApply = { shape, curve ->
                    onApply(shape, curve)
                    expanded = false
                },
                onDismiss = { expanded = false }
            )
        }
    }
}

@Composable
internal fun MouthSettingsEditor(
    initialShape: String,
    initialCurve: MouthCurve,
    previewColor: Int,
    previewThickness: Float,
    onApply: (String, MouthCurve) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    var shape by remember { mutableStateOf(initialShape) }
    var curve by remember { mutableStateOf(if (initialShape == "custom") initialCurve else MouthCurve.preset(initialShape)) }
    var selected by remember { mutableStateOf(3) }
    fun changeCurve(next: MouthCurve) { curve = next; shape = "custom" }

    Surface(
        color = colors.panelElevated,
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(6.dp),
        elevation = 6.dp
    ) {
        Column(
            Modifier.width(380.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("mouth.settings"), style = typography.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                CompactIconButton(onClick = onDismiss, size = 20.dp) { IconClose(Modifier.size(10.dp), tint = colors.textMuted) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("mouth.preset"), fontSize = 11.sp, color = colors.textPrimary)
                CompactDropdown(
                    items = MouthCurve.presets + "custom",
                    selectedItem = shape,
                    onItemSelected = {
                        shape = it
                        if (it != "custom") {
                            curve = MouthCurve.preset(it)
                            onApply(shape, curve)
                        }
                    },
                    itemLabel = { tr("mouth.shape.$it") },
                    modifier = Modifier.weight(1f),
                    height = 22.dp
                )
            }
            BezierMouthCanvas(curve, selected, previewColor, previewThickness, onSelect = { selected = it }, onChange = ::changeCurve)
            Text(tr("mouth.curve.hint"), fontSize = 10.sp, color = colors.textMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactDropdown(
                    items = (0..6).toList(),
                    selectedItem = selected,
                    onItemSelected = { selected = it },
                    itemLabel = { tr(if (it % 3 == 0) "mouth.anchor" else "mouth.control") + " ${it + 1}" },
                    modifier = Modifier.weight(1f),
                    height = 22.dp
                )
                Text("X", color = colors.textMuted, fontSize = 10.sp)
                CompactNumberSpinner(
                    value = (curve.points[selected].x * 100).toDouble(),
                    onValueChange = { changeCurve(curve.move(selected, it.toFloat() / 100, curve.points[selected].y)) },
                    min = 0.0, max = 100.0, step = 1.0, decimals = 1, unit = "%",
                    enabled = selected != 0 && selected != 6, modifier = Modifier.width(65.dp), height = 22.dp
                )
                Text("Y", color = colors.textMuted, fontSize = 10.sp)
                CompactNumberSpinner(
                    value = (curve.points[selected].y * 100).toDouble(),
                    onValueChange = { changeCurve(curve.move(selected, curve.points[selected].x, it.toFloat() / 100)) },
                    min = -50.0, max = 50.0, step = 1.0, decimals = 1, unit = "%",
                    modifier = Modifier.width(65.dp), height = 22.dp
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CompactButton(text = tr("mouth.cancel"), onClick = onDismiss, height = 24.dp)
                Spacer(Modifier.width(8.dp))
                CompactButton(
                    text = tr("mouth.apply"),
                    onClick = { onApply(shape, curve) },
                    isPrimary = true,
                    height = 24.dp
                )
            }
        }
    }
}

@Composable
private fun BezierMouthCanvas(
    curve: MouthCurve, selected: Int, rgb: Int, thickness: Float,
    onSelect: (Int) -> Unit, onChange: (MouthCurve) -> Unit,
) {
    val colors = LocalToolColors.current
    val currentCurve by rememberUpdatedState(curve)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentChange by rememberUpdatedState(onChange)
    val description = tr("mouth.curve.hint")
    fun point(p: MouthCurvePoint, w: Float, h: Float) = Offset(20f + p.x * (w - 40f), h * 0.5f + p.y * h * 0.8f)
    Canvas(Modifier.fillMaxWidth().height(170.dp).background(colors.inputBackground, RoundedCornerShape(3.dp))
        .border(1.dp, colors.border, RoundedCornerShape(3.dp))
        .semantics { contentDescription = description }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val active = currentCurve.points.indices.minByOrNull {
                    (point(currentCurve.points[it], size.width.toFloat(), size.height.toFloat()) - down.position).getDistance()
                }?.takeIf {
                    (point(currentCurve.points[it], size.width.toFloat(), size.height.toFloat()) - down.position).getDistance() < 22.dp.toPx()
                }
                if (active != null) {
                    down.consume()
                    currentSelect(active)
                    drag(down.id) { change ->
                        change.consume()
                        currentChange(currentCurve.move(active, (change.position.x - 20f) / (size.width - 40f),
                            (change.position.y - size.height * 0.5f) / (size.height * 0.8f)))
                    }
                }
            }
        }) {
        for (i in 1..3) drawLine(colors.divider, Offset(size.width * i / 4f, 8f), Offset(size.width * i / 4f, size.height - 8f))
        drawLine(colors.divider, Offset(10f, size.height * 0.5f), Offset(size.width - 10f, size.height * 0.5f))
        val pts = curve.points.map { point(it, size.width, size.height) }
        for ((a,b) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6)) {
            drawLine(colors.textDisabled, pts[a], pts[b], 1f)
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            cubicTo(pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
            cubicTo(pts[4].x, pts[4].y, pts[5].x, pts[5].y, pts[6].x, pts[6].y)
        }
        // Pale backing keeps dark sampled colors visible in the application's dark theme.
        drawPath(path, Color(0xFFFFE8DA), style = Stroke(thickness * 2f + 3f, cap = StrokeCap.Round))
        drawPath(path, Color(0xFF000000L or rgb.toLong()), style = Stroke(thickness * 2f, cap = StrokeCap.Round))
        for ((i,p) in pts.withIndex()) {
            if (i == selected) drawCircle(colors.accent.copy(alpha = 0.25f), 10.dp.toPx(), p)
            if (i % 3 == 0) drawRect(colors.accent, p - Offset(4.dp.toPx(), 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()))
            else drawCircle(colors.accent, 4.dp.toPx(), p)
        }
    }
}
