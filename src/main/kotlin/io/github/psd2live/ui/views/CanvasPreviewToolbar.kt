package io.github.psd2live.ui.views

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass

/**
 * Preview-tab twin of the edit left toolbar: play/pause and mouse tracking live on the canvas
 * instead of the parameters dock, so the artist can reach them without leaving the viewport.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.CanvasPreviewToolbar(
	animationEnabled: Boolean,
	mouseTrackingEnabled: Boolean,
	enabled: Boolean,
	onToggleAnimation: () -> Unit,
	onToggleMouseTracking: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val toolbarInteractionSource = remember { MutableInteractionSource() }
	val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
	var isHoveredByEvent by remember { mutableStateOf(false) }
	val isToolbarHovered = isHoveredBySource || isHoveredByEvent

	val animatedWidth by animateDpAsState(
		targetValue = if (isToolbarHovered) 156.dp else 34.dp,
		animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
	)
	val textAlpha by animateFloatAsState(
		targetValue = if (isToolbarHovered) 1f else 0f,
		animationSpec = tween(
			durationMillis = if (isToolbarHovered) 150 else 80,
			delayMillis = if (isToolbarHovered) 40 else 0,
			easing = FastOutSlowInEasing,
		),
	)
	val textOffset by animateDpAsState(
		targetValue = if (isToolbarHovered) 0.dp else (-6).dp,
		animationSpec = tween(
			durationMillis = if (isToolbarHovered) 180 else 80,
			delayMillis = if (isToolbarHovered) 30 else 0,
			easing = FastOutSlowInEasing,
		),
	)
	val elevation by animateDpAsState(
		targetValue = if (isToolbarHovered) 8.dp else 2.dp,
		animationSpec = tween(durationMillis = 200),
	)

	val isExpanded = animatedWidth > 42.dp
	val animationLabel = if (animationEnabled) {
		tr("preview.animation.pause")
	} else {
		tr("preview.animation.play")
	}
	val trackingLabel = if (mouseTrackingEnabled) {
		tr("preview.mouseTracking.on")
	} else {
		tr("preview.mouseTracking.off")
	}

	Column(
		modifier = modifier
			.align(Alignment.TopStart)
			.padding(start = 8.dp, top = 8.dp)
			.width(animatedWidth)
			.frostedGlass(
				shape = RoundedCornerShape(6.dp),
				isHovered = isToolbarHovered,
				elevation = elevation,
				alpha = 0.78f,
			)
			.hoverable(toolbarInteractionSource)
			.onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
			.onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
			.padding(3.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		PreviewToolRow(
			label = animationLabel,
			isActive = animationEnabled,
			isToolbarExpanded = isExpanded,
			textAlpha = textAlpha,
			textOffset = textOffset,
			enabled = enabled,
			onClick = onToggleAnimation,
			icon = { tint ->
				if (animationEnabled) {
					IconPause(modifier = Modifier.size(14.dp), tint = tint)
				} else {
					IconPlay(modifier = Modifier.size(14.dp), tint = tint)
				}
			},
		)
		PreviewToolRow(
			label = trackingLabel,
			isActive = mouseTrackingEnabled,
			isToolbarExpanded = isExpanded,
			textAlpha = textAlpha,
			textOffset = textOffset,
			enabled = enabled,
			onClick = onToggleMouseTracking,
			icon = { tint ->
				IconMouse(
					active = mouseTrackingEnabled,
					modifier = Modifier.size(14.dp),
					tint = tint,
				)
			},
		)
	}
}

@Composable
private fun PreviewToolRow(
	label: String,
	isActive: Boolean,
	isToolbarExpanded: Boolean,
	textAlpha: Float,
	textOffset: androidx.compose.ui.unit.Dp,
	enabled: Boolean,
	icon: @Composable (Color) -> Unit,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val itemInteractionSource = remember { MutableInteractionSource() }
	val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

	val bg = when {
		isActive -> colors.accent.copy(alpha = 0.24f)
		isItemHovered -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}
	val tint = when {
		!enabled -> colors.textDisabled
		isActive -> colors.accent
		isItemHovered -> colors.textPrimary
		else -> colors.textMuted
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(28.dp)
			.clip(RoundedCornerShape(3.dp))
			.background(bg)
			.semantics { contentDescription = label }
			.clickable(
				interactionSource = itemInteractionSource,
				indication = null,
				enabled = enabled,
				onClick = onClick,
			),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier.size(28.dp),
			contentAlignment = Alignment.Center,
		) {
			icon(tint)
		}

		if (isToolbarExpanded) {
			Text(
				text = label,
				color = when {
					!enabled -> colors.textDisabled
					isActive || isItemHovered -> colors.textPrimary
					else -> colors.textMuted
				},
				fontSize = 11.5.sp,
				fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.weight(1f)
					.offset(x = textOffset)
					.alpha(textAlpha)
					.padding(end = 6.dp),
			)
		}
	}
}
