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
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.animation.core.rememberTransition
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import java.awt.Cursor

/**
 * Click-to-dismiss for a full-window dialog scrim. Without a MaterialTheme the default indication
 * tints a hovered clickable 10% black, and the scrim is hovered whenever the pointer is in the
 * window, so the whole window brightened each time a dropdown popup took the pointer away.
 */
fun Modifier.scrimDismiss(enabled: Boolean = true, onDismiss: () -> Unit): Modifier =
	clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onDismiss)

/** An eye, open when [visible]; struck through when hidden. */
@Composable
fun IconEye(
	visible: Boolean,
	modifier: Modifier = Modifier.size(16.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	val eye = path {
		m(1.8f, 9f)
		c(4.2f, 4.2f, 13.8f, 4.2f, 16.2f, 9f)
		c(13.8f, 13.8f, 4.2f, 13.8f, 1.8f, 9f)
		z()
	}
	if (visible) {
		shape(eye)
		ring(9f, 9f, 2.6f)
		dot(9f, 9f, 1.1f)
	} else {
		// The slash cuts a gap through the eye so it reads at small sizes.
		behind(path { m(1.6f, 4.4f); l(13.6f, 16.4f); l(16.4f, 13.6f); l(4.4f, 1.6f); z() }) { outline(eye) }
		line(3f, 3f, 15f, 15f)
	}
}

/** Play: a rounded triangle. */
@Composable
fun IconPlay(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	val play = path { m(5.2f, 3.4f); l(14.8f, 9f); l(5.2f, 14.6f); z() }
	fill(play)
	outline(play)
}

/** Pause: two rounded bars. */
@Composable
fun IconPause(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	fillBox(4.4f, 3.4f, 3.4f, 11.2f, 1f)
	fillBox(10.2f, 3.4f, 3.4f, 11.2f, 1f)
}

/** Reset: a turn back round to the start. */
@Composable
fun IconReset(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	arcArrow(9f, 9.4f, 6.2f, 180f, -125f, head = 3.2f)
}

/** Curved back arrow for undo. */
@Composable
fun IconUndo(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) { undoArrow() }

/** Curved forward arrow for redo: [IconUndo] mirrored. */
@Composable
fun IconRedo(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) { mirrored { undoArrow() } }

/** Blender-style auto-keying: a record dot, red while it records. */
@Composable
fun IconAutoKey(
	modifier: Modifier = Modifier.size(12.dp),
	active: Boolean = false,
	tint: Color = LocalToolColors.current.textPrimary,
) {
	val record = LocalToolColors.current.error
	GridIcon(modifier, tint) {
		ring(9f, 9f, 6.8f)
		if (active) dot(9f, 9f, 4.2f, tone(record)) else dot(9f, 9f, 2.6f)
	}
}

/** A padlock, its shackle closed when [locked]. */
@Composable
fun IconLock(
	locked: Boolean,
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	// The keyhole is punched in the panel colour the icon sits on.
	val keyhole = LocalToolColors.current.panelBackground
	GridIcon(modifier, tint) {
		if (locked) {
			outline(path { m(5.8f, 8.2f); l(5.8f, 6f); c(5.8f, 1.8f, 12.2f, 1.8f, 12.2f, 6f); l(12.2f, 8.2f) })
			fillBox(3.4f, 8f, 11.2f, 8f, 1.6f)
			dot(9f, 11.4f, 1.3f, keyhole)
			line(9f, 11.6f, 9f, 13.4f, tint = keyhole)
		} else {
			outline(path { m(5.8f, 8.2f); l(5.8f, 5.4f); c(5.8f, 1.4f, 11.6f, 1f, 12.2f, 4.6f) })
			panel(3.4f, 8f, 11.2f, 8f, 1.6f)
		}
	}
}

/** The pointer, filled while the model follows it. */
@Composable
fun IconMouse(
	active: Boolean,
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	val arrow = path {
		m(4.5f, 2.5f); l(4.5f, 14.5f); l(7.6f, 11.8f); l(9.5f, 16f); l(11.6f, 15.1f); l(9.7f, 10.9f); l(13.8f, 10.8f); z()
	}
	shape(arrow, body = if (active) color else soft)
}

/** Physics: a swinging pendulum, its bob filled while physics runs. */
@Composable
fun IconPhysics(
	active: Boolean,
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	// The swing it came from.
	arc(9f, 2.8f, 11.6f, 98f, 30f, width = ICON_FINE, tint = color.copy(alpha = color.alpha * 0.55f))
	line(4.6f, 2.8f, 13.4f, 2.8f)
	line(9f, 2.8f, 11.8f, 11.2f)
	dot(12.6f, 13.4f, 2.6f, if (active) color else soft)
	ring(12.6f, 13.4f, 2.6f)
}

/** A magnifying glass. */
@Composable
fun IconSearch(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) {
	ring(7.8f, 7.8f, 5.2f)
	line(11.7f, 11.7f, 15.6f, 15.6f, width = 1.8f)
}

/**
 * Globe marking the language choice. It carries no text, so someone who switched to a language they
 * cannot read still finds the way back.
 */
@Composable
fun IconLanguage(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	val r = 7f
	dot(9f, 9f, r, soft)
	// Meridians: an ellipse half as wide as the globe, and its axis; the equator and two parallels.
	scope.drawOval(color, p(9f - r * 0.45f, 9f - r), Size(r * 0.9f * s, 2 * r * s), style = stroke(ICON_FINE))
	line(9f, 9f - r, 9f, 9f + r, ICON_FINE)
	line(9f - r, 9f, 9f + r, 9f, ICON_FINE)
	line(9f - r * 0.866f, 9f - r * 0.5f, 9f + r * 0.866f, 9f - r * 0.5f, ICON_FINE)
	line(9f - r * 0.866f, 9f + r * 0.5f, 9f + r * 0.866f, 9f + r * 0.5f, ICON_FINE)
	ring(9f, 9f, r)
}

/** Parameter chain with rounded links and clear negative space at small sizes. */
@Composable
fun IconParameterLink(
	linked: Boolean,
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	// Fixed viewports keep the rings proportional even in a square icon slot.
	// Upper-right and lower-left rings cross at (4.5, 10) and (7.5, 14).
	// Alternate the interrupted strand at each crossing to show an interlocking weave.
	val upperLink = remember {
		Path().apply {
			moveTo(4.5f, 8.2f)
			lineTo(4.5f, 3.5f)
			cubicTo(4.5f, 2.4f, 5.4f, 1.5f, 6.5f, 1.5f)
			lineTo(8.5f, 1.5f)
			cubicTo(9.6f, 1.5f, 10.5f, 2.4f, 10.5f, 3.5f)
			lineTo(10.5f, 12f)
			cubicTo(10.5f, 13.1f, 9.6f, 14f, 8.5f, 14f)
			lineTo(6.5f, 14f)
			cubicTo(5.4f, 14f, 4.5f, 13.1f, 4.5f, 12f)
			lineTo(4.5f, 11.8f)
		}
	}
	val lowerLink = remember {
		Path().apply {
			moveTo(7.5f, 15.8f)
			lineTo(7.5f, 20.5f)
			cubicTo(7.5f, 21.6f, 6.6f, 22.5f, 5.5f, 22.5f)
			lineTo(3.5f, 22.5f)
			cubicTo(2.4f, 22.5f, 1.5f, 21.6f, 1.5f, 20.5f)
			lineTo(1.5f, 12f)
			cubicTo(1.5f, 10.9f, 2.4f, 10f, 3.5f, 10f)
			lineTo(5.5f, 10f)
			cubicTo(6.6f, 10f, 7.5f, 10.9f, 7.5f, 12f)
			lineTo(7.5f, 12.2f)
		}
	}
	val openLink = remember {
		Path().apply {
			moveTo(2.5f, 9f)
			lineTo(2.5f, 5f)
			cubicTo(2.5f, 3.1f, 4.1f, 1.5f, 6f, 1.5f)
			cubicTo(7.9f, 1.5f, 9.5f, 3.1f, 9.5f, 5f)
			lineTo(9.5f, 11f)
			cubicTo(9.5f, 12.9f, 7.9f, 14.5f, 6f, 14.5f)
			cubicTo(5.2f, 14.5f, 4.5f, 14.2f, 3.9f, 13.7f)
		}
	}
	Canvas(modifier = modifier) {
		val viewportHeight = if (linked) 24f else 16f
		val scale = minOf(size.width / 12f, size.height / viewportHeight)
		withTransform({
			translate((size.width - 12f * scale) / 2f, (size.height - viewportHeight * scale) / 2f)
			scale(scale, scale, pivot = Offset.Zero)
		}) {
			val stroke = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
			if (linked) {
				drawPath(upperLink, tint, style = stroke)
				drawPath(lowerLink, tint, style = stroke)
			} else {
				drawPath(openLink, tint, style = stroke)
			}
		}
	}
}

/** Plus: add. */
@Composable
fun IconAdd(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	line(9f, 2f, 9f, 16f, 1.6f)
	line(2f, 9f, 16f, 9f, 1.6f)
}

/** A camera: a saved snapshot of the current pose. */
@Composable
fun IconSnapshot(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	shape(path {
		m(1.8f, 6.6f); q(1.8f, 5.2f, 3.2f, 5.2f); l(5.4f, 5.2f); l(6.8f, 3f); l(11.2f, 3f); l(12.6f, 5.2f)
		l(14.8f, 5.2f); q(16.2f, 5.2f, 16.2f, 6.6f); l(16.2f, 13.6f); q(16.2f, 15f, 14.8f, 15f)
		l(3.2f, 15f); q(1.8f, 15f, 1.8f, 13.6f); z()
	})
	ring(9f, 10f, 2.9f)
}

/** A folder: a project, a part. */
@Composable
fun IconFolder(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) { folder() }

/** A disclosure chevron, pointing down when [expanded] and right when not. */
@Composable
fun IconChevron(
	expanded: Boolean,
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) {
	if (expanded) outline(path { m(4.2f, 6.6f); l(9f, 11.4f); l(13.8f, 6.6f) }, 1.6f)
	else outline(path { m(6.6f, 4.2f); l(11.4f, 9f); l(6.6f, 13.8f) }, 1.6f)
}

/** A check mark. */
@Composable
fun IconCheck(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = Color.White,
) = GridIcon(modifier, tint) { checkMark() }

/** A cross: close, remove. */
@Composable
fun IconClose(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) { cross(reach = 7f, width = 1.5f) }

/** Circle with a lowercase “i” — toggle for optional help captions. */
@Composable
fun IconInfo(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) {
	ring(9f, 9f, 7.6f)
	dot(9f, 5.4f, 1.2f)
	line(9f, 8.4f, 9f, 13f, 1.5f)
}

/** A deform path, its middle anchor in the highlight colour the canvas draws a selected point in. */
@Composable
fun IconDeformPath(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	val highlight = LocalToolColors.current.highlight
	GridIcon(modifier, tint) { deformPath(anchor = tone(highlight)) }
}

/** The skeleton overlay: one bone, as the icon is too small to read a chain. */
@Composable
fun IconSkeleton(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = IconBone(modifier, tint)

/** A single bone, drawn like the canvas bones: the bone tree rows and the skeleton overlay. */
@Composable
fun IconBone(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		drawSingleBoneIcon(tint)
	}
}

/** A bin: delete. */
@Composable
fun IconTrash(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) { trashCan() }

/** Rotation deformer: a pivot, its handle and the turn it gives, as the toolbar draws it. */
@Composable
fun IconRotationDeformer(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) { rotationDeformer() }

/** Six-dot grip used as a drag handle (e.g. parameter-panel reorder). */
@Composable
fun IconDragHandle(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) {
	for (x in floatArrayOf(6.4f, 11.6f)) for (y in floatArrayOf(4f, 9f, 14f)) dot(x, y, 1.4f)
}

/** Move / float an item to the armature root. */
@Composable
fun IconMoveToRoot(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	line(3f, 3f, 15f, 3f)
	line(9f, 6.4f, 9f, 15.6f)
	chevron(9f, 6.4f, 0f, -1f, 3.8f)
}

/** Expand a tree branch: the stem with its children out. */
@Composable
fun IconExpandBranch(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	outline(path { m(4f, 3.4f); l(4f, 14f); l(11.4f, 14f) })
	line(4f, 9f, 11.4f, 9f)
	dot(13.4f, 9f, 2f)
	dot(13.4f, 14f, 2f)
	dot(4f, 3.4f, 2f)
}

/** Collapse a tree branch: the stem with its children folded into one box. */
@Composable
fun IconCollapseBranch(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	line(4f, 3.4f, 4f, 9f)
	line(4f, 9f, 9.6f, 9f)
	dot(4f, 3.4f, 2f)
	panel(9.6f, 6.4f, 6f, 5.2f, 1f)
}

/** Expand all items in tree hierarchy (chevrons pointing outward). */
@Composable
fun IconExpandAll(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	outline(path { m(4f, 7.2f); l(9f, 2.6f); l(14f, 7.2f) }, 1.6f)
	outline(path { m(4f, 10.8f); l(9f, 15.4f); l(14f, 10.8f) }, 1.6f)
}

/** Collapse all items in tree hierarchy (chevrons pointing inward). */
@Composable
fun IconCollapseAll(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	outline(path { m(4f, 2.6f); l(9f, 7.2f); l(14f, 2.6f) }, 1.6f)
	outline(path { m(4f, 15.4f); l(9f, 10.8f); l(14f, 15.4f) }, 1.6f)
}

/** Draw order: a stack of layers. */
@Composable
fun IconDrawOrder(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	outline(path { m(2f, 12.2f); l(9f, 15.8f); l(16f, 12.2f) })
	outline(path { m(2f, 9f); l(9f, 12.6f); l(16f, 9f) })
	shape(path { m(9f, 2.2f); l(16f, 5.8f); l(9f, 9.4f); l(2f, 5.8f); z() })
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
				val transition = rememberTransition(expandedStates, "TreeContextMenuTransition")
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
				Spacer(Modifier.width(5.dp))
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
@OptIn(ExperimentalComposeUiApi::class)
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
	/** When true, focusing the field selects the whole value so typing replaces it. */
	selectAllOnFocus: Boolean = false,
	/** When false, pauses while typing do not end the edit session (inline number editors). */
	endEditOnSettle: Boolean = true,
	/** Always called when the field loses focus, even if the value never changed. */
	onFocusLost: (() -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	var editing by remember { mutableStateOf(false) }
	var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
	// Texts sent through onValueChange that [value] has not echoed back yet. The echo of a keystroke can arrive after
	// the next keystroke, so an echo is matched against this queue rather than the field: resetting the field to it
	// would drop the newer keystroke. Only a focused field is typed in, so an unfocused one always takes [value].
	val unechoed = remember { ArrayDeque<String>() }
	// The node reports unfocused as soon as it is attached. That is not a blur.
	var gainedFocus by remember { mutableStateOf(false) }
	val editorRegions = LocalInlineEditorRegions.current
	LaunchedEffect(value) {
		val echo = if (gainedFocus) unechoed.indexOf(value) else -1
		if (echo >= 0) {
			repeat(echo + 1) { unechoed.removeFirst() }
			return@LaunchedEffect
		}
		unechoed.clear()
		if (fieldValue.text != value) {
			fieldValue = TextFieldValue(value, TextRange(value.length))
		}
	}
	LaunchedEffect(value, editing, endEditOnSettle) {
		if (!endEditOnSettle || !editing) return@LaunchedEffect
		delay(EDIT_SETTLE_MILLIS)
		editing = false
		onEditEnd()
	}
	DisposableEffect(Unit) { onDispose { if (editing) onEditEnd() } }

	val textStyle = if (isMono) typography.mono else typography.body

	BasicTextField(
		value = fieldValue,
		onValueChange = { input ->
			if (!editing) { editing = true; onEditStart() }
			val changed = input.text != fieldValue.text
			fieldValue = input
			if (changed) {
				if (unechoed.size >= MAX_UNECHOED_EDITS) unechoed.removeFirst()
				unechoed.addLast(input.text)
				onValueChange(input.text)
			}
		},
		modifier = modifier
			.height(height)
			.then(
				if (editorRegions == null) Modifier else Modifier.onPointerEvent(
					PointerEventType.Press,
					pass = PointerEventPass.Initial,
				) {
					editorRegions.pressedInside = true
				},
			)
			.background(colors.inputBackground, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, if (isHovered && enabled) colors.borderHover else colors.border), RoundedCornerShape(2.dp))
			.padding(horizontal = 6.dp)
			.onFocusChanged { focus ->
				if (focus.isFocused) {
					gainedFocus = true
					if (selectAllOnFocus) {
						fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
					}
				} else if (gainedFocus) {
					gainedFocus = false
					unechoed.clear()
					if (editing) {
						editing = false
						onEditEnd()
					}
					onFocusLost?.invoke()
				}
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

@OptIn(ExperimentalFoundationApi::class)
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
	tooltip: String? = null,
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

	val chipBox = @Composable {
		Box(
			modifier = (if (tooltip.isNullOrBlank()) modifier else Modifier)
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

	if (!tooltip.isNullOrBlank()) {
		TooltipArea(
			tooltip = {
				Surface(
					color = colors.panelElevated,
					shape = RoundedCornerShape(2.dp),
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
			delayMillis = 400,
			modifier = modifier,
		) {
			chipBox()
		}
	} else {
		chipBox()
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
/** Bounds [CompactTextField]'s queue of unechoed edits when a caller never echoes them back. */
private const val MAX_UNECHOED_EDITS = 64
