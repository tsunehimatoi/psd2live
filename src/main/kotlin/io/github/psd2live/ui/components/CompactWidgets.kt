package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.abs
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import java.awt.Cursor

/** Vector Eye Icon (Visible or Hidden/Crossed-out) */
@Composable
fun IconEye(
	visible: Boolean,
	modifier: Modifier = Modifier.size(16.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val eyePath = Path().apply {
			moveTo(w * 0.1f, h * 0.5f)
			cubicTo(w * 0.3f, h * 0.2f, w * 0.7f, h * 0.2f, w * 0.9f, h * 0.5f)
			cubicTo(w * 0.7f, h * 0.8f, w * 0.3f, h * 0.8f, w * 0.1f, h * 0.5f)
		}
		drawPath(eyePath, color = tint, style = stroke)
		if (visible) {
			drawCircle(color = tint, radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.5f), style = Fill)
		} else {
			drawLine(
				color = tint,
				start = Offset(w * 0.2f, h * 0.2f),
				end = Offset(w * 0.8f, h * 0.8f),
				strokeWidth = 1.4f,
				cap = StrokeCap.Round,
			)
		}
	}
}

/** Vector Play Icon */
@Composable
fun IconPlay(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val path = Path().apply {
			moveTo(w * 0.25f, h * 0.15f)
			lineTo(w * 0.85f, h * 0.5f)
			lineTo(w * 0.25f, h * 0.85f)
			close()
		}
		drawPath(path, color = tint, style = Fill)
	}
}

/** Vector Pause Icon */
@Composable
fun IconPause(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val barW = w * 0.22f
		drawRect(color = tint, topLeft = Offset(w * 0.22f, h * 0.18f), size = Size(barW, h * 0.64f))
		drawRect(color = tint, topLeft = Offset(w * 0.56f, h * 0.18f), size = Size(barW, h * 0.64f))
	}
}

/** Vector Reset / Revert Arrow Icon */
@Composable
fun IconReset(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val arcPath = Path().apply {
			arcTo(
				rect = androidx.compose.ui.geometry.Rect(w * 0.15f, h * 0.15f, w * 0.85f, h * 0.85f),
				startAngleDegrees = 45f,
				sweepAngleDegrees = 270f,
				forceMoveTo = false,
			)
		}
		drawPath(arcPath, color = tint, style = stroke)
		val arrowPath = Path().apply {
			moveTo(w * 0.55f, h * 0.1f)
			lineTo(w * 0.85f, h * 0.28f)
			lineTo(w * 0.62f, h * 0.45f)
		}
		drawPath(arrowPath, color = tint, style = stroke)
	}
}

/** Vector Lock / Unlock Icon */
@Composable
fun IconLock(
	locked: Boolean,
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)

		val bodyTop = h * 0.45f
		val bodyH = h * 0.45f
		val bodyW = w * 0.72f
		val bodyL = (w - bodyW) * 0.5f

		if (locked) {
			// Closed Shackle
			val shacklePath = Path().apply {
				moveTo(w * 0.30f, bodyTop)
				lineTo(w * 0.30f, h * 0.28f)
				arcTo(
					rect = androidx.compose.ui.geometry.Rect(w * 0.30f, h * 0.12f, w * 0.70f, h * 0.44f),
					startAngleDegrees = 180f,
					sweepAngleDegrees = 180f,
					forceMoveTo = false,
				)
				lineTo(w * 0.70f, bodyTop)
			}
			drawPath(shacklePath, color = tint, style = stroke)
		} else {
			// Open Shackle
			val shacklePath = Path().apply {
				moveTo(w * 0.28f, bodyTop)
				lineTo(w * 0.28f, h * 0.22f)
				arcTo(
					rect = androidx.compose.ui.geometry.Rect(w * 0.28f, h * 0.06f, w * 0.68f, h * 0.38f),
					startAngleDegrees = 180f,
					sweepAngleDegrees = 180f,
					forceMoveTo = false,
				)
				lineTo(w * 0.68f, h * 0.26f)
			}
			drawPath(shacklePath, color = tint, style = stroke)
		}

		// Body
		drawRoundRect(
			color = tint,
			topLeft = Offset(bodyL, bodyTop),
			size = Size(bodyW, bodyH),
			cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
			style = if (locked) Fill else stroke,
		)

		if (locked) {
			drawCircle(
				color = Color(0xFF1E1F22),
				radius = 1.3f,
				center = Offset(w * 0.5f, bodyTop + bodyH * 0.45f),
				style = Fill,
			)
		}
	}
}

/** Vector Mouse Pointer / Tracking Icon */
@Composable
fun IconMouse(
	active: Boolean,
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val cursorPath = Path().apply {
			moveTo(w * 0.20f, h * 0.10f)
			lineTo(w * 0.20f, h * 0.88f)
			lineTo(w * 0.44f, h * 0.64f)
			lineTo(w * 0.68f, h * 0.88f)
			lineTo(w * 0.82f, h * 0.74f)
			lineTo(w * 0.56f, h * 0.52f)
			lineTo(w * 0.85f, h * 0.52f)
			close()
		}
		drawPath(cursorPath, color = tint, style = if (active) Fill else stroke)
	}
}

/** Vector Search Glass Icon */
@Composable
fun IconSearch(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round)
		drawCircle(color = tint, radius = w * 0.32f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
		drawLine(
			color = tint,
			start = Offset(w * 0.66f, h * 0.66f),
			end = Offset(w * 0.88f, h * 0.88f),
			strokeWidth = 1.4f,
			cap = StrokeCap.Round,
		)
	}
}

/**
 * Cubism-style parameter link chain.
 *
 * Each link is a tall stadium (capsule) — taller than wide, not a circle.
 * [linked]=true: two stadiums diagonally staggered and hooked through each other;
 * gaps sit on the actual crossing so the weave reads as a small cross (+).
 * [linked]=false: one open tall stadium (gap at bottom-left).
 */
@Composable
fun IconParameterLink(
	linked: Boolean,
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val strokeW = (minOf(w, h) * 0.14f).coerceIn(1.15.dp.toPx(), 1.85.dp.toPx())
		val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)

		fun stadiumPath(
			left: Float,
			top: Float,
			right: Float,
			bottom: Float,
			/** Open gap on bottom-left corner (degrees of bottom arc skipped near left). */
			gapBottomLeftDeg: Float = 0f,
			/** Open gap on top-right corner (degrees of top arc skipped near right). */
			gapTopRightDeg: Float = 0f,
		): Path {
			val r = (right - left) * 0.5f
			val topRect = Rect(left, top, right, top + 2f * r)
			val bottomRect = Rect(left, bottom - 2f * r, right, bottom)
			return Path().apply {
				when {
					gapBottomLeftDeg > 0f -> {
						// Start on left edge above the gap, go up → top → right → bottom (stop before left).
						moveTo(left, bottom - r - strokeW * 0.35f)
						lineTo(left, top + r)
						arcTo(topRect, 180f, 180f, false)
						lineTo(right, bottom - r)
						arcTo(bottomRect, 0f, 180f - gapBottomLeftDeg, false)
					}
					gapTopRightDeg > 0f -> {
						// Start on right edge below the gap, go down → bottom → left → top (stop before right).
						moveTo(right, top + r + strokeW * 0.35f)
						lineTo(right, bottom - r)
						arcTo(bottomRect, 0f, 180f, false)
						lineTo(left, top + r)
						arcTo(topRect, 180f, 180f - gapTopRightDeg, false)
					}
					else -> {
						moveTo(left, top + r)
						lineTo(left, bottom - r)
						arcTo(bottomRect, 180f, -180f, false)
						lineTo(right, top + r)
						arcTo(topRect, 0f, -180f, false)
						close()
					}
				}
			}
		}

		if (!linked) {
			// Single open tall stadium — gap at bottom-left (same language as linked weave).
			val linkW = (w * 0.58f).coerceAtLeast(4f)
			val linkH = (h * 0.78f).coerceAtLeast(linkW * 1.45f)
			val left = (w - linkW) * 0.5f
			val top = (h - linkH) * 0.5f
			drawPath(
				path = stadiumPath(
					left, top, left + linkW, top + linkH,
					gapBottomLeftDeg = 52f,
				),
				color = tint,
				style = stroke,
			)
		} else {
			// Diagonal stagger: upper-right / lower-left.
			// Crossing sits on upper bottom-left ↔ lower top-right → gaps match those corners.
			val linkW = (w * 0.55f).coerceAtLeast(4f)
			val linkH = (h * 0.58f).coerceAtLeast(linkW * 1.55f)
			val diag = (w * 0.12f).coerceAtLeast(1.1.dp.toPx())
			val centerX = w * 0.5f
			val top1 = h * 0.02f
			val bottom1 = top1 + linkH
			val bottom2 = h * 0.98f
			val top2 = bottom2 - linkH
			val gapDeg = 56f

			// Upper → shift right
			val left1 = centerX - linkW * 0.5f + diag
			val right1 = left1 + linkW
			// Lower → shift left
			val left2 = centerX - linkW * 0.5f - diag
			val right2 = left2 + linkW

			// Draw lower first, then upper (upper's right strand sits in front at top-right gap of lower).
			drawPath(
				path = stadiumPath(left2, top2, right2, bottom2, gapTopRightDeg = gapDeg),
				color = tint,
				style = stroke,
			)
			drawPath(
				path = stadiumPath(left1, top1, right1, bottom1, gapBottomLeftDeg = gapDeg),
				color = tint,
				style = stroke,
			)
		}
	}
}

/** Vector Folder Icon */
@Composable
fun IconFolder(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val path = Path().apply {
			moveTo(w * 0.1f, h * 0.25f)
			lineTo(w * 0.4f, h * 0.25f)
			lineTo(w * 0.5f, h * 0.38f)
			lineTo(w * 0.9f, h * 0.38f)
			lineTo(w * 0.9f, h * 0.8f)
			lineTo(w * 0.1f, h * 0.8f)
			close()
		}
		drawPath(path, color = tint, style = stroke)
	}
}

/** Vector Chevron / Triangle Icon */
@Composable
fun IconChevron(
	expanded: Boolean,
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val path = Path().apply {
			if (expanded) {
				moveTo(w * 0.25f, h * 0.35f)
				lineTo(w * 0.5f, h * 0.65f)
				lineTo(w * 0.75f, h * 0.35f)
			} else {
				moveTo(w * 0.35f, h * 0.25f)
				lineTo(w * 0.65f, h * 0.5f)
				lineTo(w * 0.35f, h * 0.75f)
			}
		}
		drawPath(path, color = tint, style = stroke)
	}
}

/** Vector Checkmark Icon */
@Composable
fun IconCheck(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = Color.White,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val path = Path().apply {
			moveTo(w * 0.2f, h * 0.5f)
			lineTo(w * 0.45f, h * 0.75f)
			lineTo(w * 0.8f, h * 0.25f)
		}
		drawPath(path, color = tint, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
	}
}

/** Vector Close / Cross Icon */
@Composable
fun IconClose(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round)
		drawLine(color = tint, start = Offset.Zero, end = Offset(size.width, size.height), strokeWidth = stroke.width, cap = stroke.cap)
		drawLine(color = tint, start = Offset(size.width, 0f), end = Offset(0f, size.height), strokeWidth = stroke.width, cap = stroke.cap)
	}
}

/** Vector Deform Path / Bezier Curve Icon */
@Composable
fun IconDeformPath(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val curvePath = Path().apply {
			moveTo(w * 0.15f, h * 0.78f)
			cubicTo(w * 0.18f, h * 0.22f, w * 0.82f, h * 0.78f, w * 0.85f, h * 0.22f)
		}
		drawPath(curvePath, color = tint, style = stroke)
		drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.15f, h * 0.78f), style = Fill)
		drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.85f, h * 0.22f), style = Fill)
		drawCircle(color = Color(0xFF4EC9B0), radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f), style = Fill)
	}
}

/** Vector Trash Can / Delete Icon */
@Composable
fun IconTrash(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		// Can lid / handle
		drawLine(color = tint, start = Offset(w * 0.38f, h * 0.12f), end = Offset(w * 0.62f, h * 0.12f), strokeWidth = 1.2f, cap = StrokeCap.Round)
		drawLine(color = tint, start = Offset(w * 0.20f, h * 0.24f), end = Offset(w * 0.80f, h * 0.24f), strokeWidth = 1.2f, cap = StrokeCap.Round)
		// Can body
		val bodyPath = Path().apply {
			moveTo(w * 0.28f, h * 0.24f)
			lineTo(w * 0.32f, h * 0.86f)
			quadraticTo(w * 0.33f, h * 0.92f, w * 0.40f, h * 0.92f)
			lineTo(w * 0.60f, h * 0.92f)
			quadraticTo(w * 0.67f, h * 0.92f, w * 0.68f, h * 0.86f)
			lineTo(w * 0.72f, h * 0.24f)
		}
		drawPath(bodyPath, color = tint, style = stroke)
		// Vertical slats inside bin
		drawLine(color = tint, start = Offset(w * 0.43f, h * 0.38f), end = Offset(w * 0.43f, h * 0.78f), strokeWidth = 1.0f, cap = StrokeCap.Round)
		drawLine(color = tint, start = Offset(w * 0.57f, h * 0.38f), end = Offset(w * 0.57f, h * 0.78f), strokeWidth = 1.0f, cap = StrokeCap.Round)
	}
}

/** Rotation deformer: pivot + direction arrow. */
@Composable
fun IconRotationDeformer(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round)
		val pivot = Offset(w * 0.28f, h * 0.72f)
		val tip = Offset(w * 0.82f, h * 0.18f)
		val dx = tip.x - pivot.x
		val dy = tip.y - pivot.y
		val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
		val ux = dx / len
		val uy = dy / len
		val head = minOf(w, h) * 0.28f
		val shaftEnd = Offset(tip.x - ux * head * 0.85f, tip.y - uy * head * 0.85f)
		drawCircle(tint, w * 0.14f, pivot, style = Fill)
		drawLine(tint, pivot, shaftEnd, stroke.width, cap = stroke.cap)
		val headPath = Path().apply {
			moveTo(tip.x, tip.y)
			lineTo(tip.x - ux * head + -uy * head * 0.45f, tip.y - uy * head + ux * head * 0.45f)
			lineTo(tip.x - ux * head - -uy * head * 0.45f, tip.y - uy * head - ux * head * 0.45f)
			close()
		}
		drawPath(headPath, tint)
	}
}

/** Six-dot grip used as a drag handle (e.g. parameter-panel reorder). */
@Composable
fun IconDragHandle(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val r = 1.05f
		val xs = floatArrayOf(w * 0.32f, w * 0.68f)
		val ys = floatArrayOf(h * 0.22f, h * 0.5f, h * 0.78f)
		for (x in xs) {
			for (y in ys) {
				drawCircle(tint, r, Offset(x, y))
			}
		}
	}
}

/** Move / float an item to the armature root. */
@Composable
fun IconMoveToRoot(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		drawLine(tint, Offset(w * 0.18f, h * 0.18f), Offset(w * 0.82f, h * 0.18f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f, h * 0.88f), stroke.width, cap = stroke.cap)
		val arrow = Path().apply {
			moveTo(w * 0.5f, h * 0.28f)
			lineTo(w * 0.32f, h * 0.48f)
			moveTo(w * 0.5f, h * 0.28f)
			lineTo(w * 0.68f, h * 0.48f)
		}
		drawPath(arrow, tint, style = stroke)
	}
}

/** Expand a tree branch. */
@Composable
fun IconExpandBranch(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.22f, h * 0.28f), Offset(w * 0.22f, h * 0.78f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.72f, h * 0.5f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(w * 0.22f, h * 0.78f), Offset(w * 0.72f, h * 0.78f), stroke.width, cap = stroke.cap)
		drawCircle(tint, w * 0.1f, Offset(w * 0.72f, h * 0.5f), style = Fill)
		drawCircle(tint, w * 0.1f, Offset(w * 0.72f, h * 0.78f), style = Fill)
	}
}

/** Collapse a tree branch. */
@Composable
fun IconCollapseBranch(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.22f, h * 0.28f), Offset(w * 0.22f, h * 0.72f), stroke.width, cap = stroke.cap)
		drawLine(tint, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.55f, h * 0.5f), stroke.width, cap = stroke.cap)
		drawRect(tint, Offset(w * 0.55f, h * 0.38f), Size(w * 0.28f, h * 0.24f), style = Stroke(width = 1.1f))
	}
}

/** Expand all items in tree hierarchy (chevrons pointing outward). */
@Composable
fun IconExpandAll(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		// Top chevron pointing UP
		val pathUp = Path().apply {
			moveTo(w * 0.22f, h * 0.38f)
			lineTo(w * 0.5f, h * 0.16f)
			lineTo(w * 0.78f, h * 0.38f)
		}
		// Bottom chevron pointing DOWN
		val pathDown = Path().apply {
			moveTo(w * 0.22f, h * 0.62f)
			lineTo(w * 0.5f, h * 0.84f)
			lineTo(w * 0.78f, h * 0.62f)
		}
		drawPath(pathUp, tint, style = stroke)
		drawPath(pathDown, tint, style = stroke)
	}
}

/** Collapse all items in tree hierarchy (chevrons pointing inward). */
@Composable
fun IconCollapseAll(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		// Top chevron pointing DOWN
		val pathDown = Path().apply {
			moveTo(w * 0.22f, h * 0.16f)
			lineTo(w * 0.5f, h * 0.38f)
			lineTo(w * 0.78f, h * 0.16f)
		}
		// Bottom chevron pointing UP
		val pathUp = Path().apply {
			moveTo(w * 0.22f, h * 0.84f)
			lineTo(w * 0.5f, h * 0.62f)
			lineTo(w * 0.78f, h * 0.84f)
		}
		drawPath(pathDown, tint, style = stroke)
		drawPath(pathUp, tint, style = stroke)
	}
}

/** Conversion / lattice division grid. */
@Composable
fun IconGridDivision(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.1f, cap = StrokeCap.Round)
		drawRect(tint, Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.7f), style = stroke)
		drawLine(tint, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), stroke.width)
		drawLine(tint, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.5f, h * 0.85f), stroke.width)
	}
}

/** Bezier edit division (curved lattice). */
@Composable
fun IconBezierDivision(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.15f, cap = StrokeCap.Round)
		val path = Path().apply {
			moveTo(w * 0.15f, h * 0.78f)
			cubicTo(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f, w * 0.85f, h * 0.22f)
		}
		drawPath(path, tint, style = stroke)
		drawCircle(tint, w * 0.1f, Offset(w * 0.15f, h * 0.78f), style = Fill)
		drawCircle(tint, w * 0.1f, Offset(w * 0.85f, h * 0.22f), style = Fill)
		drawCircle(tint, w * 0.08f, Offset(w * 0.5f, h * 0.5f), style = Fill)
	}
}

/** Draw-order / stacking icon. */
@Composable
fun IconDrawOrder(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.1f, cap = StrokeCap.Round)
		drawRect(tint, Offset(w * 0.18f, h * 0.42f), Size(w * 0.64f, h * 0.42f), style = stroke)
		drawRect(tint, Offset(w * 0.28f, h * 0.28f), Size(w * 0.64f, h * 0.42f), style = stroke)
		drawRect(tint, Offset(w * 0.38f, h * 0.14f), Size(w * 0.48f, h * 0.36f), style = stroke)
	}
}

/**
 * Header row for context menu showing item icon, name, and element badge.
 */
@Composable
fun CompactMenuHeader(
	name: String,
	badge: String? = null,
	icon: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(28.dp)
			.background(colors.windowBackground.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(7.dp),
			modifier = Modifier.weight(1f, fill = false),
		) {
			if (icon != null) {
				Box(
					modifier = Modifier.size(16.dp),
					contentAlignment = Alignment.Center,
				) {
					icon()
				}
			}
			Text(
				text = name,
				style = typography.title.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (!badge.isNullOrBlank()) {
			Box(
				modifier = Modifier
					.background(colors.panelBackground, RoundedCornerShape(3.dp))
					.border(BorderStroke(0.8.dp, colors.border), RoundedCornerShape(3.dp))
					.padding(horizontal = 4.dp, vertical = 1.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = badge,
					style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
					color = colors.accent,
				)
			}
		}
	}
	Spacer(Modifier.height(3.dp))
}

/**
 * Compact context-menu row: fixed icon column + single-line label, tight desktop ergonomics.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CompactMenuItem(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	danger: Boolean = false,
	active: Boolean = false,
	trailingText: String? = null,
	trailingBadge: (@Composable () -> Unit)? = null,
	icon: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var isHovered by remember { mutableStateOf(false) }

	val labelColor = when {
		!enabled -> colors.textDisabled
		danger -> if (isHovered) colors.error else colors.error.copy(alpha = 0.9f)
		active -> colors.accent
		isHovered -> colors.selectionText
		else -> colors.textPrimary
	}

	val itemBg = when {
		!enabled -> Color.Transparent
		isHovered && danger -> colors.error.copy(alpha = 0.14f)
		isHovered -> colors.selection
		active -> colors.accent.copy(alpha = 0.12f)
		else -> Color.Transparent
	}

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(26.dp)
			.clip(RoundedCornerShape(4.dp))
			.background(itemBg)
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.onPointerEvent(PointerEventType.Enter) { if (enabled) isHovered = true }
			.onPointerEvent(PointerEventType.Exit) { isHovered = false }
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.weight(1f, fill = false),
		) {
			Box(
				modifier = Modifier.size(16.dp),
				contentAlignment = Alignment.Center,
			) {
				icon?.invoke()
			}
			Text(
				text = text,
				style = typography.body.copy(
					fontSize = 11.5.sp,
					fontWeight = if (active || (isHovered && !danger)) FontWeight.Medium else FontWeight.Normal,
				),
				color = labelColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (trailingBadge != null) {
			trailingBadge()
		} else if (!trailingText.isNullOrBlank()) {
			Text(
				text = trailingText,
				style = typography.monoSmall.copy(fontSize = 9.5.sp),
				color = if (isHovered) colors.selectionText.copy(alpha = 0.85f) else colors.textMuted,
			)
		}
	}
}

@Composable
fun CompactMenuSection(title: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(
		text = title.uppercase(),
		style = typography.caption.copy(
			fontSize = 9.sp,
			fontWeight = FontWeight.Bold,
			letterSpacing = 0.5.sp,
		),
		color = colors.textMuted.copy(alpha = 0.8f),
		modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 2.dp),
	)
}

@Composable
fun CompactMenuDivider() {
	val colors = LocalToolColors.current
	androidx.compose.material.Divider(
		color = colors.divider.copy(alpha = 0.6f),
		thickness = 0.5.dp,
		modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
	)
}

/**
 * Desktop context menu designed for layer tree / hierarchy tree.
 * Positioned smartly at the mouse click location with automatic window edge flipping and clamping.
 * Features ultra-smooth snappy entrance and exit transitions, elevated panel styling,
 * and high-density desktop typography.
 */
@Composable
fun TreeContextMenu(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	clickOffset: Offset = Offset.Zero,
	modifier: Modifier = Modifier,
	minWidth: Dp = 200.dp,
	maxWidth: Dp = 270.dp,
	/** Match overlay toolbars: translucent acrylic instead of an opaque elevated panel. */
	frosted: Boolean = false,
	content: @Composable ColumnScope.() -> Unit,
) {
	val expandedStates = remember { MutableTransitionState(false) }
	expandedStates.targetState = expanded

	if (expandedStates.currentState || expandedStates.targetState) {
		val colors = LocalToolColors.current
		val typography = LocalToolTypography.current
		val density = LocalDensity.current

		val positionProvider = remember(clickOffset, density) {
			object : PopupPositionProvider {
				override fun calculatePosition(
					anchorBounds: IntRect,
					windowSize: IntSize,
					layoutDirection: LayoutDirection,
					popupContentSize: IntSize,
				): IntOffset {
					val marginPx = with(density) { 8.dp.roundToPx() }
					val cursorPaddingPx = with(density) { 2.dp.roundToPx() }

					val mouseX = if (clickOffset != Offset.Zero) {
						anchorBounds.left + clickOffset.x.roundToInt()
					} else {
						anchorBounds.left + with(density) { 24.dp.roundToPx() }
					}

					val mouseY = if (clickOffset != Offset.Zero) {
						anchorBounds.top + clickOffset.y.roundToInt()
					} else {
						anchorBounds.bottom
					}

					// Horizontal placement: right of cursor, or flip to left if overflows window
					var x = mouseX + cursorPaddingPx
					if (x + popupContentSize.width > windowSize.width - marginPx) {
						x = mouseX - popupContentSize.width - cursorPaddingPx
					}
					x = x.coerceIn(marginPx, max(marginPx, windowSize.width - popupContentSize.width - marginPx))

					// Vertical placement: below cursor, or flip upward if overflows window
					var y = mouseY + cursorPaddingPx
					if (y + popupContentSize.height > windowSize.height - marginPx) {
						val upY = mouseY - popupContentSize.height - cursorPaddingPx
						y = if (upY >= marginPx) upY else (windowSize.height - popupContentSize.height - marginPx)
					}
					y = y.coerceIn(marginPx, max(marginPx, windowSize.height - popupContentSize.height - marginPx))

					return IntOffset(x, y)
				}
			}
		}

		Popup(
			popupPositionProvider = positionProvider,
			onDismissRequest = onDismissRequest,
			properties = PopupProperties(focusable = true),
		) {
			androidx.compose.runtime.CompositionLocalProvider(
				LocalDensity provides density,
				LocalToolColors provides colors,
				LocalToolTypography provides typography,
			) {
				val transition = updateTransition(expandedStates, "TreeContextMenuTransition")
				val alpha by transition.animateFloat(
					transitionSpec = {
						if (false isTransitioningTo true) tween(durationMillis = 110, easing = LinearOutSlowInEasing)
						else tween(durationMillis = 75, easing = FastOutLinearInEasing)
					},
					label = "alpha",
				) { if (it) 1f else 0f }

				val scale by transition.animateFloat(
					transitionSpec = {
						if (false isTransitioningTo true) tween(durationMillis = 130, easing = FastOutSlowInEasing)
						else tween(durationMillis = 75, easing = FastOutLinearInEasing)
					},
					label = "scale",
				) { if (it) 1f else 0.96f }

				val translateY by transition.animateFloat(
					transitionSpec = {
						if (false isTransitioningTo true) tween(durationMillis = 130, easing = FastOutSlowInEasing)
						else tween(durationMillis = 75, easing = FastOutLinearInEasing)
					},
					label = "translateY",
				) { if (it) 0f else -4f }

				val shape = RoundedCornerShape(6.dp)
				val shellModifier = modifier
					.graphicsLayer {
						this.alpha = alpha
						this.scaleX = scale
						this.scaleY = scale
						this.translationY = translateY * density.density
						this.transformOrigin = TransformOrigin(0f, 0f)
					}
					.widthIn(min = minWidth, max = maxWidth)
				val contentPad = if (frosted) 3.dp else 4.dp
				if (frosted) {
					Box(
						modifier = shellModifier.frostedGlass(
							shape = shape,
							isHovered = true,
							elevation = 12.dp,
							baseColor = colors.panelElevated,
							alpha = 0.88f,
						),
					) {
						Column(modifier = Modifier.padding(all = contentPad)) {
							content()
						}
					}
				} else {
					Surface(
						color = colors.panelElevated,
						shape = shape,
						border = BorderStroke(1.dp, colors.borderHover.copy(alpha = 0.5f)),
						elevation = 10.dp,
						modifier = shellModifier,
					) {
						Column(modifier = Modifier.padding(all = contentPad)) {
							content()
						}
					}
				}
			}
		}
	}
}

/** Practical Compact Tool Button */
@Composable
fun CompactButton(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	isPrimary: Boolean = false,
	danger: Boolean = false,
	leadingIcon: (@Composable () -> Unit)? = null,
	height: Dp = 26.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	val bgColor = when {
		!enabled -> colors.controlBackground.copy(alpha = 0.4f)
		danger && (isHovered || isPressed) -> colors.error.copy(alpha = 0.22f)
		danger -> colors.error.copy(alpha = 0.12f)
		isPrimary -> if (isHovered || isPressed) colors.accentHover else colors.accent
		isPressed -> colors.controlActive
		isHovered -> colors.controlHover
		else -> colors.controlBackground
	}

	val borderColor = when {
		!enabled -> colors.border.copy(alpha = 0.3f)
		danger -> colors.error.copy(alpha = if (isHovered) 0.75f else 0.5f)
		isPrimary -> colors.accent
		isHovered -> colors.borderHover
		else -> colors.border
	}

	val textColor = when {
		!enabled -> colors.textDisabled
		danger -> colors.error
		isPrimary -> colors.accentText
		else -> colors.textPrimary
	}

	Box(
		modifier = modifier
			.height(height)
			.background(bgColor, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, borderColor), RoundedCornerShape(2.dp))
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.padding(horizontal = 6.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			if (leadingIcon != null) {
				leadingIcon()
				Spacer(modifier.width(5.dp))
			}
			Text(
				text = text,
				style = typography.body.copy(
					fontSize = 11.sp,
					fontWeight = if (isPrimary || danger) FontWeight.Medium else FontWeight.Normal,
				),
				color = textColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				textAlign = TextAlign.Center,
			)
		}
	}
}

/** Practical Compact Icon Button */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactIconButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	tooltip: String? = null,
	size: Dp = 24.dp,
	content: @Composable () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	val bgColor = when {
		!enabled -> Color.Transparent
		isPressed -> colors.controlActive
		isHovered -> colors.controlHover
		else -> colors.controlBackground
	}

	val buttonBox = @Composable {
		Box(
			modifier = (if (tooltip.isNullOrBlank()) modifier else Modifier)
				.size(size)
				.background(bgColor, RoundedCornerShape(2.dp))
				.border(BorderStroke(1.dp, if (isHovered && enabled) colors.borderHover else colors.border), RoundedCornerShape(2.dp))
				.hoverable(interactionSource)
				.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
				.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default),
			contentAlignment = Alignment.Center,
		) {
			content()
		}
	}

	if (!tooltip.isNullOrBlank()) {
		TooltipArea(
			tooltip = {
				Surface(
					color = colors.panelElevated,
					shape = RoundedCornerShape(3.dp),
					border = BorderStroke(1.dp, colors.border),
					elevation = 4.dp,
				) {
					Text(
						text = tooltip,
						style = typography.caption.copy(fontSize = 10.sp),
						color = colors.textPrimary,
						modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
					)
				}
			},
			modifier = modifier,
			delayMillis = 400,
		) {
			buttonBox()
		}
	} else {
		buttonBox()
	}
}

/** Practical Compact Text Field */
@Composable
fun CompactTextField(
	value: String,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	placeholder: String = "",
	enabled: Boolean = true,
	isMono: Boolean = false,
	onCommit: (() -> Unit)? = null,
	leadingIcon: (@Composable () -> Unit)? = null,
	trailingIcon: (@Composable () -> Unit)? = null,
	height: Dp = 24.dp,
	/** Brackets one editing session; see the note on [CompactNumberSpinner]'s identically named pair. */
	onEditStart: () -> Unit = {},
	onEditEnd: () -> Unit = {},
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	var editing by remember { mutableStateOf(false) }
	LaunchedEffect(value, editing) {
		if (!editing) return@LaunchedEffect
		delay(EDIT_SETTLE_MILLIS)
		editing = false
		onEditEnd()
	}
	DisposableEffect(Unit) { onDispose { if (editing) onEditEnd() } }

	val textStyle = if (isMono) typography.mono else typography.body

	BasicTextField(
		value = value,
		onValueChange = { input ->
			if (!editing) { editing = true; onEditStart() }
			onValueChange(input)
		},
		modifier = modifier
			.height(height)
			.background(colors.inputBackground, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, if (isHovered && enabled) colors.borderHover else colors.border), RoundedCornerShape(2.dp))
			.padding(horizontal = 6.dp)
			.onFocusChanged { focus ->
				if (!focus.isFocused && editing) { editing = false; onEditEnd() }
			},
		enabled = enabled,
		textStyle = textStyle.copy(color = if (enabled) colors.textPrimary else colors.textDisabled, fontSize = 11.5.sp),
		cursorBrush = SolidColor(colors.accent),
		singleLine = true,
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
		// Enter is a confirm: it ends the session, which is what records the edit.
		keyboardActions = KeyboardActions(onDone = {
			if (editing) { editing = false; onEditEnd() }
			onCommit?.invoke()
		}),
		interactionSource = interactionSource,
		decorationBox = { innerTextField ->
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.fillMaxSize(),
			) {
				if (leadingIcon != null) {
					leadingIcon()
					Spacer(Modifier.width(4.dp))
				}
				Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
					if (value.isEmpty() && placeholder.isNotEmpty()) {
						Text(
							text = placeholder,
							style = textStyle.copy(fontSize = 11.5.sp),
							color = colors.textMuted,
							maxLines = 1,
						)
					}
					innerTextField()
				}
				if (trailingIcon != null) {
					Spacer(Modifier.width(4.dp))
					trailingIcon()
				}
			}
		},
	)
}

/** Practical Compact Number Stepper */
@Composable
fun CompactNumberSpinner(
	value: Double,
	onValueChange: (Double) -> Unit,
	modifier: Modifier = Modifier,
	min: Double = 0.0,
	max: Double = 100000.0,
	step: Double = 1.0,
	decimals: Int = 0,
	unit: String = "",
	enabled: Boolean = true,
	height: Dp = 24.dp,
	/**
	 * Brackets one editing session. Keystrokes inside a session collapse into a single history commit
	 * instead of one per keystroke; the caller pairs the two with a token of its own.
	 */
	onEditStart: () -> Unit = {},
	onEditEnd: () -> Unit = {},
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val formatted = if (decimals == 0) value.toLong().toString() else "%.${decimals}f".format(value)

	var textState by remember(value) { mutableStateOf(formatted) }
	var editing by remember { mutableStateOf(false) }
	// The session has to end even when the user never leaves the field, because ending it is what writes
	// the history node. A pause this long is the end of the edit as far as history is concerned; typing
	// restarts it, so a pause mid-number can split one edit into two nodes. That is the deliberate trade:
	// the alternative is leaving an abandoned session open forever.
	LaunchedEffect(textState, editing) {
		if (!editing) return@LaunchedEffect
		delay(EDIT_SETTLE_MILLIS)
		editing = false
		onEditEnd()
	}
	// Leaving the composition mid-edit — switching tabs, closing a panel — ends the session too.
	DisposableEffect(Unit) { onDispose { if (editing) onEditEnd() } }

	Row(
		modifier = modifier
			.height(height)
			.background(colors.inputBackground, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(2.dp)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		BasicTextField(
			value = textState,
			onValueChange = { input ->
				textState = input
				input.toDoubleOrNull()?.let { num ->
					onValueChange(num.coerceIn(min, max))
				}
			},
			modifier = Modifier.weight(1f).padding(horizontal = 4.dp).onFocusChanged { focus ->
				if (focus.isFocused && !editing) { editing = true; onEditStart() }
				else if (!focus.isFocused && editing) { editing = false; onEditEnd() }
			},
			textStyle = typography.mono.copy(
				color = if (enabled) colors.textPrimary else colors.textDisabled,
				fontSize = 11.sp,
				textAlign = TextAlign.Right,
			),
			cursorBrush = SolidColor(colors.accent),
			singleLine = true,
			enabled = enabled,
		)
		if (unit.isNotEmpty()) {
			Text(
				text = unit,
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
				modifier = Modifier.padding(end = 4.dp),
			)
		}
		// Compact step buttons
		Column(
			modifier = Modifier
				.fillMaxHeight()
				.width(14.dp)
				.border(BorderStroke(1.dp, colors.border)),
		) {
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.background(colors.controlBackground)
					.clickable(enabled = enabled) {
						// A click is a whole session on its own: it never takes focus, so nothing
						// would ever blur to end it, and the change has to be recorded here.
						val next = (value + step).coerceIn(min, max)
						onValueChange(next)
						onEditEnd()
					},
				contentAlignment = Alignment.Center,
			) {
				IconChevron(expanded = false, modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.background(colors.controlBackground)
					.clickable(enabled = enabled) {
						val next = (value - step).coerceIn(min, max)
						onValueChange(next)
						onEditEnd()
					},
				contentAlignment = Alignment.Center,
			) {
				IconChevron(expanded = true, modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
		}
	}
}

/** Shape of a parameter key mark / thumb: circle = keyform grid, square = blend-shape. */
enum class SliderKeyShape { Circle, Square }

/** One key point drawn on a parameter slider track. */
data class SliderKeyMark(val value: Float, val shape: SliderKeyShape = SliderKeyShape.Circle)


/** Simple compact slider for settings dialogs (parameter panel uses its own Cubism track). */
@Composable
fun CompactSlider(
	value: Float,
	onValueChange: (Float) -> Unit,
	onValueChangeStarted: () -> Unit = {},
	onValueChangeFinished: () -> Unit = {},
	modifier: Modifier = Modifier,
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	steps: Int = 0,
	enabled: Boolean = true,
	height: Dp = 16.dp,
	keyMarks: List<SliderKeyMark> = emptyList(),
	thumbShape: SliderKeyShape = SliderKeyShape.Circle,
) {
	val changeValue by rememberUpdatedState(onValueChange)
	val startChange by rememberUpdatedState(onValueChangeStarted)
	val finishChange by rememberUpdatedState(onValueChangeFinished)
	val colors = LocalToolColors.current
	val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
	val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	Canvas(
		modifier = modifier
			.height(height)
			.hoverable(interactionSource)
			.pointerHoverIcon(
				if (enabled) PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR))
				else PointerIcon.Default,
			)
			.pointerInput(valueRange, enabled) {
				if (!enabled) return@pointerInput
				val inset = 5.dp.toPx()
				awaitEachGesture {
					val down = awaitFirstDown()
					startChange()
					try {
						fun at(x: Float): Float {
							val usable = (size.width - 2f * inset).coerceAtLeast(1f)
							return valueRange.start + ((x - inset) / usable).coerceIn(0f, 1f) * span
						}
						changeValue(at(down.position.x))
						down.consume()
						do {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull { it.id == down.id } ?: break
							if (change.pressed) changeValue(at(change.position.x))
							change.consume()
						} while (event.changes.any { it.pressed })
					} finally {
						finishChange()
					}
				}
			},
	) {
		val trackH = 3.dp.toPx()
		val thumbR = 5.dp.toPx()
		val cy = size.height / 2f
		val usable = (size.width - 2f * thumbR).coerceAtLeast(0f)
		val thumbX = thumbR + fraction * usable
		drawRoundRect(
			colors.inputBackground,
			Offset(0f, cy - trackH / 2f),
			Size(size.width, trackH),
			CornerRadius(trackH / 2f),
		)
		drawRoundRect(
			if (enabled) (if (isHovered || isPressed) colors.accentHover else colors.accent) else colors.textDisabled,
			Offset(0f, cy - trackH / 2f),
			Size(thumbX.coerceAtLeast(trackH), trackH),
			CornerRadius(trackH / 2f),
		)
		for (mark in keyMarks) {
			val mx = thumbR + ((mark.value - valueRange.start) / span).coerceIn(0f, 1f) * usable
			drawCircle(colors.textMuted, 2.5.dp.toPx(), Offset(mx, cy))
		}
		val thumbColor = if (enabled) Color(0xFFE0E6ED) else colors.textDisabled
		when (thumbShape) {
			SliderKeyShape.Circle -> {
				drawCircle(thumbColor, thumbR, Offset(thumbX, cy))
				drawCircle(colors.accent, thumbR, Offset(thumbX, cy), style = Stroke(1.2.dp.toPx()))
			}
			SliderKeyShape.Square -> {
				drawRoundRect(
					thumbColor,
					Offset(thumbX - thumbR, cy - thumbR),
					Size(thumbR * 2f, thumbR * 2f),
					CornerRadius(thumbR * 0.35f),
				)
			}
		}
	}
}

/** Compact Checkbox */
@Composable
fun CompactCheckbox(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	label: String = "",
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()

	Row(
		modifier = modifier
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) {
				onCheckedChange(!checked)
			}
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.padding(vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.size(14.dp)
				.background(
					if (checked) colors.accent else colors.inputBackground,
					RoundedCornerShape(2.dp),
				)
				.border(
					BorderStroke(1.dp, if (checked) colors.accent else if (isHovered && enabled) colors.borderHover else colors.border),
					RoundedCornerShape(2.dp),
				),
			contentAlignment = Alignment.Center,
		) {
			if (checked) {
				IconCheck(modifier = Modifier.size(10.dp), tint = Color.White)
			}
		}
		if (label.isNotEmpty()) {
			Spacer(Modifier.width(6.dp))
			Text(
				text = label,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = if (enabled) (if (isHovered) colors.textPrimary else colors.textPrimary) else colors.textDisabled,
			)
		}
	}
}

/** Compact Toggle Chip / Button matching desktop tool aesthetic */
@Composable
fun CompactToggleChip(
	text: String,
	selected: Boolean,
	onToggle: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	leadingIcon: (@Composable () -> Unit)? = null,
	showCheckWhenSelected: Boolean = true,
	height: Dp = 22.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	val bgColor = when {
		!enabled -> colors.controlBackground.copy(alpha = 0.35f)
		selected -> if (isHovered || isPressed) colors.accent.copy(alpha = 0.28f) else colors.accent.copy(alpha = 0.16f)
		isPressed -> colors.controlActive
		isHovered -> colors.controlHover
		else -> colors.controlBackground.copy(alpha = 0.65f)
	}

	val borderColor = when {
		!enabled -> colors.border.copy(alpha = 0.25f)
		selected -> colors.accent
		isHovered -> colors.borderHover
		else -> colors.border
	}

	val contentColor = when {
		!enabled -> colors.textDisabled
		selected -> colors.accent
		isHovered -> colors.textPrimary
		else -> colors.textMuted
	}

	Box(
		modifier = modifier
			.height(height)
			.background(bgColor, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, borderColor), RoundedCornerShape(2.dp))
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onToggle() }
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.padding(horizontal = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			if (leadingIcon != null) {
				leadingIcon()
				Spacer(Modifier.width(3.dp))
			} else if (selected && showCheckWhenSelected) {
				IconCheck(modifier = Modifier.size(9.dp), tint = contentColor)
				Spacer(Modifier.width(3.dp))
			}
			Text(
				text = text,
				style = typography.body.copy(
					fontSize = 10.5.sp,
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = contentColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** Compact Radio Button matching CompactCheckbox design */
@Composable
fun CompactRadioButton(
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	label: String = "",
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }

	Row(
		modifier = modifier
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null, onClick = onClick)
			.padding(vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.size(14.dp)
				.clip(CircleShape)
				.background(colors.inputBackground, CircleShape)
				.border(
					BorderStroke(1.dp, if (selected) colors.accent else colors.border),
					CircleShape,
				),
			contentAlignment = Alignment.Center,
		) {
			if (selected) {
				Box(
					modifier = Modifier
						.size(6.dp)
						.clip(CircleShape)
						.background(colors.accent, CircleShape),
				)
			}
		}
		if (label.isNotEmpty()) {
			Spacer(Modifier.width(6.dp))
			Text(
				text = label,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = if (enabled) colors.textPrimary else colors.textDisabled,
			)
		}
	}
}

/** Compact Dropdown Selector */
@Composable
fun <T> CompactDropdown(
	items: List<T>,
	selectedItem: T,
	onItemSelected: (T) -> Unit,
	modifier: Modifier = Modifier,
	itemLabel: (T) -> String = { it.toString() },
	itemEnabled: (T) -> Boolean = { true },
	enabled: Boolean = true,
	height: Dp = 24.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var expanded by remember { mutableStateOf(false) }

	Box(modifier = modifier) {
		Row(
			modifier = Modifier
				.height(height)
				.fillMaxWidth()
				.background(colors.inputBackground, RoundedCornerShape(2.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(2.dp))
				.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
				.clickable(enabled = enabled) { expanded = true }
				.padding(horizontal = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = itemLabel(selectedItem),
				style = typography.body.copy(fontSize = 11.5.sp),
				color = if (enabled) colors.textPrimary else colors.textDisabled,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			IconChevron(expanded = expanded, modifier = Modifier.size(10.dp), tint = colors.textMuted)
		}

		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			modifier = Modifier
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.border)),
		) {
			items.forEach { item ->
				val isSelected = item == selectedItem
				val isItemEnabled = itemEnabled(item)
				DropdownMenuItem(
					enabled = isItemEnabled,
					onClick = {
						if (isItemEnabled) {
							onItemSelected(item)
							expanded = false
						}
					},
					modifier = Modifier
						.height(26.dp)
						.background(if (isSelected) colors.selection else Color.Transparent)
						.pointerHoverIcon(if (isItemEnabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default),
				) {
					Text(
						text = itemLabel(item),
						style = typography.body.copy(
							fontSize = 11.5.sp,
							color = when {
								!isItemEnabled -> colors.textDisabled
								isSelected -> colors.selectionText
								else -> colors.textPrimary
							},
						),
					)
				}
			}
		}
	}
}

/** Compact Tab Bar */
@Composable
fun CompactTabBar(
	tabs: List<String>,
	selectedIndex: Int,
	onTabSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	height: Dp = 26.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Row(
		modifier = modifier
			.height(height)
			.fillMaxWidth()
			.background(colors.windowBackground)
			.border(BorderStroke(1.dp, colors.divider))
			.horizontalScroll(rememberScrollState()),
		verticalAlignment = Alignment.CenterVertically,
	) {
		tabs.forEachIndexed { index, title ->
			val isSelected = index == selectedIndex
			val interactionSource = remember(index) { MutableInteractionSource() }
			val isHovered by interactionSource.collectIsHoveredAsState()

			val bg = when {
				isSelected -> colors.panelBackground
				isHovered -> colors.controlHover
				else -> Color.Transparent
			}

			Box(
				modifier = Modifier
					.fillMaxHeight()
					.background(bg)
					.drawBehind {
						if (isSelected) {
							drawRect(
								color = colors.accent,
								topLeft = Offset.Zero,
								size = Size(size.width, 2.dp.toPx()),
							)
						}
					}
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.clickable(interactionSource = interactionSource, indication = null) {
						onTabSelected(index)
					}
					.border(
						BorderStroke(
							1.dp,
							if (isSelected) colors.divider else Color.Transparent,
						),
					)
					.padding(horizontal = 10.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = title,
					style = typography.body.copy(
						fontSize = 11.5.sp,
						fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
					),
					color = if (isSelected) colors.textPrimary else colors.textMuted,
				)
			}
		}
	}
}

/** Compact Section Header */
@Composable
fun CompactSectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	trailing: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(24.dp)
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider))
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text = title,
			style = typography.header.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)
		trailing?.invoke()
	}
}


/** Quiet period that ends an abandoned field session; see CompactNumberSpinner. */
private const val EDIT_SETTLE_MILLIS = 800L
