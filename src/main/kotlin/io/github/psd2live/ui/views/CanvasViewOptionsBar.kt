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
import io.github.psd2live.ui.components.IconDeformPath
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSelectedOnly
import io.github.psd2live.ui.components.IconTextureView
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.state.TabViewOptions
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget

/**
 * Bottom-right mirror of the left tool palette: the most-used per-tab display toggles, so the
 * View menu does not have to be opened mid-edit. Icons stay on the trailing edge; hovering expands
 * the rail leftward and reveals labels the same way the left toolbar reveals them rightward.
 *
 * Path guides are Edit-only overlays, so [showPathGuides] hides that row on Preview tabs.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CanvasViewOptionsBar(
	options: TabViewOptions,
	onOptionsChange: (TabViewOptions) -> Unit,
	showPathGuides: Boolean = true,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val toolbarInteractionSource = remember { MutableInteractionSource() }
	val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
	var isHoveredByEvent by remember { mutableStateOf(false) }
	val isToolbarHovered = isHoveredBySource || isHoveredByEvent

	val animatedWidth by animateDpAsState(
		targetValue = if (isToolbarHovered) 168.dp else 34.dp,
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
		targetValue = if (isToolbarHovered) 0.dp else 6.dp,
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

	fun apply(updated: TabViewOptions) {
		onOptionsChange(updated.normalized())
	}

	val isExpanded = animatedWidth > 42.dp

	Column(
		modifier = modifier
			.tutorialTarget(TutorialTargetId.VIEW_OPTIONS_BAR)
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
		horizontalAlignment = Alignment.End,
	) {
		ViewOptionRow(
			label = tr("canvas.visibility.texture"),
			isChecked = options.showTexture,
			isToolbarExpanded = isExpanded,
			textAlpha = textAlpha,
			textOffset = textOffset,
			icon = { IconTextureView(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showTexture = !options.showTexture)) },
		)
		ViewOptionRow(
			label = tr("canvas.visibility.mesh"),
			isChecked = options.showMesh,
			isToolbarExpanded = isExpanded,
			textAlpha = textAlpha,
			textOffset = textOffset,
			icon = { IconMeshWireframe(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showMesh = !options.showMesh)) },
		)
		ViewOptionRow(
			label = tr("canvas.visibility.warp"),
			isChecked = options.showWarp,
			isToolbarExpanded = isExpanded,
			textAlpha = textAlpha,
			textOffset = textOffset,
			icon = { IconWarpDeformer(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showWarp = !options.showWarp)) },
		)
		ViewOptionRow(
			label = tr("canvas.visibility.rotation"),
			isChecked = options.showRotation,
			isToolbarExpanded = isExpanded,
			textAlpha = textAlpha,
			textOffset = textOffset,
			icon = { IconRotationDeformer(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showRotation = !options.showRotation)) },
		)
		if (showPathGuides) {
			ViewOptionRow(
				label = tr("canvas.visibility.paths"),
				isChecked = options.showDeformPaths,
				isToolbarExpanded = isExpanded,
				textAlpha = textAlpha,
				textOffset = textOffset,
				icon = { IconDeformPath(tint = it, modifier = Modifier.size(14.dp)) },
				onClick = { apply(options.copy(showDeformPaths = !options.showDeformPaths)) },
			)
			if (options.showDeformPaths) {
				ViewOptionRow(
					label = tr("canvas.information.pathWidth"),
					isChecked = options.pathShowWidth,
					isToolbarExpanded = isExpanded,
					textAlpha = textAlpha,
					textOffset = textOffset,
					icon = { IconDeformPath(tint = it.copy(alpha = 0.75f), modifier = Modifier.size(14.dp)) },
					onClick = { apply(options.copy(pathShowWidth = !options.pathShowWidth)) },
				)
				ViewOptionRow(
					label = tr("canvas.information.pathHardness"),
					isChecked = options.pathShowHardness,
					isToolbarExpanded = isExpanded,
					textAlpha = textAlpha,
					textOffset = textOffset,
					icon = { IconDeformPath(tint = it.copy(alpha = 0.55f), modifier = Modifier.size(14.dp)) },
					onClick = { apply(options.copy(pathShowHardness = !options.pathShowHardness)) },
				)
			}
		}

		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 4.dp, vertical = 2.dp)
				.height(1.dp)
				.background(colors.border.copy(alpha = 0.35f)),
		)

		ViewOptionRow(
			label = tr("canvas.information.selectedOnly"),
			isChecked = options.filterSelectedOnly,
			isToolbarExpanded = isExpanded,
			textAlpha = textAlpha,
			textOffset = textOffset,
			icon = { IconSelectedOnly(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(filterSelectedOnly = !options.filterSelectedOnly)) },
		)
	}
}

@Composable
private fun ViewOptionRow(
	label: String,
	isChecked: Boolean,
	isToolbarExpanded: Boolean,
	textAlpha: Float,
	textOffset: androidx.compose.ui.unit.Dp,
	icon: @Composable (Color) -> Unit,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val itemInteractionSource = remember { MutableInteractionSource() }
	val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

	val bg = when {
		isChecked -> colors.accent.copy(alpha = 0.24f)
		isItemHovered -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}
	val tint = when {
		isChecked -> colors.accent
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
				onClick = onClick,
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.End,
	) {
		if (isToolbarExpanded) {
			Text(
				text = label,
				color = when {
					isChecked -> colors.textPrimary
					isItemHovered -> colors.textPrimary
					else -> colors.textMuted
				},
				fontSize = 11.5.sp,
				fontWeight = if (isChecked) FontWeight.Medium else FontWeight.Normal,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.weight(1f)
					.offset(x = textOffset)
					.alpha(textAlpha)
					.padding(start = 6.dp, end = 2.dp),
			)
		}
		Box(
			modifier = Modifier.size(28.dp),
			contentAlignment = Alignment.Center,
		) {
			icon(tint)
		}
	}
}
