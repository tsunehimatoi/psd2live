package io.github.psd2live.ui.views.texture

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.exp
import kotlin.math.ln

/*
 * The atlas page's floating controls, in the edit canvas's language: a frosted bar like its mode bar, an accent
 * button like its mode button, chips like its deform-level chips and a menu that scales in like its mode menu.
 */

/**
 * Marks a control floating over a canvas as the canvas's chrome: presses and wheel turns that land on it, its
 * padding and the gaps between its rows included, stop here instead of reaching the canvas under it. The
 * canvas skips consumed presses, so no control has to be fenced off by a hand-kept rectangle.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.canvasChrome(): Modifier = this
	.onPointerEvent(PointerEventType.Press) { event -> event.changes.forEach { it.consume() } }
	.onPointerEvent(PointerEventType.Scroll) { event -> event.changes.forEach { it.consume() } }

/** A frosted bar of controls floating over a canvas; it lifts while hovered, as the edit canvas's mode bar does. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FloatingBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
	val interactionSource = remember { MutableInteractionSource() }
	val hoveredBySource by interactionSource.collectIsHoveredAsState()
	var hoveredByEvent by remember { mutableStateOf(false) }
	val hovered = hoveredBySource || hoveredByEvent
	val elevation by animateDpAsState(if (hovered) 8.dp else 2.dp, tween(200))
	Row(
		modifier
			.canvasChrome()
			.frostedGlass(RoundedCornerShape(6.dp), isHovered = hovered, elevation = elevation, alpha = if (hovered) 0.88f else 0.78f)
			.hoverable(interactionSource)
			.onPointerEvent(PointerEventType.Enter) { hoveredByEvent = true }
			.onPointerEvent(PointerEventType.Exit) { hoveredByEvent = false }
			.padding(horizontal = 4.dp, vertical = 3.dp)
			.animateContentSize(tween(200, easing = FastOutSlowInEasing)),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(3.dp),
		content = content,
	)
}

/** A short vertical rule between groups of a [FloatingBar]. */
@Composable
internal fun BarDivider() {
	Box(Modifier.padding(horizontal = 2.dp).height(14.dp).width(1.dp).background(LocalToolColors.current.border.copy(alpha = 0.45f)))
}

/** A hover tooltip in the canvas bars' style. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BarTooltip(text: String?, content: @Composable () -> Unit) {
	if (text.isNullOrBlank()) { content(); return }
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	TooltipArea(
		tooltip = {
			Surface(color = colors.panelElevated, shape = RoundedCornerShape(4.dp), border = BorderStroke(0.5.dp, colors.border), elevation = 4.dp) {
				Text(text, style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textPrimary,
					modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 7.dp, vertical = 4.dp))
			}
		},
		delayMillis = 450,
	) { content() }
}

/**
 * A chip of a [FloatingBar]: an optional icon and a label, accent-filled while [selected] - the edit canvas's
 * deform-level chip. [chevron] marks one that opens a menu.
 */
@Composable
internal fun BarChip(
	label: String?,
	selected: Boolean,
	onClick: () -> Unit,
	enabled: Boolean = true,
	tooltip: String? = null,
	chevron: Boolean = false,
	open: Boolean = false,
	icon: (@Composable (Color) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val background by animateColorAsState(when {
		selected -> colors.accent.copy(alpha = 0.22f)
		open || hovered && enabled -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}, tween(80))
	val tint by animateColorAsState(when {
		!enabled -> colors.textMuted.copy(alpha = 0.5f)
		selected -> colors.accent
		hovered || open -> colors.textPrimary
		else -> colors.textMuted
	}, tween(80))
	BarTooltip(tooltip) {
		Row(
			Modifier
				.height(24.dp)
				.defaultMinSize(minWidth = 24.dp)
				.clip(RoundedCornerShape(4.dp))
				.background(background)
				.border(0.5.dp, if (selected) colors.accent.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(4.dp))
				.hoverable(interactionSource)
				.clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
				.semantics { contentDescription = label ?: tooltip ?: "" }
				.padding(horizontal = if (label == null && !chevron) 5.dp else 7.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			if (icon != null) icon(tint)
			if (icon != null && label != null) Spacer(Modifier.width(5.dp))
			if (label != null) Text(label, color = tint, fontSize = 11.sp, maxLines = 1,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
			if (chevron) { Spacer(Modifier.width(4.dp)); Chevron(tint, open) }
		}
	}
}

/** The menu chevron of the mode button: it turns over while its menu is open. */
@Composable
internal fun Chevron(tint: Color, open: Boolean) {
	val turn by animateFloatAsState(if (open) 180f else 0f, tween(100, easing = FastOutSlowInEasing))
	GridIcon(Modifier.size(10.dp).rotate(turn), tint) {
		outline(path { m(3.6f, 6.8f); l(9f, 11.8f); l(14.4f, 6.8f) }, 1.6f)
	}
}

/**
 * The bar's primary action, shaped like the edit canvas's mode button: an accent body that runs [onClick] and a
 * chevron that opens its options. [onMenu] toggles the options; [open] says they show.
 */
@Composable
internal fun AccentSplitButton(
	label: String,
	icon: @Composable (Color) -> Unit,
	onClick: () -> Unit,
	open: Boolean,
	onMenu: () -> Unit,
	enabled: Boolean = true,
	tooltip: String? = null,
	menuTooltip: String? = null,
) {
	val colors = LocalToolColors.current
	val body = remember { MutableInteractionSource() }
	val menu = remember { MutableInteractionSource() }
	val bodyHovered by body.collectIsHoveredAsState()
	val menuHovered by menu.collectIsHoveredAsState()
	val tint = if (enabled) colors.accent else colors.textMuted.copy(alpha = 0.6f)
	fun fill(hovered: Boolean, pressed: Boolean) = when {
		!enabled -> colors.accent.copy(alpha = 0.08f)
		pressed -> colors.accent.copy(alpha = 0.3f)
		hovered -> colors.accent.copy(alpha = 0.26f)
		else -> colors.accent.copy(alpha = 0.18f)
	}
	val bodyFill by animateColorAsState(fill(bodyHovered, false), tween(80))
	val menuFill by animateColorAsState(fill(menuHovered, open), tween(80))
	Row(
		Modifier
			.height(24.dp)
			.clip(RoundedCornerShape(4.dp))
			.border(0.5.dp, colors.accent.copy(alpha = if (open) 0.75f else if (enabled) 0.5f else 0.2f), RoundedCornerShape(4.dp)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		BarTooltip(tooltip) {
			Row(
				Modifier
					.height(24.dp)
					.background(bodyFill)
					.hoverable(body)
					.clickable(interactionSource = body, indication = null, enabled = enabled, onClick = onClick)
					.semantics { contentDescription = label }
					.padding(start = 6.dp, end = 7.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				icon(tint)
				Spacer(Modifier.width(5.dp))
				Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
			}
		}
		Box(Modifier.width(0.5.dp).height(24.dp).background(colors.accent.copy(alpha = if (enabled) 0.4f else 0.15f)))
		BarTooltip(menuTooltip) {
			Box(
				Modifier
					.height(24.dp)
					.background(menuFill)
					.hoverable(menu)
					.clickable(interactionSource = menu, indication = null, enabled = enabled, onClick = onMenu)
					.semantics { contentDescription = menuTooltip ?: label }
					.padding(horizontal = 5.dp),
				contentAlignment = Alignment.Center,
			) { Chevron(tint, open) }
		}
	}
}

/**
 * The bar's one-click primary action, accent-filled like [AccentSplitButton]'s body: an optional icon and a label, or
 * the icon alone when [label] is null (its name then goes in the [tooltip]).
 */
@Composable
internal fun AccentButton(label: String?, onClick: () -> Unit, enabled: Boolean = true, tooltip: String? = null,
                          icon: (@Composable (Color) -> Unit)? = null) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val tint = if (enabled) colors.accent else colors.textMuted.copy(alpha = 0.6f)
	val fill by animateColorAsState(when {
		!enabled -> colors.accent.copy(alpha = 0.08f)
		hovered -> colors.accent.copy(alpha = 0.3f)
		else -> colors.accent.copy(alpha = 0.2f)
	}, tween(80))
	BarTooltip(tooltip) {
		Row(
			Modifier
				.height(24.dp)
				.defaultMinSize(minWidth = 24.dp)
				.clip(RoundedCornerShape(4.dp))
				.background(fill)
				.border(0.5.dp, colors.accent.copy(alpha = if (enabled) 0.55f else 0.2f), RoundedCornerShape(4.dp))
				.hoverable(interaction)
				.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
				.semantics { contentDescription = label ?: tooltip ?: "" }
				.padding(horizontal = if (label == null) 5.dp else if (icon != null) 8.dp else 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			if (icon != null) icon(tint)
			if (icon != null && label != null) Spacer(Modifier.width(5.dp))
			if (label != null) Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
		}
	}
}

/**
 * A menu dropping from a bar control, in the mode menu's style: frosted, scaling in from its anchor's corner.
 * Put it in the anchor's Box; [alignment] TopStart drops it under the anchor's left edge, TopEnd under its right.
 * [rise] opens it above the anchor instead, for controls at the bottom of a canvas (align the Box BottomStart).
 */
@Composable
internal fun FloatingMenu(
	expanded: Boolean,
	onDismiss: () -> Unit,
	width: Dp = 220.dp,
	alignment: Alignment = Alignment.TopStart,
	rise: Boolean = false,
	content: @Composable ColumnScope.() -> Unit,
) {
	val visibility = remember { MutableTransitionState(false) }
	visibility.targetState = expanded
	if (!visibility.currentState && !visibility.targetState) return
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val body: @Composable () -> Unit = {
		CompositionLocalProvider(LocalDensity provides density, LocalToolColors provides colors, LocalToolTypography provides typography) {
			val transition = rememberTransition(visibility, "FloatingMenu")
			val alpha by transition.animateFloat({
				if (false isTransitioningTo true) tween(120, easing = LinearOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
			}, "alpha") { if (it) 1f else 0f }
			val scale by transition.animateFloat({
				if (false isTransitioningTo true) tween(100, easing = LinearOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
			}, "scale") { if (it) 1f else 0.92f }
			val lift by transition.animateFloat({
				if (false isTransitioningTo true) tween(100, easing = FastOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
			}, "lift") { if (it) 0f else if (rise) 6f else -6f }
			Column(
				Modifier
					.graphicsLayer {
						this.alpha = alpha; scaleX = scale; scaleY = scale
						translationY = lift * density.density
						transformOrigin = TransformOrigin(if (alignment == Alignment.TopEnd || alignment == Alignment.BottomEnd) 0.88f else 0.12f, if (rise) 1f else 0f)
					}
					.width(width)
					.frostedGlass(RoundedCornerShape(7.dp), isHovered = true, elevation = 12.dp, baseColor = colors.panelElevated, alpha = 0.9f)
					.border(0.5.dp, colors.borderHover.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
					.padding(4.dp),
				verticalArrangement = Arrangement.spacedBy(1.dp),
				content = content,
			)
		}
	}
	Popup(
		alignment = alignment,
		offset = with(density) { IntOffset(0, (if (rise) -28 else 28).dp.roundToPx()) },
		onDismissRequest = onDismiss,
		properties = PopupProperties(focusable = true),
		content = body,
	)
}

/**
 * True inside a menu column whose one highlight glides to the hovered row, the way the mode menu's does: its rows
 * then leave the hover fill to it.
 */
internal val LocalMenuGlide = androidx.compose.runtime.staticCompositionLocalOf { false }

/** A caption over a group of [FloatingMenu] rows. */
@Composable
internal fun FloatingMenuSection(text: String) {
	Text(text.uppercase(), color = LocalToolColors.current.textMuted.copy(alpha = 0.8f), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
		letterSpacing = 0.4.sp, modifier = Modifier.padding(start = 8.dp, top = 5.dp, bottom = 2.dp))
}

/** A rule between groups of [FloatingMenu] rows. */
@Composable
internal fun FloatingMenuDivider() {
	Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).height(0.5.dp).background(LocalToolColors.current.border.copy(alpha = 0.5f)))
}

/**
 * A row of a [FloatingMenu], like the mode menu's: a bar at its edge while [selected] (unless [bar] is off, for
 * rows whose [control] shows the state), an icon, a label and an optional hint under it, a trailing note or control.
 */
@Composable
internal fun FloatingMenuRow(
	label: String,
	onClick: () -> Unit,
	selected: Boolean = false,
	enabled: Boolean = true,
	hint: String? = null,
	trailing: String? = null,
	bar: Boolean = true,
	icon: (@Composable (Color) -> Unit)? = null,
	control: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val tint by animateColorAsState(when {
		!enabled -> colors.textMuted.copy(alpha = 0.5f)
		selected -> colors.accent
		hovered -> colors.textPrimary
		else -> colors.textMuted
	}, tween(80))
	val glide = LocalMenuGlide.current
	val background by animateColorAsState(if (hovered && enabled && !glide) colors.controlHover.copy(alpha = 0.75f) else Color.Transparent, tween(80))
	val edge by animateFloatAsState(if (selected && bar) 1f else 0f, tween(80, easing = FastOutSlowInEasing))
	Row(
		Modifier
			.fillMaxWidth()
			.defaultMinSize(minHeight = 28.dp)
			.clip(RoundedCornerShape(5.dp))
			.background(background)
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
			.semantics { contentDescription = label }
			.padding(horizontal = 6.dp, vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(2.dp).height(14.dp * edge).clip(CircleShape).background(colors.accent.copy(alpha = edge)))
		Spacer(Modifier.width(5.dp))
		if (icon != null) {
			Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon(tint) }
			Spacer(Modifier.width(7.dp))
		} else Spacer(Modifier.width(2.dp))
		Column(Modifier.weight(1f)) {
			Text(label, color = if (enabled && (selected || hovered)) colors.textPrimary else tint, fontSize = 11.5.sp,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (hint != null) Text(hint, color = colors.textMuted.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
		if (trailing != null) Text(trailing, color = colors.textMuted.copy(alpha = 0.7f), fontSize = 9.5.sp, maxLines = 1,
			modifier = Modifier.padding(start = 6.dp))
		if (control != null) Box(Modifier.padding(start = 8.dp, end = 2.dp)) { control() }
	}
}

/** An on/off row of a [FloatingMenu]: a sliding track at the end shows [checked]. */
@Composable
internal fun FloatingMenuSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true,
                                hint: String? = null) {
	val colors = LocalToolColors.current
	val thumb by animateDpAsState(if (checked) 10.dp else 2.dp, tween(140))
	val track by animateColorAsState(when {
		!enabled -> colors.border.copy(alpha = 0.4f)
		checked -> colors.accent
		else -> colors.border
	}, tween(140))
	FloatingMenuRow(label, { onCheckedChange(!checked) }, selected = checked, enabled = enabled, hint = hint, bar = false, control = {
		Box(Modifier.size(20.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(track)) {
			Box(Modifier.offset(x = thumb, y = 2.dp).size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else 0.6f)))
		}
	})
}

/**
 * A value row of a [FloatingMenu], on one line: the label, a slider and the value as [display] shows it.
 * [logarithmic] spreads a size range that runs to the document's long side so the small sizes keep their room.
 */
@Composable
internal fun FloatingMenuSlider(
	label: String,
	value: Float,
	onValueChange: (Float) -> Unit,
	valueRange: ClosedFloatingPointRange<Float>,
	display: String,
	logarithmic: Boolean = false,
	enabled: Boolean = true,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 9.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(label, color = if (enabled) colors.textMuted else colors.textMuted.copy(alpha = 0.5f), fontSize = 10.5.sp, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.width(54.dp))
		val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
		val slider = Modifier.weight(1f).padding(horizontal = 4.dp)
		if (logarithmic && valueRange.start > 0f) {
			val start = ln(valueRange.start)
			val end = ln(valueRange.endInclusive)
			CompactSlider(ln(clamped), { onValueChange(exp(it).coerceIn(valueRange.start, valueRange.endInclusive)) },
				valueRange = start..end, modifier = slider, height = 12.dp, enabled = enabled)
		} else {
			CompactSlider(clamped, onValueChange, valueRange = valueRange, modifier = slider, height = 12.dp, enabled = enabled)
		}
		Text(display, color = if (enabled) colors.textPrimary else colors.textMuted, style = typography.monoSmall.copy(fontSize = 9.5.sp),
			maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(40.dp))
	}
}

/**
 * A row of a [FloatingMenu] that opens a second level: its icon, label and current value, and a chevron pointing to
 * where the level opens. [open] marks the level showing; [onOpen] runs on a hover or a click.
 */
@Composable
internal fun FloatingMenuSubmenuRow(label: String, open: Boolean, onOpen: () -> Unit, trailing: String? = null,
                                    icon: (@Composable (Color) -> Unit)? = null) {
	val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	LaunchedEffect(hovered) { if (hovered) onOpen() }
	val tint = if (open || hovered) colors.textPrimary else colors.textMuted
	// Open, the row keeps its fill while the pointer works the second level; hovered, a gliding highlight may light it.
	val glide = LocalMenuGlide.current
	val background by animateColorAsState(when {
		open -> colors.controlHover.copy(alpha = 0.75f)
		hovered && !glide -> colors.controlHover.copy(alpha = 0.75f)
		else -> Color.Transparent
	}, tween(80))
	Row(
		Modifier
			.fillMaxWidth()
			.height(26.dp)
			.clip(RoundedCornerShape(5.dp))
			.background(background)
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
			.semantics { contentDescription = label }
			.padding(horizontal = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(Modifier.width(7.dp))
		if (icon != null) {
			Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon(tint) }
			Spacer(Modifier.width(7.dp))
		}
		Text(label, color = tint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		if (trailing != null) Text(trailing, color = colors.textMuted.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1,
			modifier = Modifier.padding(horizontal = 4.dp))
		GridIcon(Modifier.size(10.dp).rotate(-90f), tint) { outline(path { m(3.6f, 6.8f); l(9f, 11.8f); l(14.4f, 6.8f) }, 1.6f) }
	}
}

/**
 * A value chip of a [FloatingBar]: the label and the value. Dragging it sideways scrubs the value, the way a
 * Blender field does; a click drops a [FloatingMenuSlider] under it for a precise pick.
 * [scrub] maps a horizontal drag of one pixel to a new value from the value the drag started at.
 */
@Composable
internal fun BarValueChip(
	label: String,
	display: String,
	value: Float,
	onValueChange: (Float) -> Unit,
	valueRange: ClosedFloatingPointRange<Float>,
	scrub: (start: Float, dx: Float) -> Float,
	logarithmic: Boolean = false,
	enabled: Boolean = true,
	tooltip: String? = null,
	onCommit: () -> Unit = {},
	rise: Boolean = false,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	var open by remember { mutableStateOf(false) }
	var dragging by remember { mutableStateOf(false) }
	val background by animateColorAsState(when {
		dragging || open -> colors.accent.copy(alpha = 0.18f)
		hovered && enabled -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}, tween(80))
	val currentValue by rememberUpdatedState(value)
	val change by rememberUpdatedState(onValueChange)
	val commit by rememberUpdatedState(onCommit)
	val scrubber by rememberUpdatedState(scrub)
	Box {
		BarTooltip(tooltip) {
			Row(
				Modifier
					.height(24.dp)
					.clip(RoundedCornerShape(4.dp))
					.background(background)
					.border(0.5.dp, if (dragging || open) colors.accent.copy(alpha = 0.45f) else colors.border.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
					.hoverable(interactionSource)
					.pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)))
					.pointerInput(enabled) {
						if (!enabled) return@pointerInput
						awaitEachGesture {
							val down = awaitFirstDown()
							down.consume()
							val start = currentValue
							var moved = false
							while (true) {
								val event = awaitPointerEvent()
								val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
								if (!pointer.pressed) { pointer.consume(); break }
								val dx = pointer.position.x - down.position.x
								if (!moved && kotlin.math.abs(dx) > viewConfiguration.touchSlop / 2f) { moved = true; dragging = true }
								if (moved) change(scrubber(start, dx).coerceIn(valueRange.start, valueRange.endInclusive))
								pointer.consume()
							}
							if (moved) { dragging = false; commit() } else open = !open
						}
					}
					.semantics { contentDescription = "$label $display" }
					.padding(horizontal = 5.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(label, color = if (enabled) colors.textMuted else colors.textMuted.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
				Spacer(Modifier.width(4.dp))
				Text(display, color = if (enabled) colors.textPrimary else colors.textMuted, style = typography.monoSmall.copy(fontSize = 10.sp), maxLines = 1)
			}
		}
		FloatingMenu(open, { open = false; commit() }, width = 200.dp, alignment = if (rise) Alignment.BottomStart else Alignment.TopStart, rise = rise) {
			FloatingMenuSlider(label, value, onValueChange, valueRange, display, logarithmic, enabled)
		}
	}
}

/**
 * A choice of a few, side by side, in the mode menu's motion: the choice in force sits on an accent pill that glides
 * to a new pick, and a second, fainter pill glides after the pointer from segment to segment. A segment shows its
 * [icon], or its [label] when it has none; [tooltip] names it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <T> GlidingSegments(
	choices: List<T>,
	selected: T,
	onSelect: (T) -> Unit,
	label: (T) -> String?,
	modifier: Modifier = Modifier,
	tooltip: (T) -> String? = { null },
	iconOf: (@Composable (T, Color) -> Unit)? = null,
	enabled: Boolean = true,
) {
	val colors = LocalToolColors.current
	val density = LocalDensity.current
	val bounds = remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Float, Float>>() }
	var hovered by remember { mutableStateOf<Int?>(null) }
	val selectedIndex = choices.indexOf(selected)

	@Composable
	fun pill(index: Int?, fill: Color, edge: Color) {
		val target = index?.let { bounds[it] }
		val x = remember { androidx.compose.animation.core.Animatable(0f) }
		val w = remember { androidx.compose.animation.core.Animatable(0f) }
		val shown by animateFloatAsState(if (target != null) 1f else 0f, tween(100, easing = FastOutSlowInEasing))
		LaunchedEffect(target) {
			val (left, width) = target ?: return@LaunchedEffect
			// Appearing, it starts on its segment; moving, it glides there.
			if (shown < 0.05f) { x.snapTo(left); w.snapTo(width) }
			else kotlinx.coroutines.coroutineScope {
				launch { x.animateTo(left, tween(110, easing = FastOutSlowInEasing)) }
				launch { w.animateTo(width, tween(110, easing = FastOutSlowInEasing)) }
			}
		}
		Box(
			Modifier
				.offset { IntOffset(x.value.roundToInt(), 0) }
				.width(with(density) { w.value.toDp() })
				.height(22.dp)
				.alpha(shown)
				.clip(RoundedCornerShape(4.dp))
				.background(fill)
				.border(0.5.dp, edge, RoundedCornerShape(4.dp)),
		)
	}

	Box(modifier.onPointerEvent(PointerEventType.Exit) { hovered = null }, contentAlignment = Alignment.CenterStart) {
		pill(hovered?.takeIf { it != selectedIndex && enabled }, colors.controlHover.copy(alpha = 0.75f), Color.Transparent)
		pill(selectedIndex.takeIf { it >= 0 }, colors.accent.copy(alpha = 0.22f), colors.accent.copy(alpha = 0.5f))
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
			choices.forEachIndexed { i, choice ->
				val isSelected = i == selectedIndex
				val isHovered = hovered == i
				val tint by animateColorAsState(when {
					!enabled -> colors.textMuted.copy(alpha = 0.5f)
					isSelected -> colors.accent
					isHovered -> colors.textPrimary
					else -> colors.textMuted
				}, tween(80))
				// Measured outside the tooltip, whose own box would make every segment sit at its origin.
				Box(
					Modifier
						.onGloballyPositioned { bounds[i] = it.positionInParent().x to it.size.width.toFloat() }
						.onPointerEvent(PointerEventType.Enter) { hovered = i },
				) {
				BarTooltip(tooltip(choice)) {
					Row(
						Modifier
							.height(22.dp)
							.defaultMinSize(minWidth = 24.dp)
							.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) { onSelect(choice) }
							.semantics { contentDescription = label(choice) ?: tooltip(choice) ?: "" }
							.padding(horizontal = if (iconOf != null && label(choice) == null) 5.dp else 7.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.Center,
					) {
						if (iconOf != null) iconOf(choice, tint)
						val text = label(choice)
						if (iconOf != null && text != null) Spacer(Modifier.width(5.dp))
						if (text != null) Text(text, color = tint, fontSize = 11.sp, maxLines = 1,
							fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
					}
				}
				}
			}
		}
	}
}

/** One choice of a group in a [FloatingMenu]: a radio mark at the end, filled while [selected]. */
@Composable
internal fun FloatingMenuRadio(label: String, selected: Boolean, onSelect: () -> Unit, enabled: Boolean = true, hint: String? = null,
                               icon: (@Composable (Color) -> Unit)? = null) {
	FloatingMenuRow(label, onSelect, selected = selected, enabled = enabled, hint = hint, bar = false, icon = icon,
		control = { RadioMark(selected, enabled) })
}

/** A radio mark: a ring, with an accent dot while [selected]. */
@Composable
internal fun RadioMark(selected: Boolean, enabled: Boolean = true) {
	val colors = LocalToolColors.current
	val mark by animateFloatAsState(if (selected) 1f else 0f, tween(120, easing = FastOutSlowInEasing))
	val rim = if (selected) colors.accent else colors.textMuted.copy(alpha = 0.8f)
	GridIcon(Modifier.size(12.dp).alpha(if (enabled) 1f else 0.5f), rim) {
		ring(9f, 9f, 7.4f)
		if (mark > 0f) dot(9f, 9f, 4.2f * mark, colors.accent)
	}
}

/** The notices of the last command and the layout, one per line, in a frosted banner the user can close. */
@Composable
internal fun NoticeBanner(messages: List<Pair<String, Color>>, onClose: (() -> Unit)?, modifier: Modifier = Modifier,
                          actions: List<Pair<String, () -> Unit>> = emptyList()) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val accent = messages.firstOrNull()?.second ?: colors.warning
	Row(
		modifier
			.canvasChrome()
			.widthIn(max = 480.dp)
			.frostedGlass(RoundedCornerShape(6.dp), elevation = 4.dp, alpha = 0.9f)
			.border(0.5.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
			.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.Top,
	) {
		GridIcon(Modifier.padding(top = 1.dp).size(14.dp), accent) {
			// A warning triangle with its mark.
			shape(path { m(9f, 1.8f); l(16.4f, 15.6f); l(1.6f, 15.6f); z() })
			line(9f, 6.8f, 9f, 10.8f)
			dot(9f, 13.2f, 1.1f)
		}
		Column(Modifier.weight(1f, fill = false).padding(start = 6.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			for ((message, color) in messages) {
				Text(message, style = typography.caption.copy(fontSize = 10.5.sp), color = if (color == colors.error) color else colors.textPrimary,
					maxLines = 3, overflow = TextOverflow.Ellipsis)
			}
			if (actions.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				for ((label, onClick) in actions) AccentButton(label, onClick)
			}
		}
		if (onClose != null) Box(Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onClose).padding(2.dp)) { IconClose(tint = colors.textMuted) }
	}
}

/** Arranged by mesh shape: two triangles nested in one square. */
@Composable
internal fun IconArrangeMesh(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) {
	shape(path { m(2.4f, 4.6f); l(2.4f, 15.6f); l(13.4f, 15.6f); z() })
	shape(path { m(4.6f, 2.4f); l(15.6f, 2.4f); l(15.6f, 13.4f); z() })
}

/** Arranged by rectangle: two rectangles side by side. */
@Composable
internal fun IconArrangeRect(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) {
	panel(2.4f, 2.6f, 5.8f, 12.8f)
	panel(9.8f, 2.6f, 5.8f, 8.4f)
}

/** Only the selection: a dashed frame round one tile. */
@Composable
internal fun IconArrangeSelection(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) {
	box(2f, 2f, 14f, 14f, 1.6f, ICON_FINE, dash = floatArrayOf(1.6f, 2.6f))
	panel(5.6f, 5.6f, 6.8f, 6.8f, 1f)
}

/** The atlas budget: a stack of pages, the front one divided into tiles. */
@Composable
internal fun IconAtlasPages(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) {
	behind(rectPath(2f, 5f, 11f, 11f, 1.2f)) { box(5f, 2f, 11f, 11f, 1.2f, ICON_FINE) }
	panel(2f, 5f, 11f, 11f)
	line(2f, 11.2f, 13f, 11.2f, ICON_FINE)
	line(7.4f, 5f, 7.4f, 16f, ICON_FINE)
}

/** The automatic arrangement: two tiles with a circling arrow round them. */
@Composable
internal fun IconArrangeAuto(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) {
	panel(5.4f, 5.4f, 3.2f, 7.2f, 0.8f, ICON_FINE)
	panel(9.6f, 5.4f, 3.2f, 4f, 0.8f, ICON_FINE)
	arcArrow(9f, 9f, 7.2f, -60f, 210f)
}
