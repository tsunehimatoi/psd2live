package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import javax.swing.JColorChooser
import javax.swing.SwingUtilities

/** Vector Palette Icon */
@Composable
fun IconPalette(
    modifier: Modifier = Modifier.size(16.dp),
    tint: Color = LocalToolColors.current.textPrimary,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.1f)
            cubicTo(w * 0.85f, h * 0.1f, w * 0.95f, h * 0.4f, w * 0.9f, h * 0.65f)
            cubicTo(w * 0.85f, h * 0.9f, w * 0.6f, h * 0.95f, w * 0.5f, h * 0.85f)
            cubicTo(w * 0.45f, h * 0.8f, w * 0.35f, h * 0.8f, w * 0.3f, h * 0.85f)
            cubicTo(w * 0.15f, h * 0.95f, w * 0.05f, h * 0.75f, w * 0.1f, h * 0.5f)
            cubicTo(w * 0.15f, h * 0.2f, w * 0.3f, h * 0.1f, w * 0.5f, h * 0.1f)
            close()
        }
        drawPath(path, color = tint, style = stroke)
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.32f, h * 0.32f))
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.52f, h * 0.26f))
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.72f, h * 0.35f))
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.76f, h * 0.58f))
    }
}

/**
 * Clickable color swatch button that pops up an interactive color picker.
 */
@Composable
fun ColorPickerSwatch(
    color: Int,
    enabled: Boolean,
    onColorChanged: (Int) -> Unit,
    sampledColor: Int? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val colors = LocalToolColors.current

    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                .background(colors.inputBackground)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .background(Color(0xFF000000L or color.toLong()), RoundedCornerShape(3.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(3.dp))
            )
            IconPalette(
                modifier = Modifier.size(12.dp),
                tint = if (enabled) colors.textPrimary else colors.textDisabled
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { 26.dp.roundToPx() }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                ColorPickerPopupContent(
                    initialColor = color,
                    sampledColor = sampledColor,
                    onColorChanged = onColorChanged,
                    onDismiss = { expanded = false },
                )
            }
        }
    }
}

/** The colour as the string an artist would type: `#RRGGBB`. */
internal fun Color.toHex(): String {
    val argb = toArgb()
    return "#%02X%02X%02X".format((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)
}

/**
 * A paint colour chip that opens the colour picker below it.
 *
 * @param Color color                The colour shown, always drawn opaque.
 * @param Function onColorChanged    Receives the picked colour, with the alpha channel restored.
 * @param Dp popupOffset             Distance from the chip's top edge to the popup, which is what
 *                                   clears the chip whatever size the caller gave it.
 */
@Composable
fun PaintColorChip(
    color: Color,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
    popupOffset: Dp = 30.dp,
    shape: Shape = RoundedCornerShape(4.dp),
    border: BorderStroke = BorderStroke(1.dp, LocalToolColors.current.border),
) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // Modifier must sit on the root so parent BoxScope.align / size actually take effect.
    Box(
        modifier
            .clip(shape)
            .background(color)
            .border(border, shape)
            .clickable { expanded = true }
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
    ) {
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { popupOffset.roundToPx() }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                ColorPickerPopupContent(
                    initialColor = color.toArgb() and 0xFFFFFF,
                    sampledColor = null,
                    onColorChanged = { rgb -> onColorChanged(Color(0xFF000000L or rgb.toLong())) },
                    onDismiss = { expanded = false },
                )
            }
        }
    }
}

/**
 * Photoshop-style foreground / background swatch: overlapping squares (FG top-left in front,
 * BG bottom-right behind) with a tiny swap control in the upper-right corner.
 *
 * @param Dp squareSize   Edge length of each colour square; overall footprint grows slightly for the swap hit target.
 */
@Composable
fun PaintFgBgSwatch(
    foreground: Color,
    background: Color,
    onForegroundChanged: (Color) -> Unit,
    onBackgroundChanged: (Color) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
    squareSize: Dp = 18.dp,
) {
    val colors = LocalToolColors.current
    val swapHit = (squareSize * 0.48f).coerceIn(9.dp, 12.dp)
    // Modest stagger so both faces show without bloating the toolbar row height.
    val stagger = squareSize * 0.36f
    val footprint = squareSize + stagger
    val shape = RoundedCornerShape(1.dp)
    val thin = BorderStroke(0.5.dp, colors.border)
    val thinAccent = BorderStroke(0.5.dp, colors.accent)

    Box(modifier = modifier.size(footprint)) {
        PaintColorChip(
            color = background,
            onColorChanged = onBackgroundChanged,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(squareSize),
            popupOffset = squareSize + 4.dp,
            shape = shape,
            border = thin,
        )
        PaintColorChip(
            color = foreground,
            onColorChanged = onForegroundChanged,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(squareSize),
            popupOffset = squareSize + 4.dp,
            shape = shape,
            border = thinAccent,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(swapHit)
                .clip(RoundedCornerShape(2.dp))
                .clickable(onClick = onSwap)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
            contentAlignment = Alignment.Center,
        ) {
            IconPaintColorSwap(
                modifier = Modifier.size(swapHit * 0.7f),
                tint = colors.textMuted,
            )
        }
    }
}

/** Tiny double-curved arrows for swapping foreground and background, like Photoshop's swatch control. */
@Composable
fun IconPaintColorSwap(
    modifier: Modifier = Modifier.size(10.dp),
    tint: Color = LocalToolColors.current.textMuted,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = (w * 0.14f).coerceAtLeast(1f), cap = StrokeCap.Round)
        // Upper arc: right → left with arrowhead on the left.
        val top = Path().apply {
            moveTo(w * 0.78f, h * 0.28f)
            quadraticTo(w * 0.5f, h * 0.02f, w * 0.22f, h * 0.28f)
        }
        drawPath(top, color = tint, style = stroke)
        drawPath(
            Path().apply {
                moveTo(w * 0.22f, h * 0.28f)
                lineTo(w * 0.32f, h * 0.12f)
                moveTo(w * 0.22f, h * 0.28f)
                lineTo(w * 0.36f, h * 0.34f)
            },
            color = tint,
            style = stroke,
        )
        // Lower arc: left → right with arrowhead on the right.
        val bottom = Path().apply {
            moveTo(w * 0.22f, h * 0.72f)
            quadraticTo(w * 0.5f, h * 0.98f, w * 0.78f, h * 0.72f)
        }
        drawPath(bottom, color = tint, style = stroke)
        drawPath(
            Path().apply {
                moveTo(w * 0.78f, h * 0.72f)
                lineTo(w * 0.68f, h * 0.88f)
                moveTo(w * 0.78f, h * 0.72f)
                lineTo(w * 0.64f, h * 0.66f)
            },
            color = tint,
            style = stroke,
        )
    }
}

/**
 * Interactive popup content with 2D Saturation-Value box, 1D Hue bar, Hex text field,
 * quick presets, and system color chooser dialog.
 */
@Composable
fun ColorPickerPopupContent(
    initialColor: Int,
    sampledColor: Int?,
    onColorChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    val initialHsb = remember(initialColor) {
        val arr = FloatArray(3)
        java.awt.Color.RGBtoHSB((initialColor shr 16) and 0xFF, (initialColor shr 8) and 0xFF, initialColor and 0xFF, arr)
        arr
    }
    var hue by remember { mutableStateOf(initialHsb[0]) }
    var sat by remember { mutableStateOf(initialHsb[1]) }
    var bri by remember { mutableStateOf(initialHsb[2]) }
    var currentColor by remember { mutableStateOf(initialColor) }
    var hexText by remember { mutableStateOf("%06X".format(initialColor)) }

    fun updateHsb(h: Float, s: Float, b: Float) {
        hue = h.coerceIn(0f, 1f)
        sat = s.coerceIn(0f, 1f)
        bri = b.coerceIn(0f, 1f)
        val rgb = java.awt.Color.HSBtoRGB(hue, sat, bri) and 0xFFFFFF
        currentColor = rgb
        hexText = "%06X".format(rgb)
        onColorChanged(rgb)
    }

    fun updateRgb(rgb: Int) {
        val cleanRgb = rgb and 0xFFFFFF
        currentColor = cleanRgb
        hexText = "%06X".format(cleanRgb)
        val arr = FloatArray(3)
        java.awt.Color.RGBtoHSB((cleanRgb shr 16) and 0xFF, (cleanRgb shr 8) and 0xFF, cleanRgb and 0xFF, arr)
        hue = arr[0]
        sat = arr[1]
        bri = arr[2]
        onColorChanged(cleanRgb)
    }

    val hueColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000),
        )
    }

    val presets = remember {
        listOf(
            0x482C32, // Soft plum (default)
            0x2D1E22, // Deep chocolate
            0x663339, // Dark berry
            0x8A454E, // Dusty rose
            0xA85858, // Warm coral
            0xC06878, // Cherry pink
            0x222222, // Charcoal
            0x000000, // Black
        )
    }

    Surface(
        color = colors.panelElevated,
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(6.dp),
        elevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.width(240.dp).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("mouth.colorPicker"),
                    style = typography.title.copy(fontSize = 12.sp),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                CompactIconButton(onClick = onDismiss, size = 18.dp) {
                    IconClose(Modifier.size(9.dp), tint = colors.textMuted)
                }
            }

            // 2D Saturation-Value box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(4.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(hue) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val update = { pos: Offset ->
                                    val s = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val v = (1f - pos.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    updateHsb(hue, s, v)
                                }
                                down.consume()
                                update(down.position)
                                drag(down.id) { change ->
                                    change.consume()
                                    update(change.position)
                                }
                            }
                        }
                ) {
                    val pureHue = Color(java.awt.Color.HSBtoRGB(hue, 1f, 1f))
                    drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHue)))
                    drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val cx = sat * size.width
                    val cy = (1f - bri) * size.height
                    drawCircle(Color.Black, radius = 6.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
                    drawCircle(Color.White, radius = 4.5.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
                }
            }

            // 1D Hue bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(3.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val update = { pos: Offset ->
                                    val h = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    updateHsb(h, sat, bri)
                                }
                                down.consume()
                                update(down.position)
                                drag(down.id) { change ->
                                    change.consume()
                                    update(change.position)
                                }
                            }
                        }
                ) {
                    drawRect(brush = Brush.horizontalGradient(hueColors))
                    val hx = hue * size.width
                    drawLine(Color.White, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color.Black, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                }
            }

            // Swatch, Hex input, and System chooser
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(0xFF000000L or currentColor.toLong()), RoundedCornerShape(3.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(3.dp))
                )
                CompactTextField(
                    value = hexText,
                    onValueChange = { newHex ->
                        hexText = newHex
                        val cleaned = newHex.trim().removePrefix("#")
                        if (cleaned.length == 6) {
                            cleaned.toIntOrNull(16)?.let { updateRgb(it) }
                        }
                    },
                    isMono = true,
                    placeholder = "#RRGGBB",
                    modifier = Modifier.weight(1f),
                    height = 22.dp
                )
                CompactButton(
                    text = tr("mouth.color.system"),
                    onClick = {
                        SwingUtilities.invokeLater {
                            val chosen = JColorChooser.showDialog(
                                null,
                                tr("mouth.colorPicker"),
                                java.awt.Color(currentColor)
                            )
                            if (chosen != null) {
                                updateRgb(chosen.rgb and 0xFFFFFF)
                            }
                        }
                    },
                    height = 22.dp
                )
            }

            // Presets
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("mouth.color.presets"),
                    style = typography.caption.copy(fontSize = 10.sp),
                    color = colors.textMuted
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (sampledColor != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF000000L or sampledColor.toLong()))
                                .border(
                                    if (currentColor == sampledColor) 1.5.dp else 1.dp,
                                    if (currentColor == sampledColor) colors.accent else colors.border,
                                    RoundedCornerShape(3.dp)
                                )
                                .clickable { updateRgb(sampledColor) }
                                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                        ) {
                            Text("A", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    for (preset in presets) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF000000L or preset.toLong()))
                                .border(
                                    if (currentColor == preset) 1.5.dp else 1.dp,
                                    if (currentColor == preset) colors.accent else colors.border,
                                    RoundedCornerShape(3.dp)
                                )
                                .clickable { updateRgb(preset) }
                                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                        )
                    }
                }
            }
        }
    }
}
