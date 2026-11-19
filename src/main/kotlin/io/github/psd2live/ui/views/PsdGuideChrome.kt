package io.github.psd2live.ui.views

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing

internal enum class GuideBannerTone { INFO, SUCCESS, WARNING, ERROR }

/** Standard DCC panel module container matching [DockWorkspaceView]. */
@Composable
internal fun GuideModuleFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalToolColors.current
    val shape = RoundedCornerShape(Corners.Panel)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.panelBackground, shape)
            .border(1.dp, colors.divider, shape),
        content = content,
    )
}

/**
 * Independent right-hand inspector module with a standard DCC module title bar and optional
 * scrollable body with desktop [VerticalScrollbar].
 */
@Composable
internal fun GuideInspectorModule(
    title: String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    GuideModuleFrame(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(colors.panelElevated)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
        ) {
            Text(
                text = title,
                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (headerTrailing != null) {
                headerTrailing()
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
        if (!scrollable) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        } else {
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
                if (scroll.canScrollForward || scroll.canScrollBackward) {
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                    )
                }
            }
        }
    }
}

/** Restrained 1-level DCC card for grouping related controls inside a guide module. */
@Composable
internal fun GuideSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalToolColors.current
    val shape = RoundedCornerShape(Corners.Panel)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panelElevated, shape)
            .border(1.dp, colors.divider, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap),
        content = content,
    )
}

/** Crisp section heading with optional trailing controls and muted subtitle. */
@Composable
internal fun GuideSectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = typography.header.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                    content = trailing,
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = typography.caption.copy(lineHeight = 14.sp),
                color = colors.textMuted,
            )
        }
    }
}

/** Muted helper caption inside guide panels. */
@Composable
internal fun GuideNote(text: String, modifier: Modifier = Modifier) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Text(
        text = text,
        style = typography.caption.copy(lineHeight = 15.sp),
        color = colors.textMuted,
        modifier = modifier,
    )
}

/** Compact rounded pill badge using DCC control corners. */
@Composable
internal fun GuideBadge(
    text: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val typography = LocalToolTypography.current
    val shape = RoundedCornerShape(Corners.Control)
    Box(
        modifier = modifier
            .clip(shape)
            .background(background, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typography.captionSmall.copy(fontWeight = FontWeight.Medium),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Restrained DCC notice box with status indicator and optional compact action button. */
@Composable
internal fun GuideInfoBanner(
    text: String,
    tone: GuideBannerTone = GuideBannerTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val toneAccent = when (tone) {
        GuideBannerTone.INFO -> colors.accent
        GuideBannerTone.SUCCESS -> colors.highlight
        GuideBannerTone.WARNING -> colors.warning
        GuideBannerTone.ERROR -> colors.error
    }
    val shape = RoundedCornerShape(Corners.Control)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(toneAccent.copy(alpha = 0.08f), shape)
            .border(1.dp, toneAccent.copy(alpha = 0.30f), shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tone == GuideBannerTone.SUCCESS) {
            IconCheck(modifier = Modifier.size(11.dp), tint = toneAccent)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(toneAccent))
        }
        Text(
            text = text,
            style = typography.caption.copy(lineHeight = 15.sp),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            CompactButton(
                text = actionLabel,
                onClick = onAction,
                enabled = enabled,
                height = 22.dp,
            )
        }
    }
}

/** Draws a clipped transparency checkerboard across [area] starting at [origin]. */
internal fun DrawScope.drawGuideChecker(
    origin: Offset = Offset.Zero,
    area: Size = size,
    light: Color,
    dark: Color,
    cell: Float = 8.dp.toPx(),
) {
    if (area.width <= 0f || area.height <= 0f) return
    val step = cell.coerceAtLeast(1f)
    drawRect(light, origin, area)
    var y = 0f
    var row = 0
    while (y < area.height) {
        var x = if (row % 2 == 0) 0f else step
        while (x < area.width) {
            drawRect(
                color = dark,
                topLeft = Offset(origin.x + x, origin.y + y),
                size = Size(minOf(step, area.width - x), minOf(step, area.height - y)),
            )
            x += step * 2f
        }
        y += step
        row++
    }
}

/** Edge-to-edge canvas surface shared across PSD import previews. */
@Composable
internal fun GuideCanvasFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalToolColors.current
    Box(
        modifier = modifier.background(colors.inputBackground),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Compact elevated status pill layered over a preview canvas. */
@Composable
internal fun GuideStatusCapsule(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val shape = RoundedCornerShape(Corners.Control)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.panelElevated.copy(alpha = 0.92f), shape)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typography.captionSmall,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shared indeterminate progress arc using [Motion.Pulse]. */
@Composable
internal fun GuideSpinner(
    modifier: Modifier = Modifier,
    color: Color = LocalToolColors.current.accent,
    trackColor: Color = LocalToolColors.current.border.copy(alpha = 0.45f),
    strokeWidth: Dp = 2.dp,
) {
    val angle by rememberInfiniteTransition("guide-spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Motion.Pulse, easing = LinearEasing), RepeatMode.Restart),
        label = "guide-spinner-angle",
    )
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val diameter = (minOf(size.width, size.height) - stroke).coerceAtLeast(1f)
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = angle - 90f,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
