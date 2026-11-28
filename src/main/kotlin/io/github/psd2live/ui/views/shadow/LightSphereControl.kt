package io.github.psd2live.ui.views.shadow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interactive 2D light sphere control: drag the light dot to adjust azimuth (-180..180)
 * and elevation (0..90). The handle follows the pointer and commits once on release.
 */
@Composable
internal fun LightSphereControl(
    azimuth: Float,
    elevation: Float,
    onCommit: (azimuth: Float, elevation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typo = LocalToolTypography.current
    var dragAz by remember { mutableStateOf(azimuth) }
    var dragEl by remember { mutableStateOf(elevation) }
    var dragging by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    val currentAzimuth by rememberUpdatedState(azimuth)

    val activeAz = if (dragging) dragAz else azimuth
    val activeEl = if (dragging) dragEl else elevation

    fun updateFromPoint(point: Offset, sizePx: Float): Pair<Float, Float> {
        val radius = (sizePx * 0.44f).coerceAtLeast(1f)
        val cx = sizePx * 0.5f
        val cy = sizePx * 0.5f
        val dx = point.x - cx
        val dy = point.y - cy
        val dist = hypot(dx, dy).coerceAtMost(radius)
        val norm = (dist / radius).coerceIn(0f, 1f)
        // Center = high elevation (90°), rim = low grazing elevation (8°)
        val el = (90f - norm * 82f).coerceIn(5f, 90f)
        // Top (-y) = 0° azimuth; right (+x) = +90°; left (-x) = -90°
        val az = if (dist < 1f) {
            if (dragging) dragAz else currentAzimuth
        } else (atan2(dx, -dy) * (180f / PI.toFloat())).coerceIn(-180f, 180f)
        return az to el
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(72.dp)
                .handCursor()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val (az, el) = updateFromPoint(offset, size.width.toFloat())
                        dragAz = az
                        dragEl = el
                        commit(az, el)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val (az, el) = updateFromPoint(offset, size.width.toFloat())
                            dragAz = az
                            dragEl = el
                            dragging = true
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val (az, el) = updateFromPoint(change.position, size.width.toFloat())
                            dragAz = az
                            dragEl = el
                        },
                        onDragEnd = {
                            dragging = false
                            commit(dragAz, dragEl)
                        },
                        onDragCancel = {
                            dragging = false
                        },
                    )
                },
        ) {
            val radius = size.minDimension * 0.44f
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val azRad = activeAz * (PI.toFloat() / 180f)
            val rNorm = ((90f - activeEl) / 82f).coerceIn(0f, 1f)
            val handle = Offset(
                center.x + sin(azRad) * radius * rNorm,
                center.y - cos(azRad) * radius * rNorm,
            )

            // Shaded sphere preview
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accent.copy(alpha = 0.42f), colors.panelElevated, colors.panelBackground),
                    center = handle,
                    radius = radius * 1.45f,
                ),
                radius = radius,
                center = center,
            )
            // Subtle latitude ring (45 deg)
            drawCircle(
                color = colors.border.copy(alpha = 0.4f),
                radius = radius * 0.55f,
                center = center,
                style = Stroke(width = 1f),
            )
            // Outer rim
            drawCircle(
                color = colors.border,
                radius = radius,
                center = center,
                style = Stroke(width = 1.25f),
            )
            // Direction ray from center to handle
            drawLine(
                color = colors.accent.copy(alpha = 0.55f),
                start = center,
                end = handle,
                strokeWidth = 1.5f,
            )
            // Draggable light handle
            drawCircle(color = colors.accent, radius = 5.dp.toPx(), center = handle)
            drawCircle(color = colors.panelBackground, radius = 2.dp.toPx(), center = handle)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = tr("shadow.world.azimuth", activeAz.roundToInt()),
                style = typo.caption,
                color = colors.textMuted,
            )
            Text(
                text = tr("shadow.world.elevation", activeEl.roundToInt()),
                style = typo.caption,
                color = colors.textMuted,
            )
        }
    }
}
